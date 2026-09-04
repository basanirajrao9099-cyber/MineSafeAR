package com.minesafear.ui.modules.fire

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.minesafear.ar.ArObjectAdjustmentPanel
import com.minesafear.ar.ArPlacement
import com.minesafear.ar.ArScene
import com.minesafear.ar.TrackingSourceFactory
import com.minesafear.ar.fallback.FallbackArView
import com.minesafear.ar.fallback.MarkerTracker
import com.minesafear.ar.ArStatusSurface
import com.minesafear.ar.arTrackingFailureMessage
import com.minesafear.ar.rememberArCoreStatus
import com.minesafear.ar.rememberArSessionManager
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.narration.NarrationSlot
import com.minesafear.narration.rememberBriefingNarration
import com.minesafear.sync.SyncScheduler
import kotlinx.coroutines.delay

private const val TAG = "FireModuleScreen"

/**
 * The "Fire & Explosion Response" AR drill.
 *
 * ## Shape of the drill
 *
 * 1. **Briefing** — what is burning and what the trainee has to do. The AR session
 *    is already running behind the overlay, so plane detection has finished by the
 *    time they finish reading.
 * 2. **Place the scene** — an exit sign by the real door, then three extinguishers
 *    on three separate patches of floor. The trainee is *not* told which
 *    extinguisher is which; they arrive in a random order and must be identified by
 *    body colour, which is the skill being taught.
 * 3. **Choose an extinguisher** — the first scored decision. A wrong bottle gets an
 *    explanation and is removed from the scene; a right one clears the others away.
 * 4. **Place escape routes** — three arrows, anywhere.
 * 5. **Choose a route** — the second scored decision. The correct arrow is the one
 *    nearest the exit sign, because in smoke you follow the signage. Decoys are
 *    removed as they are eliminated.
 * 6. **Results** — saved to Room, then handed to the results screen.
 *
 * ## Which object did they tap?
 *
 * ARCore hit tests hit trackables, not rendered models, so selection goes through
 * [ARSessionManager.placementNear], which resolves a tap to the nearest anchor
 * along the tap ray. [ARSessionManager.isCrowded] refuses placements close enough
 * together to make that ambiguous. The full reasoning is on those two functions.
 *
 * @param onExit leave without a score.
 * @param onComplete the drill finished; navigate to the results screen.
 */
@Composable
fun FireModuleScreen(
    onExit: () -> Unit,
    onComplete: (FireDrillOutcome) -> Unit,
    modifier: Modifier = Modifier,
    scenario: FireScenario = FireScenarios.ELECTRICAL_SWITCHGEAR,
) {
    val drill = rememberFireDrillState(scenario)
    val manager = rememberArSessionManager()
    val cues = rememberDrillCues()

    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }

    // Asked once per screen. Never blocks the drill on an inconclusive answer, which
    // offline is the usual one — see ArCoreAvailability. cameraGranted enables the
    // second-stage session probe, which is what catches handsets that report
    // SUPPORTED_INSTALLED and then fail to create a session.
    val arCore = rememberArCoreStatus(cameraGranted = manager.permission.isGranted)

    // A trainee holding the phone up to a wall is not touching the screen, and a
    // drill that dims out halfway through is a drill they start again.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Set once, not per step: the lambda reads the current step when it fires, so
    // there is nothing to re-key. The manager releases its own references on
    // dispose, so there is nothing to undo here either.
    DisposableEffect(manager, drill, cues) {
        manager.onPlaneTap = { hit -> handlePlaneTap(hit, manager, drill, cues) }
        onDispose { }
    }

    LaunchedEffect(drill.step) {
        if (drill.step != FireDrillStep.COMPLETE) return@LaunchedEffect
        val outcome = drill.toOutcome()
        // Room's suspend DAOs dispatch to their own executor, so this is safe from
        // the main dispatcher.
        runCatching {
            repository.saveModuleResult(drill.toResult(TrainingRepository.UNPROVISIONED_USER_ID))
        }.onSuccess {
            // Queued here rather than inside the repository, which has no Context.
            // Safe and cheap offline: the request waits on its network constraint
            // and fires the moment the phone finds signal, which is the difference
            // between this result reaching the server today and waiting for the
            // next six-hourly run.
            SyncScheduler.requestSyncNow(context)
        }.onFailure { error ->
            // Show the score anyway. Someone who has just run the drill is owed
            // their result; a row that failed to write is a problem for sync, not
            // for them.
            Log.e(TAG, "Failed to save fire module result", error)
        }
        onComplete(outcome)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Not composed when AR is impossible. ARSceneView creates its ARCore session
        // on entering composition, so a gate drawn on top does not prevent the
        // failure — it just hides a black rectangle behind itself.
        if (!arCore.blocksAr) {
            ArScene(manager = manager, modifier = Modifier.fillMaxSize())
        } else if (manager.permission.isGranted) {
            // ARCore refuses this handset: marker 6-DoF if OpenCV loaded, else gyro 3-DoF.
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
            // Ordered before the permission gate on purpose: on a phone that cannot
            // run AR at all, asking for the camera first would collect a permission
            // and then refuse anyway.
            !manager.permission.isGranted -> ArCameraPermissionGate(
                permission = manager.permission,
                title = stringResource(scenario.titleRes),
                onDismiss = onExit,
            )

            drill.step == FireDrillStep.BRIEFING -> FireBriefingOverlay(
                scenario = scenario,
                onStart = drill::beginDrill,
                onExit = onExit,
            )

            else -> FireDrillOverlay(
                drill = drill,
                manager = manager,
                onExit = onExit,
            )
        }

        FireFeedbackOverlay(
            feedback = drill.feedback,
            onDismiss = drill::dismissFeedback,
        )
    }
}

