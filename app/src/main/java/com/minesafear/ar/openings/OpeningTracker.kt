package com.minesafear.ar.openings

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.sqrt

/** Maps directions from the analysed frame's camera axes into a gravity-aligned Y-up world frame. */
interface OrientationSource {
    /** 3x3 row-major, camera -> world. Null until sensors have delivered a fix. */
    fun rWorldFromCam(rotationDegrees: Int): FloatArray?
}

internal object M3 {
    fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }
    fun mulVec(m: FloatArray, v: FloatArray) = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2])
    fun transpose(m: FloatArray) = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
    fun norm3(v: FloatArray): FloatArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1e-6f } ?: return v
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }
    /** Re-orthonormalise after repeated EMA blending, else the basis slowly shears. */
    fun orthonormalise(m: FloatArray): FloatArray {
        val x = norm3(floatArrayOf(m[0], m[3], m[6]))
        var y = floatArrayOf(m[1], m[4], m[7])
        val d = x[0] * y[0] + x[1] * y[1] + x[2] * y[2]
        y = norm3(floatArrayOf(y[0] - d * x[0], y[1] - d * x[1], y[2] - d * x[2]))
        val z = floatArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
        return floatArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
    }
}

/**
 * Standalone 3-DoF orientation from GAME_ROTATION_VECTOR (no magnetometer, so no compass jitter
 * near ore bodies or steel supports — yaw is drift-prone but locally consistent, which is all the
 * tracker needs). Swap for the project's existing GyroPose if it already exposes cam->world.
 */
class GyroOrientationSource : SensorEventListenerAdapter(), OrientationSource {
    private val rv = FloatArray(9)
    @Volatile private var ready = false

    fun register(sm: SensorManager) {
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun unregister(sm: SensorManager) = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // Same lock the reader takes: without it a caller can sample rv mid-write and
        // get three rows from two different orientations, which is not a rotation matrix.
        synchronized(rv) { SensorManager.getRotationMatrixFromVector(rv, e.values) }  // device -> ENU
        ready = true
    }

    override fun rWorldFromCam(rotationDegrees: Int): FloatArray? {
        if (!ready) return null
        // ENU (X east, Y north, Z up) -> Y-up world (X east, Y up, Z south): a row permutation.
        val pYup = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f)
        // camera(OpenCV) -> device is diag(1,-1,-1), which is its own inverse.
        val c = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        // Undo the analysis-image rotation applied to pixel directions.
        val ri = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        synchronized(rv) { return M3.mul(pYup, M3.mul(rv.copyOf(), M3.mul(c, ri))) }
    }
}

abstract class SensorEventListenerAdapter : android.hardware.SensorEventListener {
    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
}

/**
 * World-anchored opening track. With 3-DoF orientation only there is no translation estimate, so an
 * opening is remembered as a *bearing* plus the distance measured when it was last actually seen.
 * Bearing survives the opening leaving frame; distance goes stale if the user walks, which is why
 * `distanceM` is refreshed on every re-detection and `staleness` is exposed to the renderer.
 */
