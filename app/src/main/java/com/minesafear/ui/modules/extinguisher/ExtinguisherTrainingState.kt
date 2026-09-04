package com.minesafear.ui.modules.extinguisher

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.minesafear.R
import com.minesafear.ar.ArModels
import com.minesafear.data.entity.ModuleResultEntity
import java.util.UUID

/** The steps in the Extinguisher PASS Method training flow. */
enum class ExtinguisherStep {
    /** Briefing overlay: overview of PASS procedure. */
    BRIEFING,

    /** Tap floor to position the 3D realistic extinguisher. */
    PLACING_EXTINGUISHER,

    /** Step 1: Pre-use inspection (check gauge in green zone). */
    INSPECT_GAUGE,

    /** Step 2 (P): Pull the safety pin. */
    PULL_PIN,

    /** Step 3 (A): Aim nozzle low at base of fire. */
    AIM_BASE,

    /** Step 4 (S): Squeeze operating lever. */
    SQUEEZE_LEVER,

    /** Step 5 (S): Sweep nozzle side to side across the base. */
    SWEEP_NOZZLE,

    /** All steps complete. */
    COMPLETE,
}

/** Feedback shown on step actions. */
data class ExtinguisherFeedback(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val tone: Tone,
) {
    enum class Tone { CORRECT, WRONG, HINT }
}

class ExtinguisherTrainingState {
    var step by mutableStateOf(ExtinguisherStep.BRIEFING)
        private set

    var feedback by mutableStateOf<ExtinguisherFeedback?>(null)
        private set

    var startedAtMillis by mutableStateOf<Long?>(null)
        private set

    var correctActions by mutableIntStateOf(0)
        private set

    var wrongActions by mutableIntStateOf(0)
        private set

    var pinPulled by mutableStateOf(false)
        private set

    var aimedAtBase by mutableStateOf(false)
        private set

    var squeezed by mutableStateOf(false)
        private set

    var sweepProgress by mutableIntStateOf(0)
        private set

    val totalSweepTarget: Int = 3

    val isAwaitingAcknowledgement: Boolean
        get() = feedback != null

    fun beginDrill() {
        step = ExtinguisherStep.PLACING_EXTINGUISHER
        startedAtMillis = SystemClock.elapsedRealtime()
    }

    fun onExtinguisherPlaced() {
        if (step == ExtinguisherStep.PLACING_EXTINGUISHER) {
            step = ExtinguisherStep.INSPECT_GAUGE
            feedback = ExtinguisherFeedback(
                titleRes = R.string.extinguisher_feedback_inspect_title,
                bodyRes = R.string.extinguisher_feedback_inspect_body,
                tone = ExtinguisherFeedback.Tone.HINT,
            )
        }
    }

    fun inspectGauge(correct: Boolean) {
        if (step != ExtinguisherStep.INSPECT_GAUGE) return
        if (correct) {
            correctActions++
            step = ExtinguisherStep.PULL_PIN
            feedback = ExtinguisherFeedback(
                titleRes = R.string.extinguisher_feedback_gauge_correct_title,
                bodyRes = R.string.extinguisher_feedback_gauge_correct_body,
                tone = ExtinguisherFeedback.Tone.CORRECT,
            )
        } else {
            wrongActions++
            feedback = ExtinguisherFeedback(
                titleRes = R.string.extinguisher_feedback_gauge_wrong_title,
                bodyRes = R.string.extinguisher_feedback_gauge_wrong_body,
                tone = ExtinguisherFeedback.Tone.WRONG,
            )
        }
    }

    fun pullPin() {
        if (step != ExtinguisherStep.PULL_PIN) return
        pinPulled = true
        correctActions++
        step = ExtinguisherStep.AIM_BASE
        feedback = ExtinguisherFeedback(
            titleRes = R.string.extinguisher_feedback_pin_correct_title,
            bodyRes = R.string.extinguisher_feedback_pin_correct_body,
            tone = ExtinguisherFeedback.Tone.CORRECT,
        )
    }

    fun aim(atBase: Boolean) {
        if (step != ExtinguisherStep.AIM_BASE) return
        if (atBase) {
            aimedAtBase = true
            correctActions++
            step = ExtinguisherStep.SQUEEZE_LEVER
            feedback = ExtinguisherFeedback(
                titleRes = R.string.extinguisher_feedback_aim_correct_title,
                bodyRes = R.string.extinguisher_feedback_aim_correct_body,
                tone = ExtinguisherFeedback.Tone.CORRECT,
            )
        } else {
            wrongActions++
            feedback = ExtinguisherFeedback(
                titleRes = R.string.extinguisher_feedback_aim_wrong_title,
                bodyRes = R.string.extinguisher_feedback_aim_wrong_body,
                tone = ExtinguisherFeedback.Tone.WRONG,
            )
        }
    }

    fun squeezeLever() {
        if (step != ExtinguisherStep.SQUEEZE_LEVER) return
        squeezed = true
        correctActions++
        step = ExtinguisherStep.SWEEP_NOZZLE
        feedback = ExtinguisherFeedback(
            titleRes = R.string.extinguisher_feedback_squeeze_correct_title,
            bodyRes = R.string.extinguisher_feedback_squeeze_correct_body,
            tone = ExtinguisherFeedback.Tone.CORRECT,
        )
    }

    fun doSweepPass() {
        if (step != ExtinguisherStep.SWEEP_NOZZLE) return
        sweepProgress++
        if (sweepProgress >= totalSweepTarget) {
            correctActions++
            step = ExtinguisherStep.COMPLETE
            feedback = ExtinguisherFeedback(
                titleRes = R.string.extinguisher_feedback_complete_title,
                bodyRes = R.string.extinguisher_feedback_complete_body,
                tone = ExtinguisherFeedback.Tone.CORRECT,
            )
        }
    }

    fun hint(@StringRes hintRes: Int) {
        feedback = ExtinguisherFeedback(
            titleRes = R.string.fire_module_feedback_hint_title,
            bodyRes = hintRes,
            tone = ExtinguisherFeedback.Tone.HINT,
        )
    }

    fun dismissFeedback() {
        feedback = null
    }

    fun toOutcome(): ExtinguisherOutcome {
        val totalDecisions = correctActions + wrongActions
        val rawScore = if (totalDecisions > 0) {
            ((correctActions.toFloat() / totalDecisions.toFloat()) * 100f).toInt()
        } else 100
        val score = rawScore.coerceIn(0, 100)
        val passed = score >= 70 && wrongActions <= 1

        val duration = startedAtMillis?.let {
            ((SystemClock.elapsedRealtime() - it) / 1000L).toInt()
        } ?: 0

        return ExtinguisherOutcome(
            score = score,
            passed = passed,
            durationSeconds = duration,
            correctChoices = correctActions,
            wrongChoices = wrongActions,
        )
    }

    fun toResult(userId: String): ModuleResultEntity {
        val outcome = toOutcome()
        return ModuleResultEntity(
            id = UUID.randomUUID().toString(),
            moduleId = EXTINGUISHER_MODULE_ID,
            userId = userId,
            score = outcome.score,
            timestamp = System.currentTimeMillis(),
            passed = outcome.passed,
            durationSeconds = outcome.durationSeconds,
            correctTaps = outcome.correctChoices,
            incorrectTaps = outcome.wrongChoices,
            pendingSync = true,
        )
    }

    companion object {
        const val EXTINGUISHER_MODULE_ID = "extinguisher_pass_training"
    }
}

@Composable
fun rememberExtinguisherTrainingState(): ExtinguisherTrainingState =
    remember { ExtinguisherTrainingState() }
