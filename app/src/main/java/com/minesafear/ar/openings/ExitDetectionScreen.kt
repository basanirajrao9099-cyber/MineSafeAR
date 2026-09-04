package com.minesafear.ar.openings

import android.content.Context
import android.hardware.SensorManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.minesafear.R
import com.minesafear.ar.ArCameraPermissionGate
import com.minesafear.ar.ArStatusBanner
import com.minesafear.ar.ArStatusSurface
import com.minesafear.ar.fallback.CameraFov
import com.minesafear.ar.fallback.MarkerTracker
import com.minesafear.ar.rememberArCameraPermission
import java.util.concurrent.Executors

/**
 * Egress detection screen: finds doors, doorways and windows and paints exit signage on them.
 *
 * Depends on no ARCore at all, so it works on handsets with no camera calibration profile — the
 * whole point, given `duchamp` fails `Session()` create. Reuses the project's own
 * [CameraFov] for intrinsics and [MarkerTracker.openCvLoaded] for the native load, so OpenCV is
 * initialised exactly once per process no matter which AR path the user takes first.
 */
@Composable
fun ExitDetectionScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permission = rememberArCameraPermission(requestOnFirstShow = true)

    Box(modifier.fillMaxSize()) {
        if (permission.isGranted) {
            if (MarkerTracker.openCvLoaded) {
                EgressDetectorContent(onClose = onClose)
            } else {
                // Native load failure is a packaging problem (abiFilters / jniLibs), not a
                // device limitation, so say so rather than showing an empty viewfinder.
                ArStatusSurface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "OpenCV native libraries failed to load, so opening detection " +
                                "cannot run. Check that this build includes your device's ABI.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onClose) { Text(stringRes(R.string.ar_close)) }
                    }
                }
            }
        } else {
            ArCameraPermissionGate(
                permission = permission,
                title = stringRes(R.string.exit_detect_title),
                onDismiss = onClose,
            )
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)

@Composable
private fun EgressDetectorContent(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val orientation = remember { GyroOrientationSource() }
    val gravity = remember { GravityProvider() }

    // Camera height is the only free parameter left in the scale chain; range error is linear in it.
    var camHeight by remember { mutableFloatStateOf(1.50f) }
    var showDebug by remember { mutableStateOf(false) }

    val analyzer = remember {
        OpeningAnalyzer(
            gravity = gravity,
            orientation = orientation,
            // Square pixels, so one focal length from the lens FOV covers both axes.
            intrinsicsFor = { w, h -> Intrinsics.fromHFov(CameraFov.horizontalRad(ctx), w, h) },
        )
    }
    analyzer.cameraHeightM = camHeight

    val executor = remember { Executors.newSingleThreadExecutor() }
    val frame = analyzer.frames.collectAsState().value

    DisposableEffect(Unit) {
        analyzer.start(sm)
        onDispose { analyzer.stop(sm); analyzer.release(); executor.shutdown() }
    }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PreviewView(c).apply {
                    // COMPATIBLE avoids the SurfaceView z-order fight with the Compose overlay.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER // must match ViewMap in the overlay
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                            // Explicit setter, not the synthetic property: the getter's
                            // availability moved around across CameraX 1.4.x.
                            .also { it.setSurfaceProvider(surfaceProvider) }
                        val selector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                                )
                            )
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(selector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                        )
                    }, ContextCompat.getMainExecutor(c))
                }
            },
        )

        OpeningOverlay(
            frame = frame,
            rWorldFromCam = frame?.let { orientation.rWorldFromCam(it.rotationDegrees) },
            modifier = Modifier.fillMaxSize(),
            showDebugQuads = showDebug,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val doors = frame?.tracks?.count { it.kind != OpeningKind.WINDOW } ?: 0
            val windows = frame?.tracks?.count { it.kind == OpeningKind.WINDOW } ?: 0
            ArStatusBanner(
                text = when {
                    frame == null -> "Hold the phone upright and pan slowly across the room"
                    doors + windows == 0 -> "Scanning for exits — pan across walls, keep the floor in view"
                    else -> "$doors exit(s), $windows window(s) · ${frame.detectMillis} ms/frame"
                }
            )

            ArStatusSurface {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Phone height above floor: ${"%.2f".format(camHeight)} m",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Scale comes from the floor, so an honest height beats a pretty default.
                    Slider(
                        value = camHeight,
                        onValueChange = { camHeight = it },
                        valueRange = 0.9f..1.9f,
                    )
                    Text(
                        text = "Green board = exit route. Amber = window, secondary egress only. " +
                            "A ~ before a range means it was inferred from a size assumption.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { showDebug = !showDebug }) {
                            Text(if (showDebug) "Quads on" else "Quads off")
                        }
                        TextButton(onClick = onClose) { Text(stringRes(R.string.ar_close)) }
                    }
                }
            }
        }
    }
}
