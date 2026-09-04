package com.minesafear.ar.fallback

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.minesafear.ar.TrackedPose
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/**
 * 6-DoF pose from a printed ArUco marker. No ARCore, no device certification, no licence key.
 * ArUco is in the main objdetect module since OpenCV 4.7, so the plain AAR suffices.
 */
class MarkerTracker(
    private val markerSizeMeters: Float,   // measured edge of the printed black square
    private val horizontalFovRad: Float,   // from CameraFov.kt (Camera2 lens characteristics)
    private val onPose: (TrackedPose?) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        val openCvLoaded: Boolean by lazy { OpenCVLoader.initLocal() }
    }

    private val detectors = listOf(
        ArucoDetector(
            Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50),
            DetectorParameters().apply {
                _adaptiveThreshWinSizeMin = 3
                _adaptiveThreshWinSizeMax = 23
                _adaptiveThreshWinSizeStep = 10
                _minMarkerPerimeterRate = 0.015
                _perspectiveRemovePixelPerCell = 8
                _maxErroneousBitsInBorderRate = 0.35
            }
        ),
        ArucoDetector(
            Objdetect.getPredefinedDictionary(Objdetect.DICT_6X6_250),
            DetectorParameters().apply {
                _adaptiveThreshWinSizeMin = 3
                _adaptiveThreshWinSizeMax = 23
                _adaptiveThreshWinSizeStep = 10
                _minMarkerPerimeterRate = 0.015
            }
        ),
        ArucoDetector(
            Objdetect.getPredefinedDictionary(Objdetect.DICT_ARUCO_ORIGINAL),
            DetectorParameters().apply {
                _adaptiveThreshWinSizeMin = 3
                _adaptiveThreshWinSizeMax = 23
                _adaptiveThreshWinSizeStep = 10
                _minMarkerPerimeterRate = 0.015
            }
        )
    )

    // Corner order must match detectMarkers output: TL, TR, BR, BL on the Z=0 plane.
    private val objectPoints = MatOfPoint3f(
        Point3(-markerSizeMeters / 2.0, markerSizeMeters / 2.0, 0.0),
        Point3(markerSizeMeters / 2.0, markerSizeMeters / 2.0, 0.0),
        Point3(markerSizeMeters / 2.0, -markerSizeMeters / 2.0, 0.0),
        Point3(-markerSizeMeters / 2.0, -markerSizeMeters / 2.0, 0.0)
    )
    private val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0) // CameraX preview ~rectilinear
    private var cameraMatrix: Mat? = null
    private val gray = Mat()
    private val rvec = Mat()
    private val tvec = Mat()

    override fun analyze(image: ImageProxy) {
        try {
            if (!openCvLoaded) { onPose(null); return }
            yPlaneToGray(image, gray)
            val k = cameraMatrix ?: intrinsics(gray.cols(), gray.rows()).also { cameraMatrix = it }

            var bestCornerMat: Mat? = null
            for (detector in detectors) {
                val corners = ArrayList<Mat>()
                val ids = Mat()
                detector.detectMarkers(gray, corners, ids)
                if (!ids.empty() && corners.isNotEmpty()) {
                    bestCornerMat = corners[0]
                    break
                }
            }

            if (bestCornerMat == null) { onPose(null); return }

            // IPPE_SQUARE: closed-form solver specific to planar squares; no iteration, low jitter.
            val imgPts = MatOfPoint2f().also { bestCornerMat.convertTo(it, CvType.CV_32F) }
            if (!Calib3d.solvePnP(objectPoints, imgPts, k, distCoeffs, rvec, tvec,
                    false, Calib3d.SOLVEPNP_IPPE_SQUARE)) { onPose(null); return }

            onPose(TrackedPose(glTranslation(tvec), glQuaternion(rvec)))
        } catch (t: Throwable) {
            onPose(null)
        } finally { image.close() }
    }

    /** fx = half-width / tan(half-FOV); square pixels so fy = fx. */
    private fun intrinsics(w: Int, h: Int): Mat = Mat.zeros(3, 3, CvType.CV_64F).apply {
        val fx = (w / 2.0) / Math.tan(horizontalFovRad / 2.0)
        put(0, 0, fx); put(0, 2, w / 2.0)
        put(1, 1, fx); put(1, 2, h / 2.0)
        put(2, 2, 1.0)
    }

    /** OpenCV camera basis (X right, Y down, Z fwd) -> Filament/GL (X right, Y up, Z back). */
    private fun glTranslation(t: Mat) = floatArrayOf(
        t[0, 0][0].toFloat(), -t[1, 0][0].toFloat(), -t[2, 0][0].toFloat()
    )

    /** Rodrigues -> R, basis change D*R*D with D = diag(1,-1,-1), then Shepperd branch. */
    private fun glQuaternion(rv: Mat): FloatArray {
        val r = Mat(); Calib3d.Rodrigues(rv, r)
        val d = doubleArrayOf(1.0, -1.0, -1.0)
        val m = Array(3) { i -> DoubleArray(3) { j -> d[i] * r[i, j][0] * d[j] } }
        val tr = m[0][0] + m[1][1] + m[2][2]
        val q = DoubleArray(4) // x, y, z, w
        if (tr > 0) {
            val s = Math.sqrt(tr + 1.0) * 2
            q[3] = 0.25 * s
            q[0] = (m[2][1] - m[1][2]) / s
            q[1] = (m[0][2] - m[2][0]) / s
            q[2] = (m[1][0] - m[0][1]) / s
        } else { // largest diagonal keeps the divisor away from zero
            val i = if (m[0][0] > m[1][1]) (if (m[0][0] > m[2][2]) 0 else 2) else (if (m[1][1] > m[2][2]) 1 else 2)
            val j = (i + 1) % 3
            val kk = (i + 2) % 3
            val s = Math.sqrt(m[i][i] - m[j][j] - m[kk][kk] + 1.0) * 2
            q[i] = 0.25 * s
            q[j] = (m[j][i] + m[i][j]) / s
            q[kk] = (m[kk][i] + m[i][kk]) / s
            q[3] = (m[kk][j] - m[j][kk]) / s
        }
        return floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
    }

    /** Y plane of YUV_420_888 is luminance already; stride-aware copy, no colour conversion. */
    private fun yPlaneToGray(image: ImageProxy, out: Mat) {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val bytes = ByteArray(w * h)
        if (rowStride == w) {
            buf.rewind(); buf.get(bytes)
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.get(row, 0, minOf(rowStride, buf.remaining()))
                System.arraycopy(row, 0, bytes, y * w, w)
            }
        }
        if (out.rows() != h || out.cols() != w) out.create(h, w, CvType.CV_8UC1)
        out.put(0, 0, bytes)
    }
}
