package com.minesafear.ui.modules.fire

import com.minesafear.assessment.ScoringEngine

/**
 * Turns a drill's mistakes into the 0–100 score stored on `module_results.score`.
 *
 * Pure and free of Android types on purpose — this is the part of the module that
 * is worth unit testing, and the part a safety officer will want to argue about.
 *
 * ## The curve
 *
 * Two decisions, worth [POINTS_PER_STEP] each: which extinguisher, and which way
 * out. Every wrong pick inside a step costs [PENALTY_PER_WRONG_CHOICE], and a step
 * floors at zero rather than dragging the other step down with it.
 *
 * | Wrong picks | Score | Result |
 * |---|---|---|
 * | 0 | 100 | pass |
 * | 1 | 90 | pass |
 * | 2 | 80 | pass, exactly on the line |
 * | 3 | 70 | fail |
 *
 * The drill offers two wrong extinguishers and two decoy routes and removes each
 * one as it is eliminated, so the worst reachable score is 60 — a trainee who taps
 * everything still finishes, and still fails. That is deliberate: a drill you can
 * brute-force teaches nothing, and one you can fail forever teaches less.
 *
 * Time taken is recorded but not scored. Rewarding speed in a fire drill trains
 * exactly the wrong reflex; the number is there for a trainer to read, not for the
 * app to grade.
 */
object FireDrillScoring {

    const val MAX_SCORE: Int = 100

    /** There are two scored decisions, so each is worth half the module. */
    const val POINTS_PER_STEP: Int = MAX_SCORE / 2

    const val PENALTY_PER_WRONG_CHOICE: Int = 10

    /** Same bar as the written assessments — safety training is pass/fail at 80%. */
    const val PASS_MARK: Int = ScoringEngine.PASS_THRESHOLD_PERCENT

    /** One step's contribution, never negative. */
    fun stepScore(wrongChoices: Int): Int =
        (POINTS_PER_STEP - wrongChoices.coerceAtLeast(0) * PENALTY_PER_WRONG_CHOICE)
            .coerceAtLeast(0)

    fun totalScore(wrongExtinguisherChoices: Int, wrongRouteChoices: Int): Int =
        stepScore(wrongExtinguisherChoices) + stepScore(wrongRouteChoices)

    fun passed(score: Int): Boolean = score >= PASS_MARK
}
