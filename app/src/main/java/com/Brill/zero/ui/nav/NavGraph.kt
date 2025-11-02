package com.Brill.zero.ui.nav


import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.Brill.zero.ui.screen.DashboardScreen
import com.Brill.zero.ui.screen.TodoScreen
import com.Brill.zero.ui.screen.HistoryScreen


@Composable
fun ZeroNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "dashboard") {
        composable("dashboard") { DashboardScreen(onOpenTodos = { nav.navigate("todos") }, onOpenHistory = { nav.navigate("history") }) }
        composable("todos") { TodoScreen() }
        composable("history") { HistoryScreen() }
    }
}