package com.brill.zero.ui.nav


import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.brill.zero.ui.screen.DashboardScreen
import com.brill.zero.ui.screen.TodoScreen
import com.brill.zero.ui.screen.HistoryScreen
import com.brill.zero.BuildConfig
import com.brill.zero.debug.DebugScreen



@Composable
fun ZeroNavGraph(startDestination: String = "dashboard") {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = startDestination) {
        composable("dashboard") { DashboardScreen(onOpenTodos = { nav.navigate("todos") }, onOpenHistory = { nav.navigate("history") },onOpenDebug={nav.navigate("debug")}) }
        composable("todos") { TodoScreen() }
        composable("history") { HistoryScreen() }
        composable("debug") { DebugScreen() }   // ★ 你的调试页

    }
}