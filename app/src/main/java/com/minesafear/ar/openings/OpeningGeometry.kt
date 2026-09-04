package com.minesafear.ar.openings

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Kind of egress feature. ORDER MATTERS: used as a priority when signage overlaps. */
enum class OpeningKind { DOORWAY, DOOR, WINDOW, UNKNOWN }

/** Real-world size priors (metres) used to recover metric scale without depth.
 *  DGMS mine-gallery man-doors run ~0.9 x 2.0; surface-building doors ~0.9 x 2.05. */
data class SizePrior(val widthM: Float, val heightM: Float, val minAspect: Float, val maxAspect: Float)

object SizePriors {
    // aspect = height / width
    val DOOR = SizePrior(0.90f, 2.05f, 1.55f, 3.20f)
    val DOORWAY = SizePrior(1.10f, 2.05f, 1.20f, 3.20f)
    val WINDOW = SizePrior(1.20f, 1.20f, 0.45f, 1.55f)
    fun of(kind: OpeningKind) = when (kind) {
        OpeningKind.DOOR -> DOOR
        OpeningKind.DOORWAY -> DOORWAY
        OpeningKind.WINDOW -> WINDOW
        OpeningKind.UNKNOWN -> DOOR
    }
}

/** Pinhole intrinsics for the *analysis* image, in that image's own pixel coords. */
data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val w: Int, val h: Int) {

    /** Uniform scale when the analysis Mat is downscaled before detection. */
    fun scaled(s: Float) = Intrinsics(fx * s, fy * s, cx * s, cy * s, (w * s).toInt(), (h * s).toInt())

    /**
     * Intrinsics after Core.rotate on the image. ROTATE_90_CLOCKWISE maps (x,y) -> (H-1-y, x),
     * so the focal lengths swap and the principal point is permuted the same way.
     */
    fun rotated(deg: Int): Intrinsics = when (((deg % 360) + 360) % 360) {
        90 -> Intrinsics(fy, fx, (h - 1) - cy, cx, h, w)
        180 -> Intrinsics(fx, fy, (w - 1) - cx, (h - 1) - cy, w, h)
        270 -> Intrinsics(fy, fx, cy, (w - 1) - cx, h, w)
        else -> this
    }

    /** Unit ray through a pixel, OpenCV camera frame: X right, Y down, Z forward. */
    fun ray(px: Float, py: Float): FloatArray {
        val x = (px - cx) / fx
        val y = (py - cy) / fy
        val n = sqrt(x * x + y * y + 1f)
        return floatArrayOf(x / n, y / n, 1f / n)
    }

    /** Project a camera-frame point back to pixels. Returns null if behind the camera. */
    fun project(p: FloatArray): FloatArray? {
        if (p[2] <= 1e-4f) return null
        return floatArrayOf(fx * p[0] / p[2] + cx, fy * p[1] / p[2] + cy)
    }

    companion object {
        /** From horizontal FOV (radians) — pair with CameraFov.kt, which reads Camera2 lens data. */
        fun fromHFov(hFovRad: Float, w: Int, h: Int): Intrinsics {
            val fx = (w / 2f) / kotlin.math.tan(hFovRad / 2f)
            return Intrinsics(fx, fx, w / 2f, h / 2f, w, h) // square pixels: fy == fx
        }
    }
}

/**
 * Gravity in the OpenCV camera frame of the rotated analysis image.
 * TYPE_GRAVITY is in Android device coords (X right, Y up, Z out of screen toward user), and the
 * back camera looks along -Zdevice while OpenCV Y points down, so device -> camera is
 *     Xcam = +Xdev,  Ycam = -Ydev,  Zcam = -Zdev   (i.e. diag(1,-1,-1))
 * The analysis image is then rotated by `rotationDegrees`, which permutes (x,y) of any
 * direction the same way pixels move: 90 CW -> (x,y) becomes (-y, x).
 * See gravityInCamera for the sensor-sign subtlety.
 */
