package com.minesafear.ui.modules.fire

import android.os.SystemClock
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.minesafear.R
import com.minesafear.ar.ArModels
import com.minesafear.ar.ArPlacement
import com.minesafear.data.entity.ModuleResultEntity
import java.util.UUID

/** Where the trainee is in the drill. */
enum class FireDrillStep {
    /** Reading (or listening to) the scenario. The clock is not running yet. */
    BRIEFING,

    /** Tapping out the exit sign and the three extinguishers. */
    PLACING_SCENE,

    /** The first scored decision. */
    CHOOSING_EXTINGUISHER,

    /** Tapping out candidate escape routes. */
    PLACING_ROUTES,

    /** The second scored decision. */
    CHOOSING_ROUTE,

    /** Scored and saved; the results screen takes over. */
    COMPLETE,
    ;

    /** True while taps place objects rather than select them. */
    val isPlacing: Boolean get() = this == PLACING_SCENE || this == PLACING_ROUTES

    /** True while taps select an object rather than place one. */
    val isChoosing: Boolean get() = this == CHOOSING_EXTINGUISHER || this == CHOOSING_ROUTE
}

/**
 * What a model in the scene represents. Stored in [ArPlacement.tag], which is how a
 * tap comes back as "the trainee reached for the water extinguisher".
 *
 * Each variant carries its own model and scale so that placing the next item is a
 * single call with no `when` over types at the call site.
 *
 * [promptRes] and [labelRes] are deliberately different strings. The prompt is what
 * the trainee is told *before* placing, and for extinguishers it must not name the
 * type — identifying a CO2 bottle by its black body is the skill being taught, and
 * "now place the CO2 extinguisher" hands them the answer. [labelRes] is the reveal,
 * used only in feedback and results.
 */
sealed class FireDrillObject(
    @RawRes val modelRes: Int,
    val scaleToUnits: Float,
    @StringRes val promptRes: Int,
    @StringRes val labelRes: Int,
) {
    /** One of the three bottles the trainee must choose between. */
    class Extinguisher(val type: ExtinguisherType) : FireDrillObject(
        modelRes = type.modelRes,
        scaleToUnits = ArModels.EXTINGUISHER_SCALE_METRES,
        promptRes = R.string.fire_module_place_extinguisher,
        labelRes = type.labelRes,
    )

    /**
     * The signed exit. Placed first, and not just decoration: it is the clue for the
     * route step, where the correct arrow is the one nearest to it.
     */
    data object ExitSign : FireDrillObject(
        modelRes = ArModels.EXIT_SIGN,
        scaleToUnits = ArModels.EXIT_SIGN_SCALE_METRES,
        promptRes = R.string.fire_module_place_exit_sign,
        labelRes = R.string.fire_module_item_exit_sign,
    )

    /** One candidate escape route. [number] is 1-based, for legible feedback. */
    class Route(val number: Int) : FireDrillObject(
        modelRes = ArModels.EXIT_ARROW,
        scaleToUnits = ArModels.EXIT_ARROW_SCALE_METRES,
        promptRes = R.string.fire_module_place_route,
        labelRes = R.string.fire_module_item_exit_arrow,
    )
}

/** A short overlay explaining what just happened. */
@Immutable
class FireDrillFeedback(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val tone: Tone,
) {
    enum class Tone { CORRECT, WRONG, HINT }
}

/**
 * The drill's state machine: what step we are on, what has gone right and wrong,
 * and how long it took.
 *
 * A plain Compose state holder rather than a `ViewModel`, matching
 * [com.minesafear.ar.ARSessionManager] — this project has no DI or ViewModel
 * infrastructure yet, and introducing it for one screen would be the wrong place to
 * start. The clocks are constructor-injected, so everything here is unit testable
 * without an Android runtime; nothing else in the class touches the framework.
 *
 * ## Blocking on feedback
 *
 * Every handler returns early while [feedback] is showing. Without that, a fast
 * double-tap could score twice against the same decision, or place an object into
 * a step the trainee has not seen yet. Making the holder itself refuse means no
 * screen can forget to.
 */
