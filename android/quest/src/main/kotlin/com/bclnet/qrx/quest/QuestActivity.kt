/*
 * QuestActivity.kt
 * QRX (Meta Quest)
 *
 * The Quest app: a Meta Spatial SDK scene with passthrough on, the QR codes
 * in the room turned into glyph panels placed where the codes are, and a
 * control panel with the status, settings and Bluetooth (BLUE/1.0 server,
 * nearby devices).
 *
 * Placement comes from MRUK's QR code tracker: the headset tracks each code
 * as an entity with a 6DoF world pose and the decoded payload. The
 * passthrough camera + ML Kit scan stays as the fallback for codes the
 * tracker cannot read (QR versions above 10), placed by `QrPoseEstimator`.
 */
package com.bclnet.qrx.quest

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.bclnet.jsonscene.SceneDocument
import com.bclnet.jsonscene.spatial.SpatialSceneRenderer
import com.bclnet.jsonui.JsonActionHandler
import com.bclnet.jsonui.compose.JsonUIModel
import com.bclnet.qrx.core.GlyphContent
import com.bclnet.qrx.shared.GlyphSession
import com.bclnet.qrx.shared.blue.BluePermissions
import com.bclnet.qrx.shared.blue.BluetoothService
import com.bclnet.qrx.shared.scan.GlyphScanner
import com.bclnet.qrx.shared.scan.ScanFrame
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.composePanel
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKStartTrackerResult
import com.meta.spatial.mruk.MarkerPayloadType
import com.meta.spatial.mruk.TrackedQrCode
import com.meta.spatial.mruk.Tracker
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class QuestActivity : AppSystemActivity() {
    companion object {
        private const val PERMISSION_REQUEST = 7
        /** How many glyph panels can be shown at once. */
        const val SLOT_COUNT = 8
        /** Gap between a code's surface and its panel so they do not z-fight. */
        private const val SURFACE_GAP = 0.02f
        /** A code whose normal is this close to vertical lies on a floor or table. */
        private const val HORIZONTAL_DOT = 0.5f
        private const val TAG = "QRX"
    }

    /** Panel registration ids for the glyph slots. */
    private val slotIds = listOf(R.id.panel_glyph_0, R.id.panel_glyph_1, R.id.panel_glyph_2, R.id.panel_glyph_3, R.id.panel_glyph_4, R.id.panel_glyph_5, R.id.panel_glyph_6, R.id.panel_glyph_7)

    lateinit var bluetooth: BluetoothService
    lateinit var session: GlyphSession
    private lateinit var scanner: GlyphScanner
    private lateinit var lifecycleOwner: ActivityLifecycleOwner
    private lateinit var mruk: MRUKFeature

    /** Glyph payload shown by each slot. */
    val slots = mutableStateMapOf<Int, String>()
    private val slotEntities = HashMap<Int, Entity>()
    /** JsonScene scenes standing on their codes, by slot, with the JsonUI model that owns their state. */
    private val scenes = HashMap<Int, Pair<JsonUIModel, SpatialSceneRenderer>>()
    /** What a scene's actors last said, by slot (shown on the slot panel). */
    val speech = mutableStateMapOf<Int, String>()
    /** Payloads MRUK is tracking, with the code's world pose; these are not placed from the camera scan. */
    private val tracked = HashMap<String, Pose>()
    private val trackedQuery = Query.query(Query.has(TrackedQrCode.id)).build()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (sceneReady) {
                val head = headPose()
                placeTrackedCodes(head)
                for ((_, scene) in scenes.values) scene.frame(frameTimeNanos, head)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var controlPanel: Entity? = null
    var cameraState: String by mutableStateOf("camera off")
        private set
    var trackerState: String by mutableStateOf("QR tracker off")
        private set
    private var sceneReady = false
    private var permissionsGranted = false
    private var trackerAllowed = false
    private var trackerStarted = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun registerFeatures(): List<SpatialFeature> {
        mruk = MRUKFeature(this, systemManager)
        return listOf(VRFeature(this), ComposeFeature(), mruk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleOwner = ActivityLifecycleOwner(this)
        bluetooth = BluetoothService(this)
        session = GlyphSession(this, bluetooth)
        scanner = GlyphScanner(this).apply { cameraSelector = QuestCamera.selector }
        ActivityCompat.requestPermissions(this, BluePermissions.required(quest = true).toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        val granted = permissions.zip(grantResults.toList()).filter { it.second == PackageManager.PERMISSION_GRANTED }.map { it.first }
        permissionsGranted = BluePermissions.CAMERA in granted
        trackerAllowed = BluePermissions.USE_SCENE in granted
        if (BluePermissions.bluetooth.all { it in granted }) bluetooth.start()
        if (permissionsGranted) startCamera() else session.title = "Camera permission is needed to find glyphs."
        if (trackerAllowed) startTracker() else trackerState = "spatial data permission needed to place glyphs on codes"
    }

    /** Starts MRUK's QR code tracker once the permission is granted and the scene is up. */
    private fun startTracker() {
        if (trackerStarted || !trackerAllowed || !sceneReady) return
        trackerStarted = true
        trackerState = "starting QR tracker"
        mruk.configureTrackers(setOf(Tracker.QrCode)).whenComplete { result, error ->
            main.post {
                trackerState = when {
                    error != null -> "QR tracker failed: ${error.message}"
                    result == MRUKStartTrackerResult.SUCCESS -> "tracking QR codes"
                    else -> "QR tracker failed: $result"
                }
                Log.d(TAG, trackerState)
            }
        }
    }

    private fun startCamera() {
        cameraState = "starting camera"
        scanner.start(lifecycleOwner) { cameraState = "camera failed: ${it.message}"; session.title = cameraState }
        cameraState = "scanning passthrough camera"
        scope.launch { scanner.frames.collectLatest { frame -> onFrame(frame) } }
    }

    override fun onStart() { super.onStart(); lifecycleOwner.onStart() }
    override fun onResume() { super.onResume(); lifecycleOwner.onResume(); Choreographer.getInstance().postFrameCallback(frameCallback) }
    override fun onPause() { Choreographer.getInstance().removeFrameCallback(frameCallback); lifecycleOwner.onPause(); super.onPause() }
    override fun onStop() { lifecycleOwner.onStop(); super.onStop() }
    override fun onSpatialShutdown() {
        if (trackerStarted) runCatching { mruk.stopTrackers() }
        super.onSpatialShutdown()
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        for ((model, renderer) in scenes.values) { renderer.detach(); model.close() }
        scenes.clear()
        scope.cancel()
        scanner.stop()
        bluetooth.stop()
        lifecycleOwner.onDestroy()
        super.onDestroy()
    }

    // MARK: - Scene

    override fun onSceneReady() {
        super.onSceneReady()
        scene.enablePassthrough(true)
        sceneReady = true
        controlPanel = Entity.createPanelEntity(
            R.id.panel_control,
            Transform(Pose(Vector3(0f, 1.2f, -1.2f), Quaternion(0f, 0f, 0f))),
            Grabbable(),
        )
        startTracker()
    }

    override fun registerPanels(): List<PanelRegistration> {
        val control = PanelRegistration(R.id.panel_control) { _ ->
            config {
                width = 0.6f
                height = 0.7f
                layoutWidthInDp = 600f
                layoutHeightInDp = 700f
            }
            composePanel { setContent { QuestTheme { ControlPanel(this@QuestActivity) } } }
        }
        val glyphs = slotIds.mapIndexed { slot, id ->
            PanelRegistration(id) { _ ->
                config {
                    width = 0.4f
                    height = 0.4f
                    layoutWidthInDp = 480f
                    layoutHeightInDp = 480f
                }
                composePanel { setContent { QuestTheme { GlyphSlotPanel(this@QuestActivity, slot) } } }
            }
        }
        return listOf(control) + glyphs
    }

    // MARK: - Placement

    /** Every frame: the codes MRUK tracks, placed from their tracked world pose. */
    private fun placeTrackedCodes(head: Pose) {
        if (!trackerStarted) return
        for (entity in Query.eval(trackedQuery)) {
            val code = runCatching { entity.getComponent<TrackedQrCode>() }.getOrNull() ?: continue
            val payload = payloadText(code) ?: continue
            val codePose = runCatching { entity.getComponent<Transform>().transform }.getOrNull() ?: continue
            if (payload !in tracked) Log.d(TAG, "tracked code ${payload.take(60)} at ${codePose.t} q=${codePose.q} head=${head.t}")
            tracked[payload] = codePose
            session.seen(payload)
            place(payload, panelPose(codePose, head), stagePose(codePose, head))
        }
    }

    /** The code's text. MRUK hands the payload over base64-encoded; an invalid or undecodable one is skipped. */
    private fun payloadText(code: TrackedQrCode): String? {
        if (code.payloadType == MarkerPayloadType.InvalidQrCode || code.payload.isEmpty()) return null
        return runCatching { String(Base64.decode(code.payload, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Fallback for codes the tracker cannot read: the passthrough camera scan and the pinhole estimate. */
    private fun onFrame(frame: ScanFrame) {
        if (!sceneReady) return
        val intrinsics = Intrinsics.fromFov(frame.imageWidth, frame.imageHeight, QuestCamera.PASSTHROUGH_HORIZONTAL_FOV_DEGREES)
        val head = headPose()
        for (detection in frame.detections) {
            session.seen(detection.payload)
            if (detection.payload in tracked) continue
            val code = QrPoseEstimator.estimate(detection.corners, intrinsics) ?: continue
            val pose = worldPose(code, head)
            if (detection.payload !in slots.values) Log.d(TAG, "camera-estimated code ${detection.payload.take(60)} at ${pose.t} (${"%.2f".format(code.distance)} m, ${code.sidePixels.toInt()} px) head=${head.t}")
            place(detection.payload, pose, pose)
        }
    }

    /** Shows `payload` in its slot at `pose` (unless the user is holding the panel) and its scene, if any, at `stage`. */
    private fun place(payload: String, pose: Pose, stage: Pose) {
        val slot = slots.entries.firstOrNull { it.value == payload }?.key ?: freeSlot() ?: return
        val existing = slotEntities[slot]
        if (existing == null) {
            slots[slot] = payload
            slotEntities[slot] = Entity.createPanelEntity(slotIds[slot], Transform(pose), Grabbable())
        } else if (!isGrabbed(existing)) {
            existing.setComponent(Transform(pose))
        }
        placeScene(slot, payload, stage)
    }

    /**
     * Where a tracked code's panel goes. The tracked pose's -z is the code's
     * outward normal (as in Meta's MRUK QR sample). On a wall the panel sits
     * on the code, just off the surface, facing out; on a floor or table it
     * stands upright above the code, turned towards the user.
     */
    private fun panelPose(code: Pose, head: Pose): Pose {
        val normal = code.q.times(Vector3(0f, 0f, -1f)).normalize()
        val horizontal = kotlin.math.abs(normal.dot(Vector3(0f, 1f, 0f))) > HORIZONTAL_DOT
        return if (horizontal) {
            val lift = 0.2f + SURFACE_GAP // half the 0.4 m panel, so its bottom edge rests on the code
            val position = code.t.plus(normal.times(lift))
            Pose(position, facingYaw(position, head))
        } else {
            val position = code.t.plus(normal.times(SURFACE_GAP))
            Pose(position, Quaternion(0f, yawDegrees(normal.x, normal.z), 0f))
        }
    }

    /** Where a scene stands: on the code, upright, turned towards the user. */
    private fun stagePose(code: Pose, head: Pose): Pose = Pose(code.t, facingYaw(code.t, head))

    private fun facingYaw(position: Vector3, head: Pose): Quaternion =
        Quaternion(0f, yawDegrees(head.t.x - position.x, head.t.z - position.z), 0f)

    /** Yaw around the up axis (degrees) that turns a panel's front (+z) along (dx, dz). */
    private fun yawDegrees(dx: Float, dz: Float): Float = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()

    /**
     * A `_ui` glyph whose root is a JsonScene `Scene` gets its actors standing on the code: the scene
     * origin is the code's pose (the panel stays beside it for status and speech).
     */
    private fun placeScene(slot: Int, payload: String, codePose: Pose) {
        val existing = scenes[slot]
        if (existing != null) {
            existing.second.stagePose = codePose
            return
        }
        val content = session.glyph(payload)?.document?.content as? GlyphContent.Ui ?: return
        if (content.document.root.type != SceneDocument.NODE_TYPE) return
        val model = JsonUIModel(content.document)
        model.runtime.actions.fallback = JsonActionHandler { name, args, context -> session.actions.invoke(name, args, context) }
        val renderer = SpatialSceneRenderer(this, content.document.root, model.runtime.context, mindProvider = session.ai.provider)
        renderer.stagePose = codePose
        session.heardHandlers += { text -> renderer.driver.heard(text) }
        renderer.onSay = { id, text -> speech[slot] = "${renderer.document.actor(id)?.name ?: id}: $text" }
        renderer.driver.onIssue = { session.showToast(it) }
        scenes[slot] = model to renderer
        renderer.attach()
    }

    private fun freeSlot(): Int? = (0 until SLOT_COUNT).firstOrNull { it !in slots }

    /** Removes every glyph panel; the session's "forget" action calls this. */
    fun clearSlots() {
        slotEntities.values.forEach { runCatching { it.destroy() } }
        slotEntities.clear()
        slots.clear()
        for ((model, renderer) in scenes.values) { renderer.detach(); model.close() }
        scenes.clear()
        tracked.clear()
        session.heardHandlers.clear()
        speech.clear()
        session.forgetGlyphs()
    }

    private fun isGrabbed(entity: Entity): Boolean = runCatching { entity.getComponent<Grabbable>().isGrabbed }.getOrDefault(false)

    /** The HMD pose in world space, or a standing pose at the origin when the runtime has none yet. */
    private fun headPose(): Pose =
        runCatching { scene.getViewerPose() }.getOrNull() ?: Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))

    /**
     * Converts a camera-space code position (x right, y up, z forward) into a
     * world pose in front of the head: the passthrough camera is close to
     * the head origin, looking along the head's forward axis (-z in OpenXR).
     */
    private fun worldPose(code: CodePose, head: Pose): Pose {
        val local = Vector3(code.x.toFloat(), code.y.toFloat(), -code.z.toFloat())
        val rotated = head.q.times(local)
        val position = Vector3(head.t.x + rotated.x, head.t.y + rotated.y, head.t.z + rotated.z)
        return Pose(position, facingYaw(position, head))
    }
}
