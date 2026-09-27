/*
 * QuestCamera.kt
 * QRX (Meta Quest)
 *
 * Passthrough camera access on Quest 3: Horizon OS exposes the headset
 * cameras through Camera2 (permission horizonos.permission.HEADSET_CAMERA),
 * so CameraX can drive them. This file picks the left passthrough camera
 * and adapts a plain Activity to the LifecycleOwner CameraX needs.
 */
package com.bclnet.qrx.quest

import android.app.Activity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

object QuestCamera {
    /** Camera ids Horizon OS uses for the passthrough cameras (left, right). */
    val passthroughIds = listOf("50", "51")

    /** Horizontal field of view assumed for the passthrough camera when intrinsics are unavailable. */
    const val PASSTHROUGH_HORIZONTAL_FOV_DEGREES = 80.0

    /** Prefers the left passthrough camera, then any back camera, then whatever exists. */
    @OptIn(ExperimentalCamera2Interop::class)
    val selector: CameraSelector = CameraSelector.Builder().addCameraFilter { cameras ->
        val byId = cameras.associateBy { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() }
        passthroughIds.firstNotNullOfOrNull { byId[it] }?.let { return@addCameraFilter listOf(it) }
        val back = cameras.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        if (back.isNotEmpty()) back else cameras
    }.build()

    @OptIn(ExperimentalCamera2Interop::class)
    fun describe(info: CameraInfo): String = runCatching { "camera ${Camera2CameraInfo.from(info).cameraId}" }.getOrDefault("camera")
}

/** Drives a LifecycleRegistry from a plain Activity's callbacks (VrActivity is not a ComponentActivity). */
class ActivityLifecycleOwner(activity: Activity) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    init { registry.currentState = Lifecycle.State.CREATED }

    fun onStart() { registry.currentState = Lifecycle.State.STARTED }
    fun onResume() { registry.currentState = Lifecycle.State.RESUMED }
    fun onPause() { registry.currentState = Lifecycle.State.STARTED }
    fun onStop() { registry.currentState = Lifecycle.State.CREATED }
    fun onDestroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
