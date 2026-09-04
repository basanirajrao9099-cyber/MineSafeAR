package com.minesafear.ar

/** Pose in Filament/GL space: Y up, Z toward viewer, translation in metres. */
data class TrackedPose(val translation: FloatArray, val rotation: FloatArray)

enum class TrackingMode { FULL_AR, MARKER_6DOF, GYRO_3DOF }

/**
 * Runtime selection so UI never branches on device capability.
 * ARCore (real Session() create) -> ArUco marker 6-DoF -> gyro 3-DoF.
 */
object TrackingSourceFactory {
    fun select(arSupported: Boolean, openCvLoaded: Boolean): TrackingMode = when {
        arSupported -> TrackingMode.FULL_AR
        openCvLoaded -> TrackingMode.MARKER_6DOF
        else -> TrackingMode.GYRO_3DOF
    }

    /** MARKER_6DOF and FULL_AR give real translation; gyro cannot. Drives parallax-dependent UI. */
    fun hasMetricScale(mode: TrackingMode) = mode != TrackingMode.GYRO_3DOF
}