class OpeningTrack internal constructor(
    val id: Int,
    var kind: OpeningKind,
    var dirWorld: FloatArray,
    var distanceM: Float,
    var widthM: Float,
    var heightM: Float,
    var rWorldFromOpening: FloatArray,
    var confidence: Float,
    var scaleSource: ScaleSource
) {
    internal var hits = 1
    internal var misses = 0
    var lastSeenNanos = 0L; internal set

    val isConfirmed: Boolean get() = hits >= 3 && confidence >= 0.42f
    fun stalenessMs(nowNanos: Long) = (nowNanos - lastSeenNanos) / 1_000_000L

    /** Projected quad in the current frame, TL/TR/BR/BL, or null if off-camera or behind. */
    fun project(intr: Intrinsics, rWorldFromCam: FloatArray): Array<Point>? {
        val rCamFromWorld = M3.transpose(rWorldFromCam)
        val tCam = M3.mulVec(rCamFromWorld, floatArrayOf(
            dirWorld[0] * distanceM, dirWorld[1] * distanceM, dirWorld[2] * distanceM))
        if (tCam[2] <= 0.3f) return null
        val rCamFromOpening = M3.mul(rCamFromWorld, rWorldFromOpening)
        val hw = widthM / 2f; val hh = heightM / 2f
        val local = arrayOf(
            floatArrayOf(-hw, hh, 0f), floatArrayOf(hw, hh, 0f),
            floatArrayOf(hw, -hh, 0f), floatArrayOf(-hw, -hh, 0f))
        val out = arrayOfNulls<Point>(4)
        for (i in 0 until 4) {
            val p = M3.mulVec(rCamFromOpening, local[i])
            val q = floatArrayOf(p[0] + tCam[0], p[1] + tCam[1], p[2] + tCam[2])
            val px = intr.project(q) ?: return null
            out[i] = Point(px[0].toDouble(), px[1].toDouble())
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Point>
    }
}

class OpeningTracker(
    private val maxMisses: Int = 24,
    private val bearingTolCos: Float = 0.9945f,   // ~6 deg
    private val emaPos: Float = 0.35f,
    private val emaSize: Float = 0.22f
) {
    private val tracks = ArrayList<OpeningTrack>()
    private var nextId = 1

    val confirmed: List<OpeningTrack> get() = tracks.filter { it.isConfirmed }
    val all: List<OpeningTrack> get() = tracks

    fun update(poses: List<OpeningPose>, rWorldFromCam: FloatArray, nowNanos: Long): List<OpeningTrack> {
        val matched = HashSet<Int>()
        for (p in poses) {
            val dirCam = M3.norm3(p.tCam.copyOf())
            val dirW = M3.norm3(M3.mulVec(rWorldFromCam, dirCam))
            val rWO = M3.mul(rWorldFromCam, p.rCamFromOpening)

            val hit = tracks.filter { it.id !in matched }.maxByOrNull { t ->
                val cos = t.dirWorld[0] * dirW[0] + t.dirWorld[1] * dirW[1] + t.dirWorld[2] * dirW[2]
                if (cos < bearingTolCos) -1f
                else if (abs(t.heightM - p.heightM) / maxOf(t.heightM, p.heightM) > 0.45f) -1f
                else cos
            }?.takeIf { t ->
                (t.dirWorld[0] * dirW[0] + t.dirWorld[1] * dirW[1] + t.dirWorld[2] * dirW[2]) >= bearingTolCos
            }

            if (hit == null) {
                tracks += OpeningTrack(nextId++, p.kind, dirW, p.distanceM, p.widthM, p.heightM,
                    rWO, p.confidence * 0.7f, p.scaleSource).also { it.lastSeenNanos = nowNanos }
            } else {
                matched += hit.id
                hit.dirWorld = M3.norm3(blend(hit.dirWorld, dirW, emaPos))
                hit.distanceM += emaPos * (p.distanceM - hit.distanceM)
                hit.widthM += emaSize * (p.widthM - hit.widthM)
                hit.heightM += emaSize * (p.heightM - hit.heightM)
                hit.rWorldFromOpening = M3.orthonormalise(blend9(hit.rWorldFromOpening, rWO, emaPos))
                hit.confidence = (hit.confidence + 0.30f * (p.confidence - hit.confidence) + 0.05f).coerceAtMost(1f)
                hit.hits++; hit.misses = 0
                hit.lastSeenNanos = nowNanos
                // A floor-contact measurement always outranks a size guess.
                if (p.scaleSource == ScaleSource.FLOOR_CONTACT) hit.scaleSource = p.scaleSource
                if (p.confidence > hit.confidence * 0.9f) hit.kind = p.kind
            }
        }
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.id in matched) continue
            t.misses++
            t.confidence *= 0.965f
            if (t.misses > maxMisses || t.confidence < 0.14f) it.remove()
        }
        return confirmed
    }

    fun clear() { tracks.clear() }

    private fun blend(a: FloatArray, b: FloatArray, k: Float) =
        FloatArray(a.size) { a[it] + k * (b[it] - a[it]) }
    private fun blend9(a: FloatArray, b: FloatArray, k: Float) = blend(a, b, k)
}
