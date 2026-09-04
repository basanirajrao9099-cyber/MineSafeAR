package com.minesafear.ar.fallback

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.minesafear.R
import com.minesafear.ar.TrackedPose
import com.minesafear.ar.TrackingMode
import java.util.concurrent.Executors

/**
 * Camera preview + marker tracking for devices ARCore rejects.
 * Caller feeds [onPose] into the existing SceneView node transform.
 */
@Composable
fun FallbackArView(
    mode: TrackingMode,
    markerSizeMeters: Float = 0.20f,
    onPose: (TrackedPose?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    var tracked by remember { mutableStateOf(false) }

    // Gyro path needs no camera analysis, only orientation.
    val gyro = remember(mode) {
        if (mode == TrackingMode.GYRO_3DOF) GyroPose(context) { onPose(it) } else null
    }
    DisposableEffect(gyro) {
        gyro?.start()
        onDispose { gyro?.stop(); exec.shutdown() }
    }

    Box(modifier) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE // low-end GPU safe
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.surfaceProvider = surfaceProvider }
                    val uses = mutableListOf<UseCase>(preview)
                    if (mode == TrackingMode.MARKER_6DOF) {
                        val fov = CameraFov.horizontalRad(ctx)
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder().build())
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(exec, MarkerTracker(markerSizeMeters, fov) { pose ->
                            tracked = pose != null
                            onPose(pose)
                        })
                        uses += analysis
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *uses.toTypedArray())
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxSize())

        Text(
            text = stringResource(
                when (mode) {
                    TrackingMode.FULL_AR -> R.string.tracking_full_ar
                    TrackingMode.MARKER_6DOF -> R.string.tracking_marker
                    TrackingMode.GYRO_3DOF -> R.string.tracking_gyro
                }
            ),
            color = if (tracked || mode == TrackingMode.GYRO_3DOF) Color.White else Color.Yellow,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
        )
    }
}
