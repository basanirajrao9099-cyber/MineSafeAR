package com.minesafear.ui.navigation

import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.minesafear.ui.modules.TrainingModulesScreen
import com.minesafear.ui.modules.fire.FireDrillOutcome
import com.minesafear.ui.modules.fire.FireModuleResultsScreen
import com.minesafear.ui.modules.fire.FireModuleRoutes
import com.minesafear.ui.modules.fire.FireModuleScreen

/**
 * Navigation destinations owned by Part 1: AR Simulation & Training Experience (Laptop 1 / Dev A).
 */
fun NavGraphBuilder.addSimulationGraph(
    navController: NavHostController,
    insetModifier: Modifier,
) {
    composable(MineSafeArDestination.TRAINING_MODULES.route) {
        TrainingModulesScreen(
            onStartFireModule = { navController.navigate(FireModuleRoutes.DRILL) },
            modifier = insetModifier,
        )
    }

    // --- Fire & Explosion Response ------------------------------------

    composable(FireModuleRoutes.DRILL) {
        // No inset: edge to edge on purpose.
        FireModuleScreen(
            onExit = { navController.popBackStack() },
            onComplete = { outcome ->
                navController.navigate(FireModuleRoutes.results(outcome)) {
                    // Drop the finished drill. Backing into a completed scene
                    // would show a room full of anchors with nothing to score.
                    popUpTo(FireModuleRoutes.DRILL) { inclusive = true }
                }
            },
        )
    }
    composable(
        route = FireModuleRoutes.RESULTS,
        arguments = FireModuleRoutes.resultsArguments,
    ) { entry ->
        FireModuleResultsScreen(
            // Read back from the route, so the score survives the drill's
            // composition — and its AR session — being destroyed.
            outcome = FireDrillOutcome.fromArguments(entry.arguments),
            onRetry = {
                // Replacing the results entry gives the drill a fresh
                // NavBackStackEntry, and so a fresh FireDrillState.
                navController.navigate(FireModuleRoutes.DRILL) {
                    popUpTo(FireModuleRoutes.RESULTS) { inclusive = true }
                }
            },
            onNextModule = {
                navController.navigate(MineSafeArDestination.TRAINING_MODULES.route) {
                    popUpTo(MineSafeArDestination.TRAINING_MODULES.route) {
                        inclusive = true
                    }
                }
            },
            modifier = insetModifier,
        )
    }
}
