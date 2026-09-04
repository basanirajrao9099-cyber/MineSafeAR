package com.minesafear.ui.modules.extinguisher

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ar.core.HitResult
import com.minesafear.R
import com.minesafear.ar.ARSessionManager
import com.minesafear.ar.ArCameraPermissionGate
import com.minesafear.ar.ArModels
import com.minesafear.ar.ArObjectAdjustmentPanel
import com.minesafear.ar.ArScene
import com.minesafear.ar.ArStatusSurface
import com.minesafear.ar.TrackingSourceFactory
import com.minesafear.ar.arTrackingFailureMessage
import com.minesafear.ar.fallback.FallbackArView
import com.minesafear.ar.fallback.MarkerTracker
import com.minesafear.ar.rememberArCoreStatus
import com.minesafear.ar.rememberArSessionManager
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.narration.NarrationSlot
import com.minesafear.narration.rememberBriefingNarration
import com.minesafear.sync.SyncScheduler
import kotlinx.coroutines.delay

private const val TAG = "ExtinguisherScreen"

@Composable
fun ExtinguisherTrainingScreen(
    onExit: () -> Unit,
    onComplete: (ExtinguisherOutcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drill = rememberExtinguisherTrainingState()
    val manager = rememberArSessionManager()

    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }

    val arCore = rememberArCoreStatus(cameraGranted = manager.permission.isGranted)

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(manager, drill) {
        manager.onPlaneTap = { hit -> handlePlaneTap(hit, manager, drill) }
        onDispose { }
    }

    LaunchedEffect(drill.step) {
        if (drill.step != ExtinguisherStep.COMPLETE) return@LaunchedEffect
        val outcome = drill.toOutcome()
        runCatching {
            repository.saveModuleResult(drill.toResult(TrainingRepository.UNPROVISIONED_USER_ID))
        }.onSuccess {
            SyncScheduler.requestSyncNow(context)
        }.onFailure { error ->
            Log.e(TAG, "Failed to save extinguisher module result", error)
        }
        onComplete(outcome)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!arCore.blocksAr) {
            ArScene(manager = manager, modifier = Modifier.fillMaxSize())
        } else if (manager.permission.isGranted) {
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
            !manager.permission.isGranted -> ArCameraPermissionGate(
                permission = manager.permission,
                title = stringResource(R.string.extinguisher_module_title),
                onDismiss = onExit,
            )

            drill.step == ExtinguisherStep.BRIEFING -> ExtinguisherBriefingOverlay(
                onStart = drill::beginDrill,
                onExit = onExit,
            )

            else -> ExtinguisherDrillOverlay(
                drill = drill,
                manager = manager,
                onExit = onExit,
            )
        }

        ExtinguisherFeedbackOverlay(
            feedback = drill.feedback,
            onDismiss = drill::dismissFeedback,
        )
    }
}

private fun handlePlaneTap(
    hit: HitResult,
    manager: ARSessionManager,
    drill: ExtinguisherTrainingState,
) {
    if (drill.isAwaitingAcknowledgement) return

    if (drill.step == ExtinguisherStep.PLACING_EXTINGUISHER || manager.placements.isEmpty()) {
        val placed = manager.placeObjectAt(
            hitResult = hit,
            modelRes = ArModels.EXTINGUISHER_REALISTIC,
            scaleToUnits = ArModels.EXTINGUISHER_SCALE_METRES,
            tag = "realistic_extinguisher",
        )
        if (placed != null && drill.step == ExtinguisherStep.PLACING_EXTINGUISHER) {
            drill.onExtinguisherPlaced()
        }
    }
}

@Composable
private fun ExtinguisherBriefingOverlay(
    onStart: () -> Unit,
    onExit: () -> Unit,
) {
    val narration = rememberBriefingNarration(NarrationSlot.FIRE_BRIEFING)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.extinguisher_briefing_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.extinguisher_module_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.extinguisher_briefing_1),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.extinguisher_briefing_2),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.extinguisher_briefing_3),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = narration::toggle,
                enabled = narration.isAvailable,
            ) {
                Text(
                    text = stringResource(
                        if (narration.isPlaying) R.string.narration_stop
                        else R.string.narration_play
                    )
                )
            }
            if (!narration.isAvailable) {
                Text(
                    text = stringResource(R.string.narration_not_recorded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    narration.stop()
                    onStart()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.fire_module_start_drill))
            }
            TextButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ar_close))
            }
        }
    }
}

