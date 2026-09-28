/*
 * QrPoseEstimator.kt
 * QRX (Meta Quest)
 *
 * Estimates where a detected QR code is in camera space from its corner
 * points, the camera intrinsics and the code's physical size (pinhole
 * model). Pure Kotlin so it can be unit tested on the JVM.
 *
 * Camera space here is: x right, y up, z forward (metres), which is what the
 * scene placement converts into a Spatial SDK pose.
 */
package com.bclnet.qrx.quest

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    companion object {
        /** Intrinsics for a camera with the given horizontal field of view. */
        fun fromFov(width: Int, height: Int, horizontalFovDegrees: Double): Intrinsics {
            val fx = (width / 2.0) / tan(Math.toRadians(horizontalFovDegrees) / 2.0)
            return Intrinsics(fx, fx, width / 2.0, height / 2.0)
        }
    }
}

/** A code's position in camera space and its apparent size. */
data class CodePose(val x: Double, val y: Double, val z: Double, val sidePixels: Double) {
    val distance: Double get() = hypot(hypot(x, y), z)
}

object QrPoseEstimator {
    /** Nominal printed size of a QR code, metres. */
    const val DEFAULT_CODE_SIZE = 0.06

    /**
     * `corners` are the four corners in image pixels (any order). Returns null
     * when the corners do not describe a code (fewer than 3 points or a
     * degenerate size).
     */
    fun estimate(corners: List<Pair<Float, Float>>, intrinsics: Intrinsics, codeSizeMeters: Double = DEFAULT_CODE_SIZE): CodePose? {
        if (corners.size < 3) return null
        val centerX = corners.map { it.first }.average()
        val centerY = corners.map { it.second }.average()
        // Average edge length between consecutive corners (ML Kit gives them in order around the code).
        val edges = corners.indices.map { i ->
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())
        }
        val side = edges.average()
        if (side <= 1e-3) return null
        val z = intrinsics.fx * codeSizeMeters / side
        val x = (centerX - intrinsics.cx) * z / intrinsics.fx
        val yDown = (centerY - intrinsics.cy) * z / intrinsics.fy
        return CodePose(x, -yDown, z, side)
    }

    /** Projects a camera-space point back to pixels (used by tests and for debugging overlays). */
    fun project(x: Double, y: Double, z: Double, intrinsics: Intrinsics): Pair<Double, Double> =
        (intrinsics.cx + x * intrinsics.fx / z) to (intrinsics.cy - y * intrinsics.fy / z)

    /** Yaw (degrees, around the up axis) for a panel at `x, z` to face the camera origin. */
    fun facingYawDegrees(x: Double, z: Double): Double = Math.toDegrees(atan(x / z))
}
