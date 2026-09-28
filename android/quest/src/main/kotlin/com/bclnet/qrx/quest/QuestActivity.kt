/*
 * QuestActivity.kt
 * QRX (Meta Quest)
 *
 * The Quest app: a Meta Spatial SDK scene with passthrough on, the QR codes
 * seen by the passthrough camera turned into glyph panels placed in the
 * room where the codes are, and a control panel with the status, settings
 * and Bluetooth (BLUE/1.0 server, nearby devices, Particle LED board).
 */
package com.bclnet.qrx.quest

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
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
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
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
    }

    /** Panel registration ids for the glyph slots. */
    private val slotIds = listOf(R.id.panel_glyph_0, R.id.panel_glyph_1, R.id.panel_glyph_2, R.id.panel_glyph_3, R.id.panel_glyph_4, R.id.panel_glyph_5, R.id.panel_glyph_6, R.id.panel_glyph_7)

    lateinit var bluetooth: BluetoothService
    lateinit var session: GlyphSession
    private lateinit var scanner: GlyphScanner
    private lateinit var lifecycleOwner: ActivityLifecycleOwner

    /** Glyph payload shown by each slot. */
    val slots = mutableStateMapOf<Int, String>()
    private val slotEntities = HashMap<Int, Entity>()
    private var controlPanel: Entity? = null
    var cameraState: String by mutableStateOf("camera off")
        private set
    private var sceneReady = false
    private var permissionsGranted = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

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
        if (BluePermissions.bluetooth.all { it in granted }) bluetooth.start()
        if (permissionsGranted) startCamera() else session.title = "Camera permission is needed to find glyphs."
    }

    private fun startCamera() {
        cameraState = "starting camera"
        scanner.start(lifecycleOwner) { cameraState = "camera failed: ${it.message}"; session.title = cameraState }
        cameraState = "scanning passthrough camera"
        scope.launch { scanner.frames.collectLatest { frame -> onFrame(frame) } }
    }

    override fun onStart() { super.onStart(); lifecycleOwner.onStart() }
    override fun onResume() { super.onResume(); lifecycleOwner.onResume() }
    override fun onPause() { lifecycleOwner.onPause(); super.onPause() }
    override fun onStop() { lifecycleOwner.onStop(); super.onStop() }
    override fun onDestroy() {
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

    private fun onFrame(frame: ScanFrame) {
        if (!sceneReady) return
        val intrinsics = Intrinsics.fromFov(frame.imageWidth, frame.imageHeight, QuestCamera.PASSTHROUGH_HORIZONTAL_FOV_DEGREES)
        val head = headPose()
        for (detection in frame.detections) {
            session.seen(detection.payload)
            val slot = slots.entries.firstOrNull { it.value == detection.payload }?.key ?: freeSlot() ?: continue
            val code = QrPoseEstimator.estimate(detection.corners, intrinsics) ?: continue
            val pose = worldPose(code, head)
            val existing = slotEntities[slot]
            if (existing == null) {
                slots[slot] = detection.payload
                slotEntities[slot] = Entity.createPanelEntity(slotIds[slot], Transform(pose), Grabbable())
            } else if (!isGrabbed(existing)) {
                existing.setComponent(Transform(pose))
            }
        }
    }

    private fun freeSlot(): Int? = (0 until SLOT_COUNT).firstOrNull { it !in slots }

    /** Removes every glyph panel; the session's "forget" action calls this. */
    fun clearSlots() {
        slotEntities.values.forEach { runCatching { it.destroy() } }
        slotEntities.clear()
        slots.clear()
        session.forgetGlyphs()
    }

    private fun isGrabbed(entity: Entity): Boolean = runCatching { entity.getComponent<Grabbable>().isGrabbed }.getOrDefault(false)

    /** The HMD pose from the avatar attachment entities, or the view origin when unavailable. */
    private fun headPose(): Pose {
        val query = Query.query(Query.has(AvatarAttachment.id)).build()
        val head = runCatching {
            Query.eval(query).firstOrNull { it.getComponent<AvatarAttachment>().type == "head" }?.getComponent<Transform>()?.transform
        }.getOrNull()
        return head ?: Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))
    }

    /**
     * Converts a camera-space code position (x right, y up, z forward) into a
     * world pose in front of the head: the passthrough camera is close to
     * the head origin, looking along the head's forward axis (-z in OpenXR).
     */
    private fun worldPose(code: CodePose, head: Pose): Pose {
        val local = Vector3(code.x.toFloat(), code.y.toFloat(), -code.z.toFloat())
        val rotated = head.q.times(local)
        val position = Vector3(head.t.x + rotated.x, head.t.y + rotated.y, head.t.z + rotated.z)
        // Face the user: yaw the panel around the up axis towards the head.
        val dx = head.t.x - position.x
        val dz = head.t.z - position.z
        val yaw = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return Pose(position, Quaternion(0f, yaw, 0f))
    }
}
