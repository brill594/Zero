@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.ui.screen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brill.zero.data.repo.ZeroRepository
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.brill.zero.ml.NlpProcessor
import com.brill.zero.data.db.TodoEntity


@Composable
fun TodoScreen(onOpenDashboard: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { ZeroRepository.get(ctx) }
    val nlp = remember { NlpProcessor(ctx) }
    val slm = remember { nlp.l3SlmProcessor }
    val items by repo.openTodos.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // 手动添加对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var processingIds by remember { mutableStateOf(setOf<Long>()) }
    var processingProgress by remember { mutableStateOf(mapOf<Long, Float>()) }

    // 默认将 L3 线程设置为 4（UI 可覆盖）
    LaunchedEffect(Unit) { slm.setThreadsOverride(4) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部：左上角菜单按钮 + 居中标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            IconButton(onClick = onOpenDashboard, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Outlined.Menu, contentDescription = "回到仪表盘")
            }
            IconButton(onClick = { showAddDialog = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Outlined.Add, contentDescription = "手动添加 To‑Do")
            }
            Text(
                "To‑Do",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE6E6E6),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 手动添加对话框
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("新建待办") },
                text = {
                    TextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        placeholder = { Text("请输入一句描述", color = Color(0xFFB3B3B3)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFE6E6E6)),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE6E6E6),
                            unfocusedTextColor = Color(0xFFE6E6E6),
                            focusedContainerColor = Color(0xFF1A1A1A),
                            unfocusedContainerColor = Color(0xFF1A1A1A),
                            focusedPlaceholderColor = Color(0xFFB3B3B3),
                            unfocusedPlaceholderColor = Color(0xFFB3B3B3),
                            cursorColor = Color(0xFFE6E6E6),
                            focusedIndicatorColor = Color(0xFF666666),
                            unfocusedIndicatorColor = Color(0xFF444444)
                        )
                    )
                },
                containerColor = Color(0xFF1A1A1A),
                titleContentColor = Color(0xFFE6E6E6),
                textContentColor = Color(0xFFE6E6E6),
                confirmButton = {
                    TextButton(
                        onClick = {
                            val text = manualText.trim()
                            // 先关闭弹窗，避免阻塞 UI
                            manualText = ""
                            showAddDialog = false
                            if (text.isBlank()) return@TextButton

                            scope.launch {
                                // L2 自动识别意图
                                val top = withContext(Dispatchers.Default) { nlp.debugTopIntent(text) }
                                val intentLabel = top?.first

                                // 立即创建条目（dueAt 先为空）
                                val id = repo.saveTodo(
                                    TodoEntity(
                                        title = text,
                                        dueAt = null,
                                        createdAt = System.currentTimeMillis(),
                                        status = "OPEN",
                                        sourceNotificationKey = intentLabel
                                    )
                                )

                                // 若需要截止时间：后台运行 L3，仅提取 due time → 更新条目
                                val intentsRequiringDue = setOf("日程提醒", "工作沟通")
                                if (intentLabel != null && intentLabel in intentsRequiringDue) {
                                    processingIds = processingIds + id
                                    // 启动 23 秒倒计时（每 100ms 更新一次，提前完成则直接置满）
                                    scope.launch {
                                        val totalMs = 23_000L
                                        val t0 = android.os.SystemClock.uptimeMillis()
                                        while (processingIds.contains(id)) {
                                            val elapsed = android.os.SystemClock.uptimeMillis() - t0
                                            val p = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                                            processingProgress = processingProgress + (id to p)
                                            if (p >= 1f) break
                                            delay(100)
                                        }
                                    }
                                    val out = withContext(Dispatchers.Default) { slm.process(text, intentLabel) }
                                    val dueEpoch = out?.dueAt
                                    repo.updateTodoDueAt(id, dueEpoch)
                                    // 完成后直接置满进度并移除处理标记
                                    processingProgress = processingProgress + (id to 1f)
                                    processingIds = processingIds - id
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE6E6E6))
                    ) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE6E6E6))) { Text("取消") }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { t ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = Color(0xFF1A1A1A)
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 第一行：意图
                        val intentText = t.sourceNotificationKey ?: "(未设置意图)"
                        Text(
                            intentText,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFE6E6E6)
                        )
                        // 第二行：摘要 + 完成按钮（右侧空心圆）
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                t.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFE6E6E6)
                            )
                            // 处理中的条目：右侧显示转圈；否则显示可点击的空心圆“完成框”
                            if (processingIds.contains(t.id)) {
                                val p = processingProgress[t.id] ?: 0.25f
                                CircularProgressIndicator(
                                    progress = p,
                                    modifier = Modifier.size(28.dp),
                                    color = Color(0xFFE6E6E6),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .border(width = 2.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
                                        .clickable { scope.launch { repo.markTodoDone(t.id) } }
                                )
                            }
                        }
                        // 第三行：截止时间（处理中则暂不显示；完成后再显示）
                        if (!processingIds.contains(t.id)) {
                            val dueLine = remember(t.dueAt) {
                                t.dueAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "无截止时间"
                            }
                            Text(
                                dueLine,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}