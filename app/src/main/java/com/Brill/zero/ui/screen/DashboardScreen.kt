@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import android.graphics.Typeface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.brill.zero.nls.PermissionUtils // [!] 导入我们的工具

@Composable
fun DashboardScreen(onNavigate: (String) -> Unit = {}) {

    val context = LocalContext.current
    // 加载 assets 字体 VT323
    val vt323 = remember(context) {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf"))
    }

    // [!] V27 修复: 实时检查权限状态
    val hasPermission by produceState(initialValue = false, context) {
        value = PermissionUtils.isNotificationServiceEnabled(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部：居中标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            Text(
                "Dashboard",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = vt323
                ),
                color = Color(0xFFE6E6E6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 仪表盘入口改为灰色 bar（与 To‑Do 风格一致），带图标
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EntryBar(
                text = "To‑Do",
                icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp)) },
                fontFamily = vt323,
                onClick = { onNavigate("todos") }
            )
            EntryBar(
                text = "History",
                icon = { Icon(Icons.Outlined.History, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp)) },
                fontFamily = vt323,
                onClick = { onNavigate("history") }
            )
            EntryBar(
                text = "Debug",
                icon = { Icon(Icons.Outlined.BugReport, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp)) },
                fontFamily = vt323,
                onClick = { onNavigate("debug") }
            )
            EntryBar(
                text = "Settings",
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp)) },
                fontFamily = vt323,
                onClick = { onNavigate("settings") }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("ZERO", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = vt323))

        // [!!!] V27 修复: 权限引导按钮 [!!!]
        if (!hasPermission) {
            Button(
                onClick = { PermissionUtils.openNotificationAccessSettings(context) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    "权限不足",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = vt323),
                    color = MaterialTheme.colorScheme.background
                )
            }
            Text(
                "Zero 需要“通知使用权”才能工作。\n请点击按钮前往设置页手动开启。",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = vt323)
            )
        }

        // 页面功能与说明

        Text("Nothing-style · 本地SLM整理通知与待办", style = MaterialTheme.typography.labelLarge.copy(fontFamily = vt323))
        }
    }
}

@Composable
private fun EntryBar(text: String, icon: @Composable () -> Unit, fontFamily: FontFamily? = null, onClick: () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1A1A1A)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Text(
                text,
                color = Color(0xFFE6E6E6),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.5f,
                    fontFamily = fontFamily
                )
            )
        }
    }
}