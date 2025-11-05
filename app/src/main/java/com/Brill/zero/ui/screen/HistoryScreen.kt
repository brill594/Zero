@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.ui.screen

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brill.zero.data.repo.ZeroRepository
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import com.brill.zero.data.datasets.L1DatasetLogger
import com.brill.zero.domain.model.Priority
import kotlinx.coroutines.launch

private fun labelOf(n: com.brill.zero.data.db.NotificationEntity): String {
    return n.title?.takeIf { it.isNotBlank() }
        ?: n.text?.takeIf { it.isNotBlank() }
        ?: "(无内容)"
}

@Composable
fun HistoryScreen(onOpenDashboard: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { ZeroRepository.get(ctx) }
    val highs by repo.streamByPriority("HIGH").collectAsState(initial = emptyList())
    val meds by repo.streamByPriority("MEDIUM").collectAsState(initial = emptyList())
    val lows by repo.streamByPriority("LOW").collectAsState(initial = emptyList())


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部：左上角菜单按钮 + 居中标题 + 右侧清空按钮
        var clearDialogOpen by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            IconButton(onClick = onOpenDashboard, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Outlined.Menu, contentDescription = "回到仪表盘")
            }
            Text(
                "History",
                style = MaterialTheme.typography.titleMedium,
                color = androidx.compose.ui.graphics.Color(0xFFE6E6E6),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(onClick = { clearDialogOpen = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Outlined.Delete, contentDescription = "清空历史通知")
            }
        }

        if (clearDialogOpen) {
            AlertDialog(
                onDismissRequest = { clearDialogOpen = false },
                title = { Text("清空历史通知") },
                text = { Text("是否清空所有通知记录？该操作不可撤销。") },
                confirmButton = {
                    val scope = rememberCoroutineScope()
                    val repo = remember { ZeroRepository.get(ctx) }
                    TextButton(onClick = {
                        scope.launch {
                            repo.clearAllNotifications()
                            clearDialogOpen = false
                        }
                    }) { Text("清空") }
                },
                dismissButton = {
                    TextButton(onClick = { clearDialogOpen = false }) { Text("取消") }
                }
            )
        }

        LazyColumn(
            Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Text("高优先级", style = MaterialTheme.typography.titleSmall) }
            items(highs) { n -> NotificationRow(n) }

            item { Spacer(Modifier.height(16.dp)); Text("中优先级", style = MaterialTheme.typography.titleSmall) }
            items(meds)  { n -> NotificationRow(n) }

            item { Spacer(Modifier.height(16.dp)); Text("低优先级", style = MaterialTheme.typography.titleSmall) }
            items(lows)  { n -> NotificationRow(n) }
        }
    }
}

@Composable
private fun NotificationRow(n: com.brill.zero.data.db.NotificationEntity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ZeroRepository.get(ctx) }
    val pm = ctx.packageManager
    val iconBitmap = remember(n.pkg) {
        runCatching { pm.getApplicationIcon(n.pkg).toBitmap(64, 64) }.getOrNull()
    }
    val appName = remember(n.pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0)).toString() }.getOrNull() ?: n.pkg
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (iconBitmap != null) {
            Image(bitmap = iconBitmap.asImageBitmap(), contentDescription = appName, modifier = Modifier.size(64.dp))
        } else {
            Box(Modifier.size(64.dp)) {} // fallback empty space
        }
        Column(Modifier.weight(1f)) {
            Text(appName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val titleLine = n.title?.takeIf { it.isNotBlank() } ?: "(无标题)"
            // 主题（subject）字体应小于内容字体
            Text(titleLine, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val infoLine = n.text?.takeIf { it.isNotBlank() } ?: "(无内容)"
            // 内容（content）字体比主题更大、可读性更好
            Text(infoLine, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // 右侧：标注按钮 + 下拉菜单
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.Label, contentDescription = "标注优先级")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("高优先级") }, onClick = {
                    menuOpen = false
                    val pm = ctx.packageManager
                    val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0)).toString() }.getOrNull() ?: n.pkg
                    val full = listOfNotNull(appName, n.title, n.text).filter { it.isNotBlank() }.joinToString(" : ")
                    scope.launch {
                        repo.setNotificationUserPriority(n.id, Priority.HIGH.name)
                        L1DatasetLogger.append(ctx, full.ifBlank { "(无内容)" }, "高优先级")
                    }
                })
                DropdownMenuItem(text = { Text("中优先级") }, onClick = {
                    menuOpen = false
                    val pm = ctx.packageManager
                    val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0)).toString() }.getOrNull() ?: n.pkg
                    val full = listOfNotNull(appName, n.title, n.text).filter { it.isNotBlank() }.joinToString(" : ")
                    scope.launch {
                        repo.setNotificationUserPriority(n.id, Priority.MEDIUM.name)
                        L1DatasetLogger.append(ctx, full.ifBlank { "(无内容)" }, "中优先级")
                    }
                })
                DropdownMenuItem(text = { Text("低优先级") }, onClick = {
                    menuOpen = false
                    val pm = ctx.packageManager
                    val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0)).toString() }.getOrNull() ?: n.pkg
                    val full = listOfNotNull(appName, n.title, n.text).filter { it.isNotBlank() }.joinToString(" : ")
                    scope.launch {
                        repo.setNotificationUserPriority(n.id, Priority.LOW.name)
                        L1DatasetLogger.append(ctx, full.ifBlank { "(无内容)" }, "低优先级")
                    }
                })
                Divider()
                DropdownMenuItem(text = { Text("删除此通知") }, onClick = {
                    menuOpen = false
                    scope.launch { repo.deleteNotification(n.id) }
                })
            }
        }
    }
}