package com.minesafear.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
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

    Box(modifier = modifier) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            // Horizontal only: this pipeline places equipment on floors and benches.
            // Wall signage (see ArModels) needs HORIZONTAL_AND_VERTICAL here plus a
            // vertical branch in ARSessionManager.horizontalPlaneHit.
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            // The translucent grid over detected planes.
            planeRenderer = showPlaneRenderer,
            // null = do not let SceneView request the camera permission; ArCameraPermission
            // owns that flow, and two competing dialogs is worse than none.
            permissionHandler = null,
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
        ) {
            // Obstacles are NOT rendered as 3D nodes here. A grey placeholder cube on
            // top of an obstacle is indistinguishable from a placed training prop, and
            // sitting inside the translucent plane grid it read as part of the floor.
            // ObstacleSilhouetteLayer traces their real outlines in screen space
            // instead; see DepthObstacleMask for how the outline is recovered.

            manager.placements.forEach { placement ->
                key(placement.id) {
                    // createModelInstance parses the .glb and must run on the main
                    // thread (Filament JNI). One instance per placement: a Filament
                    // ModelInstance cannot be attached to two nodes at once.
                    //
                    // Instances are owned by the ModelLoader and released with it.
                    val instance = remember(placement.id) {
                        runCatching { modelLoader.createModelInstance(placement.modelRes) }.getOrNull()
                    }
                    if (instance != null) {
                        val scaleMult = placement.userScaleMultiplier
                        val rotY = placement.rotationYDegrees

                        AnchorNode(anchor = placement.anchor) {
                            ModelNode(
                                modelInstance = instance,
                                scaleToUnits = placement.scaleToUnits * scaleMult,
                                rotation = io.github.sceneview.math.Rotation(x = 0f, y = rotY, z = 0f),
                            )
                        }
                    }
                }
            }
        }

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
