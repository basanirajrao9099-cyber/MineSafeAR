package com.minesafear.ar.openings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM geometry tests: no OpenCV natives, no Android framework, so these run in
 * :app:testDebugUnitTest. Ground truth is built forwards (world point -> pixel) and the
 * production code is asked to invert it.
 */
class OpeningGeometryTest {

    private val w = 720
    private val h = 1280
    private val intr = Intrinsics(900f, 900f, w / 2f, h / 2f, w, h)
    private val camHeight = 1.50f

    // --- rotation bookkeeping -------------------------------------------------------------------

    @Test fun `rotated intrinsics swap axes and dimensions`() {
        val k = intr.rotated(90)
        assertEquals(h, k.w); assertEquals(w, k.h)
        assertEquals(intr.fy, k.fx, 1e-6f); assertEquals(intr.fx, k.fy, 1e-6f)
    }

    @Test fun `ray in rotated frame equals rotated ray`() {
        // Core.rotate 90 CW maps (x,y) -> (H-1-y, x); a direction's x,y must permute the same way.
        val px = 137f; val py = 902f
        val r0 = intr.ray(px, py)
        val r1 = intr.rotated(90).ray((h - 1) - py, px)
        assertEquals(-r0[1], r1[0], 1e-6f)
        assertEquals(r0[0], r1[1], 1e-6f)
        assertEquals(r0[2], r1[2], 1e-6f)
    }

    @Test fun `project inverts ray`() {
        val r = intr.ray(211f, 655f)
        val p = intr.project(floatArrayOf(r[0] * 4f, r[1] * 4f, r[2] * 4f))!!
        assertEquals(211f, p[0], 1e-3f); assertEquals(655f, p[1], 1e-3f)
    }

    @Test fun `project rejects points behind the camera`() {
        assertNull(intr.project(floatArrayOf(0.1f, 0.1f, -2f)))
    }

    // --- gravity sign ---------------------------------------------------------------------------

    @Test fun `upright phone yields a down vector in camera coords`() {
        // Portrait, back camera horizontal: TYPE_GRAVITY reads (0, +9.81, 0) in device coords.
        val g = gravityCamFromSensor(0f, 9.81f, 0f, 0)
        // OpenCV camera Y points down, so the floor direction must be +Y.
        assertEquals(0f, g[0], 1e-5f); assertEquals(1f, g[1], 1e-5f); assertEquals(0f, g[2], 1e-5f)
    }

    @Test fun `phone flat on a table points gravity along the optical axis`() {
        // Face up on a table: sensor reads (0,0,+9.81); the back camera stares at the floor.
        val g = gravityCamFromSensor(0f, 0f, 9.81f, 0)
        assertEquals(1f, g[2], 1e-5f)   // straight down the camera's forward axis
    }

    @Test fun `gravity is unit length for arbitrary readings`() {
        val g = gravityCamFromSensor(1.4f, -7.7f, 5.9f, 270)
        assertEquals(1f, sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]), 1e-5f)
    }

    // --- floor ranging --------------------------------------------------------------------------

    /** Forward model: camera pitched nose-down by `pitch`, no roll/yaw. World axes at the camera are
     *  X right, Y up, Z forward; camera down-axis in world is (0, -cos p, -sin p). */
    private fun projectWorld(pitch: Float, lateral: Float, height: Float, forward: Float): FloatArray {
        val dy = height - camHeight
        val c = cos(pitch); val s = sin(pitch)
        val yc = -(dy * c + forward * s)     // camera Y is down
        val zc = -dy * s + forward * c
        return intr.project(floatArrayOf(lateral, yc, zc))!!
    }

    /** World down-vector in camera coords for a camera pitched nose-down by `pitch`. */
    private fun gravityFor(pitch: Float) = floatArrayOf(0f, cos(pitch), sin(pitch))

    @Test fun `range to floor recovers the true distance`() {
        val pitch = 0.22f                       // ~12.6 deg down
        val forward = 4.0f
        val px = projectWorld(pitch, 0f, 0f, forward)
        val d = GroundPlane.rangeToFloor(intr, gravityFor(pitch), px[0], px[1], camHeight)
        assertNotNull(d)
        val truth = sqrt(forward * forward + camHeight * camHeight)
        assertEquals(truth, d!!, 2e-3f)
    }

    @Test fun `range to floor refuses rays at or above the horizon`() {
        val pitch = 0.22f
        val g = gravityFor(pitch)
        val yHorizon = GroundPlane.horizonY(intr, g, intr.cx)!!
        assertNull(GroundPlane.rangeToFloor(intr, g, intr.cx, yHorizon - 20f, camHeight))
    }

    @Test fun `horizon is the locus where the ray is perpendicular to gravity`() {
        val g = gravityFor(0.17f)
        for (x in listOf(0f, 180f, 360f, 719f)) {
            val y = GroundPlane.horizonY(intr, g, x)!!
            val r = intr.ray(x, y)
            assertEquals(0f, r[0] * g[0] + r[1] * g[1] + r[2] * g[2], 1e-6f)
        }
    }

    @Test fun `image up is opposite projected gravity`() {
        val up = GroundPlane.imageUp(floatArrayOf(0.3f, 0.9f, 0.2f))
        assertTrue(up[1] < 0f)                                   // screen-up is -y in pixel coords
        assertEquals(1f, sqrt(up[0] * up[0] + up[1] * up[1]), 1e-5f)
    }

    @Test fun `roll is zero when the phone is upright and grows with tilt`() {
        assertEquals(0f, GroundPlane.rollRad(floatArrayOf(0f, 1f, 0f)), 1e-6f)
        assertTrue(abs(GroundPlane.rollRad(gravityCamFromSensor(8.49f, 4.90f, 0f, 0))) > 0.9f)
    }

    // --- Filament handoff -----------------------------------------------------------------------

    @Test fun `filament transform is column major and preserves handedness`() {
        val r = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f)   // 90 deg about Y
        val pose = OpeningPose(OpeningKind.DOOR, r, floatArrayOf(0.4f, -0.2f, 3.5f),
            0.9f, 2.05f, ScaleSource.FLOOR_CONTACT, 1.2f, 0.8f)
        val m = pose.toFilamentTransform()
        // Translation lives in m[12..14] for column-major, with Y and Z flipped for GL.
        assertEquals(0.4f, m[12], 1e-6f); assertEquals(0.2f, m[13], 1e-6f); assertEquals(-3.5f, m[14], 1e-6f)
        assertEquals(1f, m[15], 1e-6f)
        assertEquals(1f, det3(m), 1e-5f)                            // no mirror flip
    }

    @Test fun `distance matches the translation norm`() {
        val pose = OpeningPose(OpeningKind.WINDOW, FloatArray(9), floatArrayOf(3f, 4f, 12f),
            1.2f, 1.2f, ScaleSource.SIZE_PRIOR, 0f, 0.5f)
        assertEquals(13f, pose.distanceM, 1e-5f)
    }

    private fun det3(m: FloatArray): Float {
        fun a(r: Int, c: Int) = m[c * 4 + r]
        return a(0, 0) * (a(1, 1) * a(2, 2) - a(1, 2) * a(2, 1)) -
               a(0, 1) * (a(1, 0) * a(2, 2) - a(1, 2) * a(2, 0)) +
               a(0, 2) * (a(1, 0) * a(2, 1) - a(1, 1) * a(2, 0))
    }
}
