package com.minesafear.ar.openings

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class ScaleSource { FLOOR_CONTACT, SIZE_PRIOR }

/** Metric 6-DoF pose of an opening's plane, in the OpenCV camera frame of the analysed frame. */
data class OpeningPose(
    val kind: OpeningKind,
    val rCamFromOpening: FloatArray,  // 3x3 row-major, opening frame -> camera frame
    val tCam: FloatArray,             // metres, opening centre in camera frame
    val widthM: Float,
    val heightM: Float,
    val scaleSource: ScaleSource,
    val reprojErrPx: Float,
    val confidence: Float
) {
    val distanceM: Float get() = sqrt(tCam[0] * tCam[0] + tCam[1] * tCam[1] + tCam[2] * tCam[2])

    /**
     * 4x4 column-major matrix for Filament/SceneView. OpenCV camera (X right, Y down, Z fwd)
     * -> GL camera (X right, Y up, Z back) is a diag(1,-1,-1) similarity applied to R and t:
     *     R_gl = C * R * C,  t_gl = C * t   with C = diag(1,-1,-1)
     * The inner C also flips the opening's own Y/Z so its +Y stays "up" and +Z stays "out of wall".
     */
    fun toFilamentTransform(): FloatArray {
        val c = floatArrayOf(1f, -1f, -1f)
        val m = FloatArray(16)
        for (col in 0 until 3) for (row in 0 until 3) {
            m[col * 4 + row] = c[row] * rCamFromOpening[row * 3 + col] * c[col]
        }
        m[12] = c[0] * tCam[0]; m[13] = c[1] * tCam[1]; m[14] = c[2] * tCam[2]; m[15] = 1f
        return m
    }
}

/**
 * Recovers metric pose without depth hardware.
 *
 * Scale comes from the floor, not from a size guess, whenever the opening's lower edge rests on the
 * ground: the gravity vector plus a known camera height turns the bottom-edge pixel into a real
 * distance, and the vertical-object constraint then yields the true height. That height doubles as
 * the door/window discriminator — a "door-shaped" quad whose implied height is 1.1 m is a window.
 */
