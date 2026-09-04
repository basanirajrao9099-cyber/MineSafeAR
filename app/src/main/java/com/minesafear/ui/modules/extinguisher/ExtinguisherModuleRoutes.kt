package com.minesafear.ui.modules.extinguisher

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument

@Immutable
data class ExtinguisherOutcome(
    val score: Int,
    val passed: Boolean,
    val durationSeconds: Int,
    val correctChoices: Int,
    val wrongChoices: Int,
) {
    companion object {
        fun fromArguments(arguments: Bundle?): ExtinguisherOutcome = ExtinguisherOutcome(
            score = arguments?.getInt(ExtinguisherModuleRoutes.ARG_SCORE) ?: 0,
            passed = arguments?.getBoolean(ExtinguisherModuleRoutes.ARG_PASSED) ?: false,
            durationSeconds = arguments?.getInt(ExtinguisherModuleRoutes.ARG_DURATION) ?: 0,
            correctChoices = arguments?.getInt(ExtinguisherModuleRoutes.ARG_CORRECT) ?: 0,
            wrongChoices = arguments?.getInt(ExtinguisherModuleRoutes.ARG_WRONG) ?: 0,
        )
    }
}

object ExtinguisherModuleRoutes {
    const val TRAINING: String = "extinguisher_module"

    const val ARG_SCORE: String = "score"
    const val ARG_PASSED: String = "passed"
    const val ARG_DURATION: String = "durationSeconds"
    const val ARG_CORRECT: String = "correctChoices"
    const val ARG_WRONG: String = "wrongChoices"

    private const val RESULTS_PREFIX = "extinguisher_module_results"

    const val RESULTS: String =
        "$RESULTS_PREFIX/{$ARG_SCORE}/{$ARG_PASSED}/{$ARG_DURATION}/{$ARG_CORRECT}/{$ARG_WRONG}"

    val resultsArguments: List<NamedNavArgument> = listOf(
        navArgument(ARG_SCORE) { type = NavType.IntType },
        navArgument(ARG_PASSED) { type = NavType.BoolType },
        navArgument(ARG_DURATION) { type = NavType.IntType },
        navArgument(ARG_CORRECT) { type = NavType.IntType },
        navArgument(ARG_WRONG) { type = NavType.IntType },
    )

    fun results(outcome: ExtinguisherOutcome): String = listOf(
        RESULTS_PREFIX,
        outcome.score,
        outcome.passed,
        outcome.durationSeconds,
        outcome.correctChoices,
        outcome.wrongChoices,
    ).joinToString(separator = "/")
}
