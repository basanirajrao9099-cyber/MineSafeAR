package com.minesafear.ui.modules.fire

import com.minesafear.assessment.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scoring curve for the fire drill.
 *
 * Worth testing on its own because it is the only part of the module that can be
 * checked without a camera, a phone, or a plane to anchor to — and because the number
 * it produces is what ends up on a worker's training record.
 *
 * Deliberately hermetic: [FireDrillScoring] is pure arithmetic, so nothing here
 * touches Android, resources, or [FireScenario]. The consequence is that the
 * reachability test below hard-codes the scenario's shape (three extinguishers, three
 * routes) rather than reading it from [FireScenarios]; see its comment.
 */
class FireDrillScoringTest {

    @Test
    fun `a flawless drill scores full marks and passes`() {
        val score = FireDrillScoring.totalScore(wrongExtinguisherChoices = 0, wrongRouteChoices = 0)

        assertEquals(FireDrillScoring.MAX_SCORE, score)
        assertEquals(100, score)
        assertTrue(FireDrillScoring.passed(score))
    }

    @Test
    fun `one wrong choice still passes`() {
        assertEquals(90, FireDrillScoring.totalScore(1, 0))
        assertTrue(FireDrillScoring.passed(90))
    }

    @Test
    fun `two wrong choices land exactly on the pass mark`() {
        val score = FireDrillScoring.totalScore(1, 1)

        assertEquals(FireDrillScoring.PASS_MARK, score)
        assertEquals(80, score)
        // The boundary is inclusive on purpose: 80 is a pass, 79 is not.
        assertTrue(FireDrillScoring.passed(score))
        assertFalse(FireDrillScoring.passed(score - 1))
    }

    @Test
    fun `three wrong choices fail`() {
        assertEquals(70, FireDrillScoring.totalScore(2, 1))
        assertFalse(FireDrillScoring.passed(70))
    }

    @Test
    fun `it does not matter which step the mistakes were made on`() {
        assertEquals(FireDrillScoring.totalScore(2, 0), FireDrillScoring.totalScore(0, 2))
        assertEquals(FireDrillScoring.totalScore(2, 1), FireDrillScoring.totalScore(1, 2))
    }

    @Test
    fun `a step cannot score below zero`() {
        assertEquals(0, FireDrillScoring.stepScore(FireDrillScoring.POINTS_PER_STEP))
        assertEquals(0, FireDrillScoring.stepScore(99))
        assertEquals(0, FireDrillScoring.totalScore(99, 99))
    }

    @Test
    fun `the two steps are worth the same and add up to the maximum`() {
        assertEquals(FireDrillScoring.MAX_SCORE, FireDrillScoring.POINTS_PER_STEP * 2)
        assertEquals(FireDrillScoring.POINTS_PER_STEP, FireDrillScoring.stepScore(0))
    }

    @Test
    fun `the worst score a real attempt can reach is sixty`() {
        // A wrong pick is removed from the scene, so each step can only be got wrong
        // (choices - 1) times before the correct object is the only one left. With
        // three extinguishers and three routes that is two mistakes per step.
        //
        // Hard-coded rather than read from FireScenarios so this test stays free of
        // Android resources. If the scenario grows a fourth route, update both.
        val worst = FireDrillScoring.totalScore(
            wrongExtinguisherChoices = EXTINGUISHER_CHOICES - 1,
            wrongRouteChoices = ROUTE_CHOICES - 1,
        )

        assertEquals(60, worst)
        assertFalse(FireDrillScoring.passed(worst))
        // Nobody can bottom out at zero, so the floor in stepScore is defensive
        // rather than reachable — which is why it is asserted separately above.
        assertTrue(worst > 0)
    }

    @Test
    fun `negative counts are treated as none`() {
        // Nothing should be able to produce a negative count, but the drill feeds
        // these straight from tap handlers and a score above 100 would be worse
        // than a wrong one.
        assertEquals(FireDrillScoring.POINTS_PER_STEP, FireDrillScoring.stepScore(-1))
        assertEquals(FireDrillScoring.MAX_SCORE, FireDrillScoring.totalScore(-3, -1))
    }

    @Test
    fun `the pass mark matches the rest of the app`() {
        // An AR drill and a written assessment should not disagree about what
        // counts as competent.
        assertEquals(ScoringEngine.PASS_THRESHOLD_PERCENT, FireDrillScoring.PASS_MARK)
    }

    private companion object {
        const val EXTINGUISHER_CHOICES = 3
        const val ROUTE_CHOICES = 3
    }
}
