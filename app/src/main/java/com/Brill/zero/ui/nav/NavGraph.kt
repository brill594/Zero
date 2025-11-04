@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
package com.brill.zero.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.google.accompanist.navigation.animation.AnimatedNavHost
import com.google.accompanist.navigation.animation.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.brill.zero.ui.screen.DashboardScreen
import com.brill.zero.ui.screen.TodoScreen
import com.brill.zero.ui.screen.HistoryScreen
import com.brill.zero.ui.screen.SettingsScreen
import com.brill.zero.ui.screen.DatasetManageScreen
import com.brill.zero.BuildConfig
import com.brill.zero.debug.DebugScreen
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

@Composable
fun ZeroNavGraph(startDestination: String = "todos") {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold { padding ->
        AnimatedNavHost(navController = nav, startDestination = startDestination, modifier = Modifier.padding(padding)) {
            composable(
                route = "dashboard",
                enterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
            ) {
                DashboardScreen(onNavigate = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(
                route = "todos",
                enterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
            ) {
                TodoScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(
                route = "history",
                enterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
            ) {
                HistoryScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(
                route = "debug",
                enterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
            ) {
                DebugScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(
                route = "settings",
                enterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
            ) {
                SettingsScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }, onOpenDataset = {
                    nav.navigate("dataset") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(
                route = "dataset",
                enterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
            ) {
                DatasetManageScreen(onOpenSettings = {
                    nav.navigate("settings") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        }
    }
}