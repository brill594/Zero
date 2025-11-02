package com.Brill.zero.ui.screen


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@Composable
fun DashboardScreen(onOpenTodos: () -> Unit, onOpenHistory: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("ZERO", style = MaterialTheme.typography.headlineLarge)
        OutlinedButton(onClick = onOpenTodos, border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)) { Text("To‑Do") }
        OutlinedButton(onClick = onOpenHistory) { Text("历史通知") }
        Text("Nothing‑style · 本地LLM整理通知与待办", style = MaterialTheme.typography.labelLarge)
    }
}