@Stable
class FireDrillState internal constructor(
    val scenario: FireScenario,
    /** Monotonic; immune to the user changing the clock mid-drill. */
    private val elapsedRealtimeMillis: () -> Long,
    /** Wall clock, for the stored timestamp only. */
    private val wallClockMillis: () -> Long,
) {

    var step: FireDrillStep by mutableStateOf(FireDrillStep.BRIEFING)
        private set

    /** The overlay currently demanding acknowledgement, or `null`. */
    var feedback: FireDrillFeedback? by mutableStateOf(null)
        private set

    /**
     * Monotonic reading from when the briefing was dismissed, or `null` before it.
     * Exposed so a screen can render a live timer from its own tick — this class
     * does not expose a time that changes without notifying composition.
     */
    var startedAtMillis: Long? by mutableStateOf(null)
        private set

    private var finishedAtMillis: Long? by mutableStateOf(null)

    var correctChoices: Int by mutableStateOf(0)
        private set

    var wrongExtinguisherChoices: Int by mutableStateOf(0)
        private set

    var wrongRouteChoices: Int by mutableStateOf(0)
        private set

    /** Items still to place in the current placement step, in order. */
    private var queue: List<FireDrillObject> by mutableStateOf(emptyList())

    private var queueSize: Int by mutableStateOf(0)

    /** What the next placing tap drops, or `null` outside a placement step. */
    val nextToPlace: FireDrillObject? get() = queue.firstOrNull()

    val placedInStep: Int get() = queueSize - queue.size

    val totalToPlaceInStep: Int get() = queueSize

    val wrongChoices: Int get() = wrongExtinguisherChoices + wrongRouteChoices

    val score: Int
        get() = FireDrillScoring.totalScore(wrongExtinguisherChoices, wrongRouteChoices)

    val passed: Boolean get() = FireDrillScoring.passed(score)

    /** Briefing to last decision, in whole seconds. Zero until the drill finishes. */
    val durationSeconds: Int
        get() {
            val start = startedAtMillis ?: return 0
            val end = finishedAtMillis ?: return 0
            return ((end - start) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)
        }

    private val isBlocked: Boolean get() = feedback != null

    /**
     * True while an overlay is waiting to be acknowledged.
     *
     * Callers must check this before acting on a tap. Every handler here already
     * refuses, but the AR scene is still live behind a feedback card, and a tap that
     * lands outside it would otherwise anchor an object the drill has not counted.
     */
    val isAwaitingAcknowledgement: Boolean get() = isBlocked

    // --- Transitions -----------------------------------------------------

    /** Dismisses the briefing and starts the clock. */
    fun beginDrill() {
        if (step != FireDrillStep.BRIEFING) return
        startedAtMillis = elapsedRealtimeMillis()
        enterPlacement(
            next = FireDrillStep.PLACING_SCENE,
            items = buildList {
                add(FireDrillObject.ExitSign)
                scenario.extinguisherOrder().forEach { add(FireDrillObject.Extinguisher(it)) }
            },
        )
    }

    /**
     * Call once the object returned by [nextToPlace] has actually been anchored.
     * Advances to the choosing step when the queue empties.
     */
    fun onPlaced() {
        if (isBlocked || !step.isPlacing) return
        queue = queue.drop(1)
        if (queue.isNotEmpty()) return
        step = when (step) {
            FireDrillStep.PLACING_SCENE -> FireDrillStep.CHOOSING_EXTINGUISHER
            FireDrillStep.PLACING_ROUTES -> FireDrillStep.CHOOSING_ROUTE
            else -> step
        }
    }

    /**
     * Records the extinguisher the trainee reached for. Returns true if it was the
     * right one, which is the screen's cue to keep it and clear the others away.
     */
    fun onExtinguisherChosen(type: ExtinguisherType): Boolean {
        if (isBlocked || step != FireDrillStep.CHOOSING_EXTINGUISHER) return false

        if (type == scenario.correctExtinguisher) {
            correctChoices++
            feedback = FireDrillFeedback(
                titleRes = R.string.fire_module_feedback_correct_title,
                bodyRes = R.string.fire_module_extinguisher_correct,
                tone = FireDrillFeedback.Tone.CORRECT,
            )
            enterPlacement(
                next = FireDrillStep.PLACING_ROUTES,
                items = List(scenario.routeCount) { FireDrillObject.Route(number = it + 1) },
            )
            return true
        }

        wrongExtinguisherChoices++
        feedback = FireDrillFeedback(
            titleRes = R.string.fire_module_feedback_wrong_title,
            // The scenario guarantees a reason for every wrong type; the fallback
            // only exists so a future scenario with a gap degrades to vague rather
            // than blank.
            bodyRes = scenario.wrongReasonFor(type) ?: R.string.fire_module_wrong_generic,
            tone = FireDrillFeedback.Tone.WRONG,
        )
        return false
    }

    /**
     * Records the escape route the trainee picked. [correct] is decided by the
     * screen, which is the only place that can measure anchor positions.
     *
     * Returns true when the drill is over.
     */
    fun onRouteChosen(correct: Boolean): Boolean {
        if (isBlocked || step != FireDrillStep.CHOOSING_ROUTE) return false

        if (correct) {
            correctChoices++
            finishedAtMillis = elapsedRealtimeMillis()
            step = FireDrillStep.COMPLETE
            return true
        }

        wrongRouteChoices++
        feedback = FireDrillFeedback(
            titleRes = R.string.fire_module_feedback_wrong_title,
            bodyRes = R.string.fire_module_route_wrong,
            tone = FireDrillFeedback.Tone.WRONG,
        )
        return false
    }

    /**
     * Shows an unscored hint — a tap on the right kind of thing at the wrong time,
     * or on nothing at all. Costs nothing: penalising a trainee for exploring the
     * scene would teach them not to look around it.
     */
    fun hint(@StringRes bodyRes: Int) {
        if (isBlocked) return
        feedback = FireDrillFeedback(
            titleRes = R.string.fire_module_feedback_hint_title,
            bodyRes = bodyRes,
            tone = FireDrillFeedback.Tone.HINT,
        )
    }

    fun dismissFeedback() {
        feedback = null
    }

    // --- Result ----------------------------------------------------------

    /** The row to persist. Only meaningful once [step] is [FireDrillStep.COMPLETE]. */
    fun toResult(userId: String): ModuleResultEntity = ModuleResultEntity(
        id = UUID.randomUUID().toString(),
        moduleId = scenario.moduleId,
        userId = userId,
        score = score,
        timestamp = wallClockMillis(),
        passed = passed,
        durationSeconds = durationSeconds,
        correctTaps = correctChoices,
        incorrectTaps = wrongChoices,
    )

    /** The same numbers, shaped for the results route's arguments. */
    fun toOutcome(): FireDrillOutcome = FireDrillOutcome(
        score = score,
        passed = passed,
        durationSeconds = durationSeconds,
        correctChoices = correctChoices,
        wrongChoices = wrongChoices,
    )

    private fun enterPlacement(next: FireDrillStep, items: List<FireDrillObject>) {
        step = next
        queue = items
        queueSize = items.size
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * Creates a drill scoped to the current composition.
 *
 * Not saved across process death: the drill's state is only meaningful alongside
 * the ARCore anchors it refers to, and those cannot be restored (see
 * [com.minesafear.ar.rememberArSessionManager]). Restoring a half-finished drill
 * into an empty room would be worse than restarting it. Rotation is handled in the
 * manifest instead, by not recreating the activity.
 */
@Composable
fun rememberFireDrillState(
    scenario: FireScenario = FireScenarios.ELECTRICAL_SWITCHGEAR,
): FireDrillState = remember(scenario) {
    FireDrillState(
        scenario = scenario,
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        wallClockMillis = System::currentTimeMillis,
    )
}
