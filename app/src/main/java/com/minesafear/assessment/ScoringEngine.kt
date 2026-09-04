package com.minesafear.assessment

import kotlin.math.roundToInt

/**
 * Grades assessments on-device so a worker underground gets their result — and
 * their certificate — without waiting for connectivity.
 */
object ScoringEngine {

    /** Statutory safety training is typically pass/fail at 80%. */
    const val PASS_THRESHOLD_PERCENT = 80

    fun score(
        questions: List<AssessmentQuestion>,
        submission: AssessmentSubmission,
    ): AssessmentScore {
        if (questions.isEmpty()) {
            return AssessmentScore(
                correctAnswers = 0,
                totalQuestions = 0,
                scorePercent = 0,
                passed = false,
            )
        }

        val correct = questions.count { question ->
            submission.answers[question.id] == question.correctOptionId
        }
        val percent = (correct * 100f / questions.size).roundToInt()

        return AssessmentScore(
            correctAnswers = correct,
            totalQuestions = questions.size,
            scorePercent = percent,
            passed = percent >= PASS_THRESHOLD_PERCENT,
        )
    }

    /** Hazard tags the worker got wrong — the basis for "revisit these" guidance. */
    fun missedHazardTags(
        questions: List<AssessmentQuestion>,
        submission: AssessmentSubmission,
    ): List<String> = questions
        .filter { submission.answers[it.id] != it.correctOptionId }
        .mapNotNull { it.hazardTag }
        .distinct()
}
