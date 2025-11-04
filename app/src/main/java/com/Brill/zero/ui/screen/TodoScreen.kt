@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.ui.screen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brill.zero.data.repo.ZeroRepository
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch


@Composable
fun TodoScreen() {
    val ctx = LocalContext.current
    val repo = remember { ZeroRepository.get(ctx) }
    val items by repo.openTodos.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text("To‑Do", style = MaterialTheme.typography.titleLarge)
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1A1A1A),
                titleContentColor = Color(0xFFE6E6E6)
            )
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { t ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = Color(0xFF1A1A1A)
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 第一行：意图
                        val intentText = t.sourceNotificationKey ?: "(未设置意图)"
                        Text(intentText, style = MaterialTheme.typography.titleMedium, color = Color(0xFFE6E6E6))
                        // 第二行：摘要 + 完成按钮（右侧空心圆）
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                t.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFE6E6E6)
                            )
                            // Larger hollow circle button
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .border(width = 2.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
                                    .clickable { scope.launch { repo.markTodoDone(t.id) } }
                            )
                        }
                        // 第三行：截止时间
                        val dueLine = remember(t.dueAt) {
                            t.dueAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "无截止时间"
                        }
                        Text(dueLine, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}