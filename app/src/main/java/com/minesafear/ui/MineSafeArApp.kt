package com.minesafear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.minesafear.ui.navigation.MineSafeArDestination
import com.minesafear.ui.navigation.addManagementGraph
import com.minesafear.ui.navigation.addSimulationGraph

/**
 * App shell: a bottom navigation bar over a [NavHost] holding the top-level
 * screens, plus the routes that training modules push on top of them.
 *
 * ## Insets
 *
 * The scaffold's padding is applied per screen rather than to the whole [NavHost],
 * because the AR drill must run edge to edge — a camera feed inset from the status
 * bar looks like a rendering bug, and the drill draws its own chrome inside
 * `safeDrawingPadding`.
 */
@Composable
fun MineSafeArApp(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = MineSafeArDestination.fromRoute(currentRoute)

    Scaffold(
        bottomBar = {
            // Hidden for anything pushed above the top-level graph. A nav bar during
            // a scored AR drill is a one-tap way to abandon it by accident, and the
            // drill wants the whole screen anyway.
            if (currentDestination != null) {
                NavigationBar {
                    MineSafeArDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == currentDestination,
                            onClick = {
                                if (destination != currentDestination) {
                                    navController.navigate(destination.route) {
                                        popUpTo(MineSafeArDestination.START.route) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.navLabelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        MineSafeArNavHost(
            navController = navController,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun MineSafeArNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
) {
    val inset = Modifier.padding(contentPadding)

    NavHost(
        navController = navController,
        startDestination = MineSafeArDestination.START.route,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Part 1: AR Simulation & Training Experience (Laptop 1 / Dev A)
        addSimulationGraph(navController, insetModifier = inset)

        // Part 2: Certification, Sync, Storage & Settings (Laptop 2 / Dev B)
        addManagementGraph(navController, insetModifier = inset)
    }
}