@Composable
private fun ExtinguisherDrillOverlay(
    drill: ExtinguisherTrainingState,
    manager: ARSessionManager,
    onExit: () -> Unit,
) {
    var confirmingExit by remember { mutableStateOf(false) }
    val selectedPlacement = manager.selectedPlacement
    val activeExtinguisher = manager.placements.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        if (selectedPlacement != null) {
            ArObjectAdjustmentPanel(
                placement = selectedPlacement,
                onClose = { manager.selectPlacement(null) },
                onRemove = { manager.remove(selectedPlacement) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        } else if (activeExtinguisher != null && drill.step != ExtinguisherStep.PLACING_EXTINGUISHER) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "🧯 Adjust Extinguisher",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(
                        onClick = { manager.selectPlacement(activeExtinguisher) },
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArStatusSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.extinguisher_module_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = drillPrompt(drill = drill, manager = manager),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StepProgressIndicator(step = drill.step)

                    ExtinguisherActionContent(
                        drill = drill,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            }

            ArStatusSurface(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = elapsedLabel(drill),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.fire_module_wrong_count, drill.wrongActions),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { confirmingExit = true }) {
                        Text(text = stringResource(R.string.fire_module_quit))
                    }
                }
            }
        }
    }

    if (confirmingExit) {
        AlertDialog(
            onDismissRequest = { confirmingExit = false },
            title = { Text(text = stringResource(R.string.fire_module_quit_confirm_title)) },
            text = { Text(text = stringResource(R.string.fire_module_quit_confirm_body)) },
            confirmButton = {
                TextButton(onClick = onExit) {
                    Text(text = stringResource(R.string.fire_module_quit))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingExit = false }) {
                    Text(text = stringResource(R.string.fire_module_quit_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun StepProgressIndicator(step: ExtinguisherStep) {
    val progress = when (step) {
        ExtinguisherStep.BRIEFING -> 0f
        ExtinguisherStep.PLACING_EXTINGUISHER -> 0.1f
        ExtinguisherStep.INSPECT_GAUGE -> 0.25f
        ExtinguisherStep.PULL_PIN -> 0.45f
        ExtinguisherStep.AIM_BASE -> 0.65f
        ExtinguisherStep.SQUEEZE_LEVER -> 0.85f
        ExtinguisherStep.SWEEP_NOZZLE -> 0.95f
        ExtinguisherStep.COMPLETE -> 1f
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.extinguisher_step_progress, stepName(step)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun stepName(step: ExtinguisherStep): String = when (step) {
    ExtinguisherStep.BRIEFING -> stringResource(R.string.extinguisher_step_name_briefing)
    ExtinguisherStep.PLACING_EXTINGUISHER -> stringResource(R.string.extinguisher_step_name_place)
    ExtinguisherStep.INSPECT_GAUGE -> stringResource(R.string.extinguisher_step_name_inspect)
    ExtinguisherStep.PULL_PIN -> stringResource(R.string.extinguisher_step_name_pull)
    ExtinguisherStep.AIM_BASE -> stringResource(R.string.extinguisher_step_name_aim)
    ExtinguisherStep.SQUEEZE_LEVER -> stringResource(R.string.extinguisher_step_name_squeeze)
    ExtinguisherStep.SWEEP_NOZZLE -> stringResource(R.string.extinguisher_step_name_sweep)
    ExtinguisherStep.COMPLETE -> stringResource(R.string.extinguisher_step_name_complete)
}

@Composable
private fun ExtinguisherActionContent(
    drill: ExtinguisherTrainingState,
    modifier: Modifier = Modifier,
) {
    if (drill.step == ExtinguisherStep.BRIEFING || drill.step == ExtinguisherStep.PLACING_EXTINGUISHER || drill.step == ExtinguisherStep.COMPLETE) {
        return
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (drill.step) {
            ExtinguisherStep.INSPECT_GAUGE -> {
                Text(
                    text = stringResource(R.string.extinguisher_inspect_question),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { drill.inspectGauge(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_inspect_green))
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { drill.inspectGauge(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_inspect_red))
                }
            }

            ExtinguisherStep.PULL_PIN -> {
                Text(
                    text = stringResource(R.string.extinguisher_pull_prompt),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = drill::pullPin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_pull_pin))
                }
            }

            ExtinguisherStep.AIM_BASE -> {
                Text(
                    text = stringResource(R.string.extinguisher_aim_prompt),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { drill.aim(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_aim_base))
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { drill.aim(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_aim_flames))
                }
            }

            ExtinguisherStep.SQUEEZE_LEVER -> {
                Text(
                    text = stringResource(R.string.extinguisher_squeeze_prompt),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = drill::squeezeLever,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_squeeze))
                }
            }

            ExtinguisherStep.SWEEP_NOZZLE -> {
                Text(
                    text = stringResource(R.string.extinguisher_sweep_prompt, drill.sweepProgress, drill.totalSweepTarget),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = drill::doSweepPass,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.extinguisher_action_sweep_pass))
                }
            }

            else -> {}
        }
    }
}

@Composable
private fun drillPrompt(drill: ExtinguisherTrainingState, manager: ARSessionManager): String {
    arTrackingFailureMessage(manager.trackingFailure)?.let { return it }
    if (!manager.hasTrackedPlane) return stringResource(R.string.ar_hint_find_plane)

    return when (drill.step) {
        ExtinguisherStep.PLACING_EXTINGUISHER -> stringResource(R.string.extinguisher_place_prompt)
        ExtinguisherStep.INSPECT_GAUGE -> stringResource(R.string.extinguisher_step_inspect)
        ExtinguisherStep.PULL_PIN -> stringResource(R.string.extinguisher_step_pull)
        ExtinguisherStep.AIM_BASE -> stringResource(R.string.extinguisher_step_aim)
        ExtinguisherStep.SQUEEZE_LEVER -> stringResource(R.string.extinguisher_step_squeeze)
        ExtinguisherStep.SWEEP_NOZZLE -> stringResource(R.string.extinguisher_step_sweep)
        ExtinguisherStep.BRIEFING, ExtinguisherStep.COMPLETE -> stringResource(R.string.fire_module_prompt_wait)
    }
}

@Composable
private fun elapsedLabel(drill: ExtinguisherTrainingState): String {
    val startedAt = drill.startedAtMillis
    val running = drill.step != ExtinguisherStep.COMPLETE
    var seconds by remember { mutableStateOf(0) }

    LaunchedEffect(startedAt, running) {
        if (startedAt == null) {
            seconds = 0
            return@LaunchedEffect
        }
        while (true) {
            seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
            if (!running) break
            delay(timeMillis = 1000L)
        }
    }

    return stringResource(R.string.fire_module_elapsed, seconds / 60, seconds % 60)
}

@Composable
private fun ExtinguisherFeedbackOverlay(
    feedback: ExtinguisherFeedback?,
    onDismiss: () -> Unit,
) {
    if (feedback == null) return

    var appeared by remember(feedback) { mutableStateOf(false) }
    LaunchedEffect(feedback) { appeared = true }

    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "feedbackScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        label = "feedbackAlpha",
    )

    val colors = MaterialTheme.colorScheme
    val container = when (feedback.tone) {
        ExtinguisherFeedback.Tone.CORRECT -> colors.primaryContainer
        ExtinguisherFeedback.Tone.WRONG -> colors.errorContainer
        ExtinguisherFeedback.Tone.HINT -> colors.surfaceVariant
    }
    val onContainer = when (feedback.tone) {
        ExtinguisherFeedback.Tone.CORRECT -> colors.onPrimaryContainer
        ExtinguisherFeedback.Tone.WRONG -> colors.onErrorContainer
        ExtinguisherFeedback.Tone.HINT -> colors.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
                color = container,
                contentColor = onContainer,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(feedback.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(feedback.bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onDismiss) {
                        Text(text = stringResource(R.string.fire_module_feedback_continue))
                    }
                }
            }
        }
    }
}
