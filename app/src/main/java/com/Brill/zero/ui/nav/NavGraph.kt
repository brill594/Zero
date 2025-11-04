package com.brill.zero.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.brill.zero.ui.screen.DashboardScreen
import com.brill.zero.ui.screen.TodoScreen
import com.brill.zero.ui.screen.HistoryScreen
import com.brill.zero.BuildConfig
import com.brill.zero.debug.DebugScreen

private data class NavItem(val label: String, val route: String)

@Composable
fun ZeroNavGraph(startDestination: String = "todos") {
    val nav = rememberNavController()
    // 底部导航仅保留“仪表盘”，其余页面入口移至仪表盘页面内
    val items = listOf(
        NavItem("仪表盘", "dashboard")
    )

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(item.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFE6E6E6),
                            unselectedIconColor = Color(0xFFE6E6E6),
                            selectedTextColor = Color(0xFFE6E6E6),
                            unselectedTextColor = Color(0xFFE6E6E6),
                            indicatorColor = Color(0xFF333333)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = startDestination, modifier = Modifier.padding(padding)) {
            composable("dashboard") {
                DashboardScreen(onNavigate = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable("todos") {
                TodoScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable("history") {
                HistoryScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable("debug") {
                DebugScreen(onOpenDashboard = {
                    nav.navigate("dashboard") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        }
    }
}