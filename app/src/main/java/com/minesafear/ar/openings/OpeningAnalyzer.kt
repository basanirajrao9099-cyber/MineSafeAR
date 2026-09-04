package com.minesafear.ar.openings

import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Snapshot handed to the renderer. Projection happens at draw time, so signs follow head motion. */
data class OpeningFrame(
    val tracks: List<OpeningTrack>,
    val intrinsics: Intrinsics,
    val rotationDegrees: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val detectMillis: Long
)

/**
 * CameraX analyser: Y-plane -> gravity-rectified quad detection -> metric pose -> world track.
 * Runs on CameraX's analysis executor; keep STRATEGY_KEEP_ONLY_LATEST so a slow frame drops
 * instead of queueing, and cap the rate — 10 Hz is enough for structure that does not move.
 */
class OpeningAnalyzer(
    private val gravity: GravityProvider,
    private val orientation: OrientationSource,
    /** Supply real lens intrinsics from CameraFov.kt; the fallback assumes a 67 deg horizontal FOV. */
    private val intrinsicsFor: (w: Int, h: Int) -> Intrinsics = { w, h ->
        Intrinsics.fromHFov(Math.toRadians(67.0).toFloat(), w, h)
    },
    private val minIntervalMs: Long = 90L
) : ImageAnalysis.Analyzer {

    private val detector = OpeningDetector()
    private val solver = OpeningPoseSolver()
    private val tracker = OpeningTracker()

    private val _frames = MutableStateFlow<OpeningFrame?>(null)
    val frames: StateFlow<OpeningFrame?> = _frames

    /** Set once if the user's holding height is known; scale accuracy is linear in this value. */
    var cameraHeightM: Float
        get() = solver.camHeightM
        set(v) { solver.camHeightM = v }

    private var lastRunMs = 0L
    private var yBytes = ByteArray(0)
    private val yFull = Mat()
    private val rotated = Mat()

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastRunMs < minIntervalMs) { image.close(); return }
        lastRunMs = now

        val rot = image.imageInfo.rotationDegrees
        val rWorldFromCam = orientation.rWorldFromCam(rot)
        if (!gravity.hasData || rWorldFromCam == null) { image.close(); return }

        try {
            val gray = grayFrom(image) ?: return
            // Rotate to display orientation so "vertical" and the intrinsics agree with the preview.
            val disp = rotateToDisplay(gray, rot)
            val intr = intrinsicsFor(image.width, image.height).rotated(rot)
            val g = gravity.gravityInCamera(rot)

            val t0 = System.nanoTime()
            val candidates = detector.detect(disp, intr, g)
            val poses = candidates.mapNotNull { solver.solve(it, intr, g) }
            val tracks = tracker.update(poses, rWorldFromCam, System.nanoTime())
            val ms = (System.nanoTime() - t0) / 1_000_000L

            _frames.value = OpeningFrame(tracks, intr, rot, disp.width(), disp.height(), ms)
        } finally {
            image.close()
        }
    }

    private fun grayFrom(image: ImageProxy): Mat? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buf = plane.buffer
        val need = buf.remaining()
        if (yBytes.size < need) yBytes = ByteArray(need)
        buf.get(yBytes, 0, need)
        val stride = plane.rowStride
        val rows = need / stride
        if (rows < image.height) return null
        if (yFull.rows() != rows || yFull.cols() != stride) yFull.create(rows, stride, CvType.CV_8UC1)
        yFull.put(0, 0, yBytes, 0, rows * stride)
        // Row stride can exceed width; crop rather than reinterpret, or the image shears.
        return yFull.submat(0, image.height, 0, image.width)
    }

    private fun rotateToDisplay(src: Mat, rot: Int): Mat = when (((rot % 360) + 360) % 360) {
        90 -> { Core.rotate(src, rotated, Core.ROTATE_90_CLOCKWISE); rotated }
        180 -> { Core.rotate(src, rotated, Core.ROTATE_180); rotated }
        270 -> { Core.rotate(src, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); rotated }
        else -> src
    }

    fun start(sm: SensorManager) {
        gravity.register(sm)
        (orientation as? GyroOrientationSource)?.register(sm)
    }

    fun stop(sm: SensorManager) {
        gravity.unregister(sm)
        (orientation as? GyroOrientationSource)?.unregister(sm)
    }

    fun release() {
        detector.release(); solver.close(); tracker.clear()
        yFull.release(); rotated.release()
    }
}
