package com.minesafear.ar.fallback

import android.content.Context
import android.hardware.*
import com.minesafear.ar.TrackedPose

/** 3-DoF orientation only. GAME_ROTATION_VECTOR has no magnetometer drift correction. */
class GyroPose(context: Context, private val onPose: (TrackedPose) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available get() = sensor != null

    fun start() = sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } != null
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // Android ENU (X east, Y north, Z up) -> GL (X right, Y up, Z back): row permutation.
        val q = floatArrayOf(e.values[0], e.values[2], -e.values[1],
            if (e.values.size > 3) e.values[3] else 0f)
        onPose(TrackedPose(floatArrayOf(0f, 0f, 0f), q))
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
