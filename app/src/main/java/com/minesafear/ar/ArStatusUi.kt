package com.minesafear.ar

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import com.minesafear.R
import java.util.Locale
import kotlin.math.abs

/**
 * Chrome that every AR screen needs: something legible over a camera feed, and
 * something to say when the camera is refused or tracking gives up.
 *
 * Shared rather than per-screen because the wording is a safety concern. A trainee
 * who is told two different things about why AR stopped working learns to ignore
 * both.
 */

/**
 * A translucent panel to put content on.
 *
 * Reads far better over a live camera feed than bare text does, and `Surface`
 * absorbs touches, so tapping the panel cannot also place or select an object in
 * the scene behind it.
 */
@Composable
fun ArStatusSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        content()
    }
}

/** One line of guidance on an [ArStatusSurface]. */
@Composable
fun ArStatusBanner(text: String, modifier: Modifier = Modifier) {
    ArStatusSurface(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Shown instead of the AR scene while the camera permission is missing.
 *
 * [ArScene] renders nothing without the permission and deliberately does not ask
 * for it, because what to say to a worker who declines the camera is a product
 * decision. This is that decision, in one place.
 */
@Composable
fun ArCameraPermissionGate(
    permission: ArCameraPermission,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val denied = permission.status == ArCameraPermission.Status.Denied

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (denied) R.string.ar_permission_denied else R.string.ar_permission_rationale
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Once refused twice the system dialog stops appearing, so a denied
            // state needs the Settings route rather than another ask.
            if (denied) {
                Button(onClick = { openAppSettings(context) }) {
                    Text(text = stringResource(R.string.ar_open_settings))
                }
            } else {
                Button(onClick = { permission.request() }) {
                    Text(text = stringResource(R.string.ar_grant_camera))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ar_close))
            }
        }
    }
}

/**
 * Maps ARCore's tracking failures to something a trainee can act on, or `null`
 * when tracking is healthy.
 */
@Composable
fun arTrackingFailureMessage(reason: TrackingFailureReason?): String? = when (reason) {
    null, TrackingFailureReason.NONE -> null
    TrackingFailureReason.BAD_STATE -> stringResource(R.string.ar_tracking_bad_state)
    TrackingFailureReason.INSUFFICIENT_LIGHT -> stringResource(R.string.ar_tracking_insufficient_light)
    TrackingFailureReason.EXCESSIVE_MOTION -> stringResource(R.string.ar_tracking_excessive_motion)
    TrackingFailureReason.INSUFFICIENT_FEATURES ->
        stringResource(R.string.ar_tracking_insufficient_features)

    TrackingFailureReason.CAMERA_UNAVAILABLE ->
        stringResource(R.string.ar_tracking_camera_unavailable)
    // ARCore can add reasons; an unknown one is better than a crash.
    else -> null
}

/**
 * UI controls overlay to adjust size (scale) and direction (rotation) of a selected [ArPlacement].
 */
@Composable
fun ArObjectAdjustmentPanel(
    placement: ArPlacement,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }

    ArStatusSurface(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Header Row: Title & Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "Adjust Object",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Rot: ${placement.rotationYDegrees.toInt()}° · Height: ${(placement.floatHeightMetres * 100).toInt()}cm · Scale: ${(placement.userScaleMultiplier * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse panel" else "Expand panel",
                        )
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove object",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close adjustments",
                        )
                    }
                }
            }

            if (isExpanded) {
                // Direction / Rotation Section
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Direction (Rotation)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "${placement.rotationYDegrees.toInt()}°",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { placement.rotate(-15f) }) {
                            Text("-15°", style = MaterialTheme.typography.labelSmall)
                        }

                        Slider(
                            value = placement.rotationYDegrees,
                            onValueChange = { placement.setRotation(it) },
                            valueRange = 0f..360f,
                            modifier = Modifier.weight(1f),
                        )

                        TextButton(onClick = { placement.rotate(15f) }) {
                            Text("+15°", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        listOf(0f to "0°", 90f to "90°", 180f to "180°", 270f to "270°").forEach { (angle, label) ->
                            FilterChip(
                                selected = (placement.rotationYDegrees.toInt() == angle.toInt()),
                                onClick = { placement.setRotation(angle) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }

                // Float Height / Surface Elevation Section
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Float Height (Surface Elevation)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "${(placement.floatHeightMetres * 100).toInt()} cm (${String.format(
                                Locale.US, "%.2f", placement.floatHeightMetres)}m)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { placement.adjustFloatHeight(-0.05f) }) {
                            Text("-5cm", style = MaterialTheme.typography.labelSmall)
                        }

                        Slider(
                            value = placement.floatHeightMetres,
                            onValueChange = { placement.setFloatHeight(it) },
                            valueRange = 0.0f..1.0f,
                            modifier = Modifier.weight(1f),
                        )

                        TextButton(onClick = { placement.adjustFloatHeight(0.05f) }) {
                            Text("+5cm", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        listOf(
                            0.00f to "Ground (0cm)",
                            0.15f to "Low (15cm)",
                            0.25f to "Float (25cm)",
                            0.50f to "High (50cm)",
                        ).forEach { (height, label) ->
                            FilterChip(
                                selected = (abs(placement.floatHeightMetres - height) < 0.03f),
                                onClick = { placement.setFloatHeight(height) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }

                // Size / Scale Section
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Size (Scale)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "${(placement.userScaleMultiplier * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { placement.scaleBy(0.9f) }) {
                            Text("-10%", style = MaterialTheme.typography.labelSmall)
                        }

                        Slider(
                            value = placement.userScaleMultiplier,
                            onValueChange = { placement.setScaleMultiplier(it) },
                            valueRange = 0.3f..2.5f,
                            modifier = Modifier.weight(1f),
                        )

                        TextButton(onClick = { placement.scaleBy(1.1f) }) {
                            Text("+10%", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        listOf(0.5f to "50%", 1.0f to "100%", 1.5f to "150%", 2.0f to "200%").forEach { (scale, label) ->
                            FilterChip(
                                selected = (abs(placement.userScaleMultiplier - scale) < 0.05f),
                                onClick = { placement.setScaleMultiplier(scale) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        }
    }
}