// --- Tap handling --------------------------------------------------------

/**
 * Routes a confirmed tap on a horizontal plane to whatever the current step means
 * by it. Not a composable: this runs from the AR render loop, not from composition.
 */
private fun handlePlaneTap(
    hit: HitResult,
    manager: ARSessionManager,
    drill: FireDrillState,
    cues: DrillCues,
) {
    // The feedback card does not cover the whole screen, so a tap beside it still
    // reaches the scene. Without this, acknowledging one mistake could anchor an
    // object the drill never counted.
    if (drill.isAwaitingAcknowledgement) return

    when {
        drill.step.isPlacing -> {
            val item = drill.nextToPlace ?: return
            if (manager.isCrowded(hit)) {
                drill.hint(R.string.fire_module_hint_too_close)
                cues.hint()
                return
            }
            // A null return means ARCore refused the anchor — tracking dropped
            // between the hit test and now. Say nothing; the trainee taps again.
            manager.placeObjectAt(
                hitResult = hit,
                modelRes = item.modelRes,
                scaleToUnits = item.scaleToUnits,
                tag = item,
            ) ?: return
            cues.placed()
            drill.onPlaced()
        }

        drill.step == FireDrillStep.CHOOSING_EXTINGUISHER ->
            chooseExtinguisher(manager, drill, cues, manager.placementNear(hit))

        drill.step == FireDrillStep.CHOOSING_ROUTE ->
            chooseRoute(manager, drill, cues, manager.placementNear(hit))
    }
}

private fun chooseExtinguisher(
    manager: ARSessionManager,
    drill: FireDrillState,
    cues: DrillCues,
    picked: ArPlacement?,
) {
    val target = picked?.tag
    if (target !is FireDrillObject.Extinguisher) {
        // Tapping the exit sign is the right instinct at the wrong moment, so it
        // gets its own wording rather than a generic miss.
        drill.hint(
            if (target is FireDrillObject.ExitSign) R.string.fire_module_hint_exit_sign_later
            else R.string.fire_module_hint_tap_extinguisher
        )
        cues.hint()
        return
    }

    if (drill.onExtinguisherChosen(target.type)) {
        cues.correct()
        // The chosen bottle stays as a record of the right answer; the rest go, so
        // the route step is not cluttered with objects that can no longer be tapped.
        manager.placements
            .filter { it !== picked && it.tag is FireDrillObject.Extinguisher }
            .forEach(manager::remove)
    } else {
        cues.wrong()
        // Eliminating it makes the mistake visible and stops the same wrong answer
        // being tapped twice.
        manager.remove(picked)
    }
}

private fun chooseRoute(
    manager: ARSessionManager,
    drill: FireDrillState,
    cues: DrillCues,
    picked: ArPlacement?,
) {
    if (picked == null || picked.tag !is FireDrillObject.Route) {
        drill.hint(R.string.fire_module_hint_tap_route)
        cues.hint()
        return
    }

    val correct = isSignedRoute(manager, picked)
    drill.onRouteChosen(correct)
    if (correct) {
        cues.correct()
    } else {
        cues.wrong()
        manager.remove(picked)
    }
}

/**
 * True if [route] is the arrow closest to the exit sign — the signed way out.
 *
 * Deciding correctness by geometry rather than by picking a winner at random is
 * what makes the step teachable. The trainee chose where all four objects went, so
 * "which of these leads to the exit" has an answer they can reason about from the
 * scene in front of them, and the lesson is the real one: follow the signage.
 *
 * Removing an eliminated decoy never changes the answer, because the nearest arrow
 * is only ever removed by being chosen correctly.
 *
 * Returns true if the sign's anchor has stopped tracking and the distances cannot
 * be measured. A trainee should not fail a drill because ARCore lost a plane.
 */
private fun isSignedRoute(manager: ARSessionManager, route: ArPlacement): Boolean {
    val sign = manager.placements.firstOrNull { it.tag is FireDrillObject.ExitSign }
        ?: return true

    var nearest: ArPlacement? = null
    var nearestDistance = Float.MAX_VALUE
    manager.placements.forEach { candidate ->
        if (candidate.tag !is FireDrillObject.Route) return@forEach
        val distance = manager.distanceBetweenPlacements(candidate, sign) ?: return@forEach
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearest = candidate
        }
    }

    return nearest == null || nearest === route
}

