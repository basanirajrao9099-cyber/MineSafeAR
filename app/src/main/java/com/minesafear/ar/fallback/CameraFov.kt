package com.minesafear.ar.fallback

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.atan2

/** Lens FOV from Camera2 — replaces the ARCore calibration profile this device lacks. */
object CameraFov {
    fun horizontalRad(context: Context): Float {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return 1.15f // ~66 deg fallback
        val c = cm.getCameraCharacteristics(id)
        val f = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return 1.15f
        val s = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return 1.15f
        return 2f * atan2(s.width / 2f, f) // pinhole: 2*atan(sensorW/2 / focal)
    }
}