class GravityProvider : android.hardware.SensorEventListener {
    @Volatile private var gx = 0f
    @Volatile private var gy = 9.81f
    @Volatile private var gz = 0f
    @Volatile var hasData = false; private set

    fun register(sm: SensorManager) {
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun unregister(sm: SensorManager) = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // Low-pass so an accelerometer fallback is usable as a gravity estimate.
        val a = 0.15f
        synchronized(this) {
            gx += a * (e.values[0] - gx); gy += a * (e.values[1] - gy); gz += a * (e.values[2] - gz)
        }
        hasData = true
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit

    /**
     * Unit gravity in the rotated image's camera frame, POINTING TOWARD THE FLOOR.
     *
     * TYPE_GRAVITY reports the *reaction* vector: face-up on a table it reads (0, 0, +9.81) even
     * though gravity physically pulls along -Zdevice. So the true down-vector is -sensor, and
     * composing that with device->camera diag(1,-1,-1) gives (-gx, +gy, +gz). Getting this sign
     * wrong silently inverts every floor range and puts the horizon above the ceiling.
     */
    fun gravityInCamera(rotationDegrees: Int): FloatArray {
        val sx: Float; val sy: Float; val sz: Float
        synchronized(this) { sx = gx; sy = gy; sz = gz }
        return gravityCamFromSensor(sx, sy, sz, rotationDegrees)
    }
}

/** Pure form of the sensor -> camera-frame gravity transform, so it is unit-testable off-device. */
fun gravityCamFromSensor(sx: Float, sy: Float, sz: Float, rotationDegrees: Int): FloatArray {
    var cx = -sx; var cy = sy; val cz = sz            // -sensor, then device -> OpenCV camera
    when (((rotationDegrees % 360) + 360) % 360) {    // then apply the image rotation
        90 -> { val t = cx; cx = -cy; cy = t }
        180 -> { cx = -cx; cy = -cy }
        270 -> { val t = cx; cx = cy; cy = -t }
    }
    val n = sqrt(cx * cx + cy * cy + cz * cz).takeIf { it > 1e-6f } ?: 1f
    return floatArrayOf(cx / n, cy / n, cz / n)
}

object GroundPlane {

    /**
     * Distance along a pixel ray to the floor, assuming the camera is `camHeightM` above it.
     * Floor is the plane { P : dot(P, g) = camHeight } with g the unit gravity through the origin.
     * s = camHeight / dot(ray, g); requires the ray to have a downward component.
     */
    fun rangeToFloor(intr: Intrinsics, g: FloatArray, px: Float, py: Float, camHeightM: Float): Float? {
        val d = intr.ray(px, py)
        val dot = d[0] * g[0] + d[1] * g[1] + d[2] * g[2]
        if (dot < 0.035f) return null                      // ~2 deg above horizon: ill-conditioned
        val s = camHeightM / dot
        return if (s in 0.4f..80f) s else null
    }

    /** Image-space "up" (unit) — the direction opposite projected gravity. Drives line rectification. */
    fun imageUp(g: FloatArray): FloatArray {
        val n = hypot(g[0], g[1]).takeIf { it > 1e-4f } ?: return floatArrayOf(0f, -1f)
        return floatArrayOf(-g[0] / n, -g[1] / n)
    }

    /** Camera roll about the optical axis, radians. Used to reject frames tilted past ~35 deg. */
    fun rollRad(g: FloatArray): Float = atan2(g[0], g[1])

    /** y of the horizon at a given x: the locus where dot(ray, g) == 0. Null if gy ~ 0 (camera rolled 90). */
    fun horizonY(intr: Intrinsics, g: FloatArray, x: Float): Float? {
        if (abs(g[1]) < 1e-3f) return null
        // dot = g0*(x-cx)/fx + g1*(y-cy)/fy + g2 = 0  ->  solve for y
        val a = g[0] * (x - intr.cx) / intr.fx + g[2]
        return intr.cy - intr.fy * a / g[1]
    }
}
