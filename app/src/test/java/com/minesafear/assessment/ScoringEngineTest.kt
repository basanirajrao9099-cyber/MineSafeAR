package com.minesafear.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    @Test
    fun `all correct passes with 100 percent`() {
        val submission = submissionOf(
            "q1" to "a",
            "q2" to "a",
            "q3" to "a",
            "q4" to "a",
            "q5" to "a",
        )

        val score = ScoringEngine.score(QUESTIONS, submission)

        assertEquals(5, score.correctAnswers)
        assertEquals(100, score.scorePercent)
        assertTrue(score.passed)
    }

    @Test
    fun `four of five is exactly the pass threshold`() {
        val submission = submissionOf(
            "q1" to "a",
            "q2" to "a",
            "q3" to "a",
            "q4" to "a",
            "q5" to "b",
        )

        val score = ScoringEngine.score(QUESTIONS, submission)

        assertEquals(ScoringEngine.PASS_THRESHOLD_PERCENT, score.scorePercent)
        assertTrue(score.passed)
    }

    @Test
    fun `unanswered questions count as wrong`() {
        val submission = submissionOf("q1" to "a", "q2" to "a", "q3" to "a")

        val score = ScoringEngine.score(QUESTIONS, submission)

        assertEquals(3, score.correctAnswers)
        assertEquals(60, score.scorePercent)
        assertFalse(score.passed)
    }

    @Test
    fun `empty question list does not pass and does not divide by zero`() {
        val score = ScoringEngine.score(emptyList(), submissionOf())

        assertEquals(0, score.totalQuestions)
        assertEquals(0, score.scorePercent)
        assertFalse(score.passed)
    }

    @Test
    fun `missed hazard tags are reported once each`() {
        val submission = submissionOf("q1" to "b", "q2" to "b", "q3" to "a", "q4" to "a", "q5" to "a")

        val missed = ScoringEngine.missedHazardTags(QUESTIONS, submission)

        assertEquals(listOf("ppe"), missed)
    }

    private fun submissionOf(vararg answers: Pair<String, String>) = AssessmentSubmission(
        workerId = "worker-1",
        moduleId = "module-1",
        answers = answers.toMap(),
        durationSeconds = 120,
    )

    private companion object {
        /** q1 and q2 share a hazard tag so the dedupe in `missedHazardTags` is covered. */
        val QUESTIONS = listOf(
            question("q1", "ppe"),
            question("q2", "ppe"),
            question("q3", "gas"),
            question("q4", null),
            question("q5", null),
        )

        fun question(id: String, hazardTag: String?) = AssessmentQuestion(
            id = id,
            prompt = "Prompt for $id",
            options = listOf(AnswerOption("a", "Correct"), AnswerOption("b", "Wrong")),
            correctOptionId = "a",
            hazardTag = hazardTag,
        )
    }
}