// --- Overlays -----------------------------------------------------------

/**
 * The instructional overlay, per requirement 1: what is burning, what to do, and an
 * optional spoken version.
 *
 * Full-screen and opaque enough to read, which also means it absorbs touches — the
 * scene behind it cannot be tapped while the briefing is up. The AR session is
 * still running underneath, deliberately: by the time anyone finishes reading,
 * ARCore has found the floor.
 */
@Composable
private fun FireBriefingOverlay(
    scenario: FireScenario,
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
                text = stringResource(R.string.fire_module_briefing_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(scenario.titleRes),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(scenario.fireDescriptionRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))

            scenario.briefingRes.forEach { line ->
                Text(
                    text = stringResource(line),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // The audio stub: a real control over an absent recording, so the
            // missing piece is a file rather than a feature. Availability is
            // per-language — see NarrationCatalogue.
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

/** Chrome over the live camera: what to do next, how long it has taken, and a way out. */
@Composable
private fun FireDrillOverlay(
    drill: FireDrillState,
    manager: ARSessionManager,
    onExit: () -> Unit,
) {
    var confirmingExit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        ArStatusSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // What is burning stays on screen for the whole drill: the answer
                // depends on it, and a trainee who has forgotten it is guessing.
                Text(
                    text = stringResource(drill.scenario.fireDescriptionRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = drillPrompt(drill = drill, manager = manager),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (drill.step.isPlacing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.fire_module_place_progress,
                            drill.placedInStep,
                            drill.totalToPlaceInStep,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = elapsedLabel(drill),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.fire_module_wrong_count, drill.wrongChoices),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { confirmingExit = true }) {
                    Text(text = stringResource(R.string.fire_module_quit))
                }
            }
        }
    }

    if (confirmingExit) {
        // A scored, timed attempt is worth one tap of protection.
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

/**
 * The one line of guidance for the current step.
 *
 * Tracking trouble outranks everything: there is no point asking someone to tap an
 * extinguisher that cannot be drawn because the room is too dark.
 */
@Composable
private fun drillPrompt(drill: FireDrillState, manager: ARSessionManager): String {
    arTrackingFailureMessage(manager.trackingFailure)?.let { return it }
    if (!manager.hasTrackedPlane) return stringResource(R.string.ar_hint_find_plane)

    return when (drill.step) {
        // The null branch is a snapshot race that should not be reachable: a step
        // only becomes `isPlacing` with a non-empty queue, and onPlaced() advances
        // the step in the same write as emptying it. Neutral copy rather than a
        // blank line, so a missed case reads as "wait" and not as a broken screen.
        FireDrillStep.PLACING_SCENE, FireDrillStep.PLACING_ROUTES ->
            drill.nextToPlace?.let { stringResource(it.promptRes) }
                ?: stringResource(R.string.fire_module_prompt_wait)

        FireDrillStep.CHOOSING_EXTINGUISHER ->
            stringResource(R.string.fire_module_choose_extinguisher_prompt)

        FireDrillStep.CHOOSING_ROUTE ->
            stringResource(R.string.fire_module_choose_route_prompt)

        // Neither step renders this overlay: BRIEFING shows the briefing card and
        // COMPLETE has already navigated away.
        FireDrillStep.BRIEFING, FireDrillStep.COMPLETE ->
            stringResource(R.string.fire_module_prompt_wait)
    }
}

/**
 * `mm:ss` since the briefing was dismissed, ticking once a second.
 *
 * The tick lives here rather than in [FireDrillState] so that the holder never
 * exposes a value that changes without notifying composition — see its
 * `startedAtMillis`. Showing the clock is deliberate: time pressure is part of what
 * makes a drill a drill, even though [FireDrillScoring] does not grade it.
 */
@Composable
private fun elapsedLabel(drill: FireDrillState): String {
    val startedAt = drill.startedAtMillis
    val running = drill.step != FireDrillStep.COMPLETE
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

/**
 * The "why this is wrong" overlay from requirement 3, and the success cue from it
 * too — a bounce in, a colour, a tone and a buzz.
 */
@Composable
private fun FireFeedbackOverlay(
    feedback: FireDrillFeedback?,
    onDismiss: () -> Unit,
) {
    if (feedback == null) return

    // Animate from a new instance each time, so a second wrong answer bounces
    // again rather than sitting still.
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
        FireDrillFeedback.Tone.CORRECT -> colors.primaryContainer
        FireDrillFeedback.Tone.WRONG -> colors.errorContainer
        FireDrillFeedback.Tone.HINT -> colors.surfaceVariant
    }
    val onContainer = when (feedback.tone) {
        FireDrillFeedback.Tone.CORRECT -> colors.onPrimaryContainer
        FireDrillFeedback.Tone.WRONG -> colors.onErrorContainer
        FireDrillFeedback.Tone.HINT -> colors.onSurfaceVariant
    }

    // A scrim rather than a bare Box: it dims the camera feed so the explanation is
    // readable, and Surface absorbs touches, so the scene behind cannot be tapped
    // while the trainee is being told what they got wrong.
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
