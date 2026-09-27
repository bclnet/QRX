package com.bclnet.qrx.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPoseEstimatorTest {
    private val intrinsics = Intrinsics.fromFov(1280, 720, 80.0)

    /** Projects the four corners of a code of `size` metres centred at (x, y, z). */
    private fun corners(x: Double, y: Double, z: Double, size: Double): List<Pair<Float, Float>> {
        val h = size / 2
        return listOf(-h to h, h to h, h to -h, -h to -h).map { (dx, dy) ->
            val (px, py) = QrPoseEstimator.project(x + dx, y + dy, z, intrinsics)
            px.toFloat() to py.toFloat()
        }
    }

    @Test
    fun intrinsicsFromFov() {
        assertEquals(640.0, intrinsics.cx, 1e-9)
        assertEquals(360.0, intrinsics.cy, 1e-9)
        assertEquals(640.0 / Math.tan(Math.toRadians(40.0)), intrinsics.fx, 1e-4)
    }

    @Test
    fun recoversPositionOfAProjectedCode() {
        val pose = QrPoseEstimator.estimate(corners(0.3, -0.1, 1.5, 0.06), intrinsics, 0.06)!!
        assertEquals(0.3, pose.x, 1e-4)
        assertEquals(-0.1, pose.y, 1e-4)
        assertEquals(1.5, pose.z, 1e-4)
        assertEquals(1.5, Math.sqrt(0.3 * 0.3 + 0.1 * 0.1 + 1.5 * 1.5).let { pose.distance / it * 1.5 }, 1e-4)
    }

    @Test
    fun largerCodesAreFurtherAway() {
        val near = QrPoseEstimator.estimate(corners(0.0, 0.0, 0.5, 0.06), intrinsics)!!
        val far = QrPoseEstimator.estimate(corners(0.0, 0.0, 2.0, 0.06), intrinsics)!!
        assertEquals(0.5, near.z, 1e-4)
        assertEquals(2.0, far.z, 1e-4)
        // A 12 cm print of the same code at 2 m looks like a 6 cm one at 1 m.
        assertEquals(1.0, QrPoseEstimator.estimate(corners(0.0, 0.0, 2.0, 0.12), intrinsics, 0.06)!!.z, 1e-4)
    }

    @Test
    fun degenerateInput() {
        assertNull(QrPoseEstimator.estimate(listOf(1f to 1f, 2f to 2f), intrinsics))
        assertNull(QrPoseEstimator.estimate(List(4) { 5f to 5f }, intrinsics))
    }

    @Test
    fun facingYaw() {
        assertEquals(0.0, QrPoseEstimator.facingYawDegrees(0.0, 2.0), 1e-9)
        assertEquals(45.0, QrPoseEstimator.facingYawDegrees(2.0, 2.0), 1e-9)
    }
}
