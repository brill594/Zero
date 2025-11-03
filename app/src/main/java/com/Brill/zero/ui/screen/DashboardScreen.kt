package com.brill.zero.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brill.zero.nls.PermissionUtils // [!] 导入我们的工具

@Composable
fun DashboardScreen(onOpenTodos: () -> Unit, onOpenHistory: () -> Unit,    onOpenDebug: () -> Unit
) {

    val context = LocalContext.current

    // [!] V27 修复: 实时检查权限状态
    val hasPermission by produceState(initialValue = false, context) {
        value = PermissionUtils.isNotificationServiceEnabled(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("ZERO", style = MaterialTheme.typography.headlineLarge)

        // [!!!] V27 修复: 权限引导按钮 [!!!]
        if (!hasPermission) {
            Button(
                onClick = { PermissionUtils.openNotificationAccessSettings(context) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    "权限不足",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.background
                )
            }
            Text(
                "Zero 需要“通知使用权”才能工作。\n请点击按钮前往设置页手动开启。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedButton(
            onClick = onOpenTodos,
            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp),
            enabled = hasPermission // [!] 仅在有权限时才启用
        ) { Text("To‑Do") }

        OutlinedButton(
            onClick = onOpenHistory,
            enabled = hasPermission // [!] 仅在有权限时才启用
        ) { Text("历史通知") }
        OutlinedButton(onClick = onOpenDebug) {
            Text("Debug")
        }

        Text("Nothing-style · 本地SLM整理通知与待办", style = MaterialTheme.typography.labelLarge)
    }
}