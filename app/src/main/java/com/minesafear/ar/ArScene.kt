package com.minesafear.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.ar.core.Config
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader

/**
 * The AR camera view, with horizontal plane detection and tap-to-place wired to
 * [manager].
 *
 * Renders nothing until [ARSessionManager.permission] is granted — the caller
 * owns the refusal UI, because what to say to a worker who declines the camera is
 * a product decision, not a rendering one. See `ARTestActivity` for an example.
 *
 * ## The plane indicator
 *
 * `planeRenderer = true` is SceneView's translucent grid, drawn over every plane
 * ARCore is tracking. That is the "you can tap here" affordance; it appears a
 * second or two after the camera starts moving, which is why
 * [ARSessionManager.hasTrackedPlane] exists to drive a hint until then.
 *
 * ## Lifecycle
 *
 * `ARSceneView` resumes and pauses the ARCore session from the composition's
 * lifecycle and destroys it on dispose, so there is no manual plumbing here. The
 * engine and model loader are hoisted out of the call only so the `content`
 * lambda can build model instances; both are the library's own defaults, and both
 * are destroyed when this composition leaves.
 */
@Composable
fun ArScene(
    manager: ARSessionManager,
    modifier: Modifier = Modifier,
    showPlaneRenderer: Boolean = true,
    showObstacleHighlights: Boolean = true,
) {
    if (!manager.permission.isGranted) return

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    val nodes = remember(manager.placements) {
        manager.placements.mapNotNull { placement ->
            val instance = runCatching { modelLoader.createModelInstance(placement.modelRes) }.getOrNull()
            if (instance != null) {
                val scaleMult = placement.userScaleMultiplier
                val rotY = placement.rotationYDegrees
                AnchorNode(engine = engine, anchor = placement.anchor).apply {
                    addChildNode(
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = placement.scaleToUnits * scaleMult,
                        ).apply {
                            rotation = io.github.sceneview.math.Rotation(x = 0f, y = rotY, z = 0f)
                        }
                    )
                }
            } else null
        }
    }

    Box(modifier = modifier) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            childNodes = nodes,
            // The translucent grid over detected planes.
            planeRenderer = showPlaneRenderer,
            sessionConfiguration = { session, config ->
                // Redundant with planeFindingMode above, but this is the hook a
                // training module overrides, so state the requirement where it is read.
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                // Autofocus: trainees hold the phone close to read equipment labels,
                // and ARCore's default fixed focus renders that unreadably soft.
                config.focusMode = Config.FocusMode.AUTO
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                }
            },
            onSessionCreated = manager::onSessionCreated,
            onSessionResumed = manager::onSessionResumed,
            onSessionPaused = manager::onSessionPaused,
            onSessionUpdated = manager::onSessionUpdated,
            onTrackingFailureChanged = manager::onTrackingFailureChanged,
            // The second parameter is a Filament node pick, not an ARCore plane hit,
            // so it is discarded; ARSessionManager does its own Frame.hitTest.
            onTouchEvent = { event, _ -> manager.onTouchEvent(event) },
        )

        // Drawn after the AR view so it composites on top of the camera feed and
        // the plane grid. Pointer-transparent: taps still reach ARSceneView.
        if (showObstacleHighlights && manager.isObstacleDetectionEnabled) {
            ObstacleHighlightOverlay(
                contours = manager.obstacleContours,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
