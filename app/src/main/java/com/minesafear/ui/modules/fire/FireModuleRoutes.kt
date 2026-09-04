package com.minesafear.ui.modules.fire

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * What the results screen shows.
 *
 * Passed through the back stack as route arguments rather than held in a shared
 * object, so the results survive the drill's own composition being torn down —
 * which it is, the moment we navigate away and the AR session is destroyed.
 */
@Immutable
data class FireDrillOutcome(
    val score: Int,
    val passed: Boolean,
    val durationSeconds: Int,
    val correctChoices: Int,
    val wrongChoices: Int,
) {
    companion object {
        /** Reads an outcome back out of a [androidx.navigation.NavBackStackEntry]. */
        fun fromArguments(arguments: Bundle?): FireDrillOutcome = FireDrillOutcome(
            score = arguments?.getInt(FireModuleRoutes.ARG_SCORE) ?: 0,
            passed = arguments?.getBoolean(FireModuleRoutes.ARG_PASSED) ?: false,
            durationSeconds = arguments?.getInt(FireModuleRoutes.ARG_DURATION) ?: 0,
            correctChoices = arguments?.getInt(FireModuleRoutes.ARG_CORRECT) ?: 0,
            wrongChoices = arguments?.getInt(FireModuleRoutes.ARG_WRONG) ?: 0,
        )
    }
}

/**
 * Routes for the fire module.
 *
 * Not part of [com.minesafear.ui.navigation.MineSafeArDestination], which is
 * specifically the set of destinations the bottom bar switches between. These are
 * pushed on top of it, and the bar hides while they are showing.
 */
object FireModuleRoutes {

    /** The AR drill itself. */
    const val DRILL: String = "fire_module"

    const val ARG_SCORE: String = "score"
    const val ARG_PASSED: String = "passed"
    const val ARG_DURATION: String = "durationSeconds"
    const val ARG_CORRECT: String = "correctChoices"
    const val ARG_WRONG: String = "wrongChoices"

    private const val RESULTS_PREFIX = "fire_module_results"

    const val RESULTS: String =
        "$RESULTS_PREFIX/{$ARG_SCORE}/{$ARG_PASSED}/{$ARG_DURATION}/{$ARG_CORRECT}/{$ARG_WRONG}"

    val resultsArguments: List<NamedNavArgument> = listOf(
        navArgument(ARG_SCORE) { type = NavType.IntType },
        navArgument(ARG_PASSED) { type = NavType.BoolType },
        navArgument(ARG_DURATION) { type = NavType.IntType },
        navArgument(ARG_CORRECT) { type = NavType.IntType },
        navArgument(ARG_WRONG) { type = NavType.IntType },
    )

    fun results(outcome: FireDrillOutcome): String = listOf(
        RESULTS_PREFIX,
        outcome.score,
        outcome.passed,
        outcome.durationSeconds,
        outcome.correctChoices,
        outcome.wrongChoices,
    ).joinToString(separator = "/")
}