class OpeningPoseSolver(
    /** Height of the phone above the walking surface, metres. Calibrate once per user if possible. */
    var camHeightM: Float = 1.50f
) {
    private val distZero = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)

    fun solve(c: OpeningCandidate, intr: Intrinsics, g: FloatArray): OpeningPose? {
        val bottom = c.bottomCentre()
        val top = c.topCentre()
        val dBottom = GroundPlane.rangeToFloor(intr, g, bottom.x.toFloat(), bottom.y.toFloat(), camHeightM)

        var kind = c.kind
        var scaleSource = ScaleSource.SIZE_PRIOR
        var heightM: Float
        var widthM: Float
        val aspect = pixelAspect(c)

        val hEst = dBottom?.let { verticalExtent(intr, g, bottom, top, it) }
        val plausibleDoor = hEst != null && hEst in 1.70f..2.45f && c.bottomBelowHorizon &&
                (kind == OpeningKind.DOOR || kind == OpeningKind.DOORWAY)

        if (plausibleDoor) {
            heightM = hEst!!
            widthM = heightM / max(0.2f, aspect)
            scaleSource = ScaleSource.FLOOR_CONTACT
        } else {
            // Bottom edge is not on the floor (or geometry was ill-conditioned) -> wall-mounted.
            if (kind == OpeningKind.DOOR || kind == OpeningKind.DOORWAY) {
                if (hEst != null && hEst < 1.70f) kind = OpeningKind.WINDOW
            }
            val prior = SizePriors.of(kind)
            widthM = prior.widthM
            heightM = prior.widthM * aspect
            if (heightM !in 0.35f..3.0f) heightM = prior.heightM
        }
        if (widthM !in 0.35f..3.5f) widthM = SizePriors.of(kind).widthM

        // Opening frame: X right, Y up, Z out of the wall toward the viewer. Corner order matches
        // the candidate's TL, TR, BR, BL so the solved R has +Z facing the camera.
        val hw = widthM / 2.0; val hh = heightM / 2.0
        val obj = MatOfPoint3f(
            Point3(-hw, hh, 0.0), Point3(hw, hh, 0.0), Point3(hw, -hh, 0.0), Point3(-hw, -hh, 0.0)
        )
        val img = MatOfPoint2f(*c.corners)
        val camMat = Mat.zeros(3, 3, org.opencv.core.CvType.CV_64F).apply {
            put(0, 0, intr.fx.toDouble()); put(1, 1, intr.fy.toDouble())
            put(0, 2, intr.cx.toDouble()); put(1, 2, intr.cy.toDouble()); put(2, 2, 1.0)
        }
        val rvec = Mat(); val tvec = Mat()
        // IPPE is the planar-specific solver; it is stable on 4 coplanar points where ITERATIVE is not.
        // It throws on degenerate configurations rather than returning false, hence the guard.
        val ok = try {
            Calib3d.solvePnP(obj, img, camMat, distZero, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE)
        } catch (t: Throwable) { false }
        if (!ok) { release(obj, img, camMat, rvec, tvec); return null }

        val rMat = Mat(); Calib3d.Rodrigues(rvec, rMat)
        val r = FloatArray(9) { rMat.get(it / 3, it % 3)[0].toFloat() }
        val t = FloatArray(3) { tvec.get(it, 0)[0].toFloat() }
        val err = reprojError(obj, img, camMat, rvec, tvec)
        release(obj, img, camMat, rvec, tvec, rMat)

        if (t[2] <= 0.35f || t[2] > 45f) return null

        // A wall opening is near-vertical: its +Y must be close to world up (= -g).
        val upDot = -(r[1] * g[0] + r[4] * g[1] + r[7] * g[2])
        if (upDot < 0.80f) return null

        val conf = (c.score * (1f - (err / 9f).coerceIn(0f, 0.75f)) *
                (if (scaleSource == ScaleSource.FLOOR_CONTACT) 1f else 0.82f) *
                upDot).coerceIn(0f, 1f)

        return OpeningPose(kind, r, t, widthM, heightM, scaleSource, err, conf)
    }

    /**
     * Height of a vertical segment whose base sits at distance dBottom.
     * P_top = s*r_top and P_bot = dBottom*r_bot must satisfy P_top - P_bot = H * (-g).
     * Crossing both sides with g kills the H term, so s = dBottom * |r_bot x g| / |r_top x g|.
     */
    private fun verticalExtent(intr: Intrinsics, g: FloatArray, bottom: Point, top: Point, dBottom: Float): Float? {
        val rb = intr.ray(bottom.x.toFloat(), bottom.y.toFloat())
        val rt = intr.ray(top.x.toFloat(), top.y.toFloat())
        val cb = cross(rb, g); val ct = cross(rt, g)
        val nb = norm(cb); val nt = norm(ct)
        if (nt < 1e-4f || nb < 1e-4f) return null
        if (dot(cb, ct) < 0f) return null                 // top and bottom straddle the epipolar sense
        val s = dBottom * nb / nt
        if (s < 0.3f || s > 90f) return null
        val h = -((s * rt[0] - dBottom * rb[0]) * g[0] +
                  (s * rt[1] - dBottom * rb[1]) * g[1] +
                  (s * rt[2] - dBottom * rb[2]) * g[2])
        return if (h > 0.25f && h < 4.5f) h else null
    }

    private fun pixelAspect(c: OpeningCandidate): Float {
        val q = c.corners
        val left = dist(q[3], q[0]); val right = dist(q[2], q[1])
        val top = dist(q[1], q[0]); val bot = dist(q[2], q[3])
        val w = (top + bot) / 2f
        return if (w < 1e-3f) 1f else ((left + right) / 2f) / w
    }

    private fun reprojError(obj: MatOfPoint3f, img: MatOfPoint2f, camMat: Mat, rvec: Mat, tvec: Mat): Float {
        val proj = MatOfPoint2f()
        Calib3d.projectPoints(obj, rvec, tvec, camMat, distZero, proj)
        val a = proj.toArray(); val b = img.toArray()
        var acc = 0f
        for (i in a.indices) acc += dist(a[i], b[i])
        proj.release()
        return acc / a.size
    }

    private fun dist(a: Point, b: Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y).toFloat()
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray) = sqrt(dot(a, a))
    private fun release(vararg m: Mat) = m.forEach { it.release() }

    fun close() = distZero.release()
}

/** Sanity gate reused by the tracker: two poses describe the same physical opening. */
internal fun samePhysicalOpening(a: OpeningPose, b: OpeningPose, cosTol: Float = 0.9962f): Boolean {
    val na = a.distanceM.takeIf { it > 1e-3f } ?: return false
    val nb = b.distanceM.takeIf { it > 1e-3f } ?: return false
    val cos = (a.tCam[0] * b.tCam[0] + a.tCam[1] * b.tCam[1] + a.tCam[2] * b.tCam[2]) / (na * nb)
    if (cos < cosTol) return false                        // >5 deg apart in bearing
    return abs(na - nb) / max(na, nb) < 0.40f && abs(a.heightM - b.heightM) / max(a.heightM, b.heightM) < 0.45f
}
