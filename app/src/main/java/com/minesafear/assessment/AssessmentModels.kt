package com.minesafear.assessment

/** One selectable answer. [id] is stable so submissions survive question reordering. */
data class AnswerOption(
    val id: String,
    val text: String,
)

/**
 * A single-correct-answer question. Multi-select and ordering questions will need
 * their own types; keeping this one narrow avoids a premature abstraction.
 */
data class AssessmentQuestion(
    val id: String,
    val prompt: String,
    val options: List<AnswerOption>,
    val correctOptionId: String,
    /** Optional hazard category, used later to explain which topics were missed. */
    val hazardTag: String? = null,
)

/**
 * What the UI hands to [ScoringEngine]: the chosen option per question id. A
 * question missing from [answers] counts as unanswered, and therefore wrong.
 */
data class AssessmentSubmission(
    val workerId: String,
    val moduleId: String,
    val answers: Map<String, String>,
    val durationSeconds: Int,
)

/** Result of grading a submission. */
data class AssessmentScore(
    val correctAnswers: Int,
    val totalQuestions: Int,
    val scorePercent: Int,
    val passed: Boolean,
)
