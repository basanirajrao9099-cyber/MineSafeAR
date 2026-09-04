package com.minesafear.ar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * View and projection matrices captured from one ARCore frame.
 *
 * Both are 16-element column-major arrays, matching OpenGL and ARCore's own
 * `Camera.getViewMatrix` / `getProjectionMatrix` output. Copied per frame rather
 * than aliased, because consumers read them on the UI thread while the GL thread
 * is already filling the next frame's buffers.
 */
data class ArCameraMatrices(
    val view: FloatArray,
    val projection: FloatArray,
) {
    /** Array identity, not contents — a per-frame value is never compared by value. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Hazard highlighting for real objects on the floor.
 *
 * Silhouette outlines only. An earlier version drew projected floor rings around
 * each detected cluster; those were dropped deliberately, because a ring marks
 * *the ground near* an obstacle while an outline marks the obstacle itself, and
 * the ring read as a placed AR prop rather than as a warning. Devices without
 * depth support now show nothing here rather than a misleading ring — the
 * obstacle-count chip still reports the detection.
 */
@Composable
fun ObstacleHighlightOverlay(
    contours: List<ObstacleContour>,
    modifier: Modifier = Modifier,
) {
    if (contours.isEmpty()) return
    ObstacleSilhouetteLayer(contours = contours, modifier = modifier)
}
