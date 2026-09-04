package com.minesafear.ar

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.minesafear.R
import com.minesafear.ar.fallback.FallbackArView
import com.minesafear.ar.fallback.MarkerTracker
import com.minesafear.localization.AppLocaleManager
import com.minesafear.ui.theme.MineSafeArTheme

/**
 * Harness that proves the AR pipeline end to end: permission ->
 * ARCore session -> horizontal plane detection -> translucent plane grid -> tap or button
 * -> anchored model.
 */
class ARTestActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MineSafeArTheme {
                ArTestScreen(onClose = { finish() })
            }
        }
    }
}

private data class ArModelOption(
    val name: String,
    @RawRes val modelRes: Int,
    val scaleMetres: Float,
)

private val modelOptions = listOf(
    ArModelOption("Cube", ArModels.PLACEHOLDER_CUBE, ArModels.DEFAULT_SCALE_METRES),
    ArModelOption("CO₂ Extinguisher", ArModels.EXTINGUISHER_CO2, ArModels.EXTINGUISHER_SCALE_METRES),
    ArModelOption("Foam Extinguisher", ArModels.EXTINGUISHER_FOAM, ArModels.EXTINGUISHER_SCALE_METRES),
    ArModelOption("Water Extinguisher", ArModels.EXTINGUISHER_WATER, ArModels.EXTINGUISHER_SCALE_METRES),
    ArModelOption("Exit Sign", ArModels.EXIT_SIGN, ArModels.EXIT_SIGN_SCALE_METRES),
    ArModelOption("Exit Arrow", ArModels.EXIT_ARROW, ArModels.EXIT_ARROW_SCALE_METRES),
)

@Composable
private fun ArTestScreen(onClose: () -> Unit) {
    val manager = rememberArSessionManager()
    val arCore = rememberArCoreStatus(cameraGranted = manager.permission.isGranted)
    var showPlaneHighlight by remember { mutableStateOf(true) }
    var selectedOptionIndex by remember { mutableIntStateOf(0) }
    var forceFallbackMode by remember { mutableStateOf(false) }

    val currentOption = modelOptions.getOrElse(selectedOptionIndex) { modelOptions[0] }
    manager.activeModelRes = currentOption.modelRes
    manager.activeScaleToUnits = currentOption.scaleMetres

    Box(modifier = Modifier.fillMaxSize()) {
        if (!arCore.blocksAr && !forceFallbackMode) {
            ArScene(
                manager = manager,
                modifier = Modifier.fillMaxSize(),
                showPlaneRenderer = showPlaneHighlight,
            )
        } else if (forceFallbackMode && manager.permission.isGranted) {
            FallbackArView(
                mode = TrackingSourceFactory.select(
                    arSupported = false,
                    openCvLoaded = MarkerTracker.openCvLoaded,
                ),
                onPose = { },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when {
            forceFallbackMode -> ArTestOverlay(
                manager = manager,
                showPlaneHighlight = showPlaneHighlight,
                onTogglePlaneHighlight = { showPlaneHighlight = !showPlaneHighlight },
                selectedOptionIndex = selectedOptionIndex,
                onSelectOption = { index -> selectedOptionIndex = index },
                onClose = onClose,
            )

            arCore.blocksAr -> ArCoreUnavailableGate(
                status = arCore,
                onDismiss = onClose,
                onContinueFallback = { forceFallbackMode = true },
            )

            manager.permission.isGranted -> ArTestOverlay(
                manager = manager,
                showPlaneHighlight = showPlaneHighlight,
                onTogglePlaneHighlight = { showPlaneHighlight = !showPlaneHighlight },
                selectedOptionIndex = selectedOptionIndex,
                onSelectOption = { index -> selectedOptionIndex = index },
                onClose = onClose,
            )

            else -> ArCameraPermissionGate(
                permission = manager.permission,
                title = stringResource(R.string.ar_test_title),
                onDismiss = onClose,
            )
        }
    }
}

@Composable
private fun ArTestOverlay(
    manager: ARSessionManager,
    showPlaneHighlight: Boolean,
    onTogglePlaneHighlight: () -> Unit,
    selectedOptionIndex: Int,
    onSelectOption: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlaySize = it.size }
    ) {
        // Center reticle indicator showing where "Add Object" will drop the 3D model
        if (manager.hasTrackedPlane) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.4f), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Section: Tracking Status + Surface Highlight Badge
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = arTrackingFailureMessage(manager.trackingFailure)
                    ?: stringResource(
                        if (manager.hasTrackedPlane) R.string.ar_hint_tap_plane else R.string.ar_hint_find_plane
                    )

                ArStatusBanner(text = status)

                // Surface Detection Highlight Chip
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (manager.hasTrackedPlane) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    shape = CircleShape,
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (manager.hasTrackedPlane) "Surface Detected (Floor / Table)" else "Scanning for Surface...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Obstacles & Uneven Ground Chip
                val obstacleCount = manager.detectedObstacles.size
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (obstacleCount > 0) Color(0xFFE53935) else Color(0xFF2196F3),
                                    shape = CircleShape,
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (obstacleCount > 0) "⚠️ $obstacleCount Obstacle(s) / Rocky Surface Detected" else "Ground Scan: Clear Terrain",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Bottom Section: Object Selection Bar + Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val selectedPlacement = manager.selectedPlacement
                if (selectedPlacement != null) {
                    ArObjectAdjustmentPanel(
                        placement = selectedPlacement,
                        onClose = { manager.selectPlacement(null) },
                        onRemove = { manager.remove(selectedPlacement) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ArStatusSurface {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    Text(
                        text = "Select Object to Add:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    // Horizontal Object Picker Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        modelOptions.forEachIndexed { index, option ->
                            val selected = (index == selectedOptionIndex)
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectOption(index) },
                                label = { Text(option.name) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }

                    // Action buttons: "Add Object", "Grid On/Off", "Clear", "Close"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val cx = if (overlaySize.width > 0) overlaySize.width / 2f else 500f
                                val cy = if (overlaySize.height > 0) overlaySize.height / 2f else 1000f
                                manager.enqueueTap(cx, cy)
                            },
                            enabled = manager.hasTrackedPlane,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Object")
                        }

                        OutlinedButton(
                            onClick = onTogglePlaneHighlight,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = if (showPlaneHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (showPlaneHighlight) "Grid On" else "Grid Off")
                        }

                        TextButton(onClick = { manager.clear() }) {
                            Text(text = stringResource(R.string.ar_clear))
                        }

                        TextButton(onClick = onClose) {
                            Text(text = stringResource(R.string.ar_close))
                        }
                    }
                }
            }
        }
    }
}
}
