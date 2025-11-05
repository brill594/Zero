@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.debug

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import com.brill.zero.domain.model.Priority
import com.brill.zero.ml.NlpProcessor
import com.brill.zero.ml.PriorityClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis
import org.json.JSONObject
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.data.db.TodoEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onOpenDashboard: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 模型实例（避免频繁重建）
    val l1 = remember { PriorityClassifier(ctx) }
    val nlp = remember { NlpProcessor(ctx) }
    val slm = remember { nlp.l3SlmProcessor }
    val repo = remember { com.brill.zero.data.repo.ZeroRepository.get(ctx) }

    // 线程选择（默认改为 1）
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    var threads by remember { mutableStateOf(1) }
    LaunchedEffect(threads) { slm.setThreadsOverride(threads) }

    // GPU 卸载层数（0 表示禁用 GPU）
    var gpuLayers by remember { mutableStateOf(0) }
    LaunchedEffect(gpuLayers) { slm.setGpuLayersOverride(gpuLayers) }

    var input by remember { mutableStateOf("微信：明早9点和王总对齐下财务周报，下午取快递 取件码A8B3。") }

    var l1Out by remember { mutableStateOf<Priority?>(null) }
    var l1Time by remember { mutableStateOf<Long?>(null) }

    var l2Out by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var l2Time by remember { mutableStateOf<Long?>(null) }

    val l3Intents = remember { nlp.debugL3Intents() }
    var l3Intent by remember { mutableStateOf(l3Intents.first()) }
    var l3UseL2 by remember { mutableStateOf(true) }
    var l3ResolvedIntent by remember { mutableStateOf<String?>(null) }
    var l3Out by remember { mutableStateOf<String?>(null) }
    var l3Time by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        // 可选：预热，避免首次点击 jank
        runCatching { l1.preload() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
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
            Text(
                "Zero · Debug Console",
                style = MaterialTheme.typography.titleMedium,
                color = androidx.compose.ui.graphics.Color(0xFFE6E6E6),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入文本 / 通知标题+正文") },
                minLines = 4
            )

            Spacer(Modifier.height(16.dp))

            // ---- L1 ----
            SectionTitle("L1 · PriorityClassifier")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch {
                        val t = measureTimeMillis {
                            l1Out = withContext(Dispatchers.Default) {
                                // L1 的 predictPriority 是 suspend，直接调用
                                l1.predictPriority(input)
                            }
                        }
                        l1Time = t
                    }
                }) { Text("Run L1") }
                Spacer(Modifier.width(12.dp))
                Text(
                    resultLine(l1Out?.name, l1Time),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 2
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---- L2 ----
            SectionTitle("L2 · Intent (MediaPipe TextClassifier)")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch {
                        val t = measureTimeMillis {
                            l2Out = withContext(Dispatchers.Default) {
                                nlp.debugTopIntent(input)
                            }
                        }
                        l2Time = t
                    }
                }) { Text("Run L2") }
                Spacer(Modifier.width(12.dp))
                Text(
                    resultLine(
                        l2Out?.first,
                        l2Time
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 2
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---- L3 ----
            SectionTitle("L3 · SLM (Qwen / 本地)")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = l3UseL2, onCheckedChange = { l3UseL2 = it })
                Spacer(Modifier.width(8.dp))
                Text(
                    if (l3UseL2) "使用 L2 预测意图" else "手动选择意图",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 2
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThreadsDropdown(selected = threads, max = cores.coerceAtMost(8), onSelect = { threads = it }, modifier = Modifier.weight(1f))
                GpuLayersDropdown(selected = gpuLayers, options = listOf(0, 4, 8, 16, 24, 32), onSelect = { gpuLayers = it }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntentDropdown(l3Intents, l3Intent, onSelect = { l3Intent = it }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    scope.launch {
                        val t = measureTimeMillis {
                            l3Out = withContext(Dispatchers.Default) {
                                val resolved = if (l3UseL2) {
                                    val top = nlp.debugTopIntent(input)
                                    // Use label only; do not append confidence to intent
                                    l3ResolvedIntent = top?.first ?: l3Intent
                                    top?.first ?: l3Intent
                                } else {
                                    l3ResolvedIntent = l3Intent
                                    l3Intent
                                }

                                val todo = slm.process(input, resolved)
                                // 以统一 JSON 形式展示调试输出（intent/summary/due_time）
                                todo?.let {
                                    val intentVal = l3ResolvedIntent ?: resolved
                                    val summaryVal = it.title
                                    val dueEpoch = it.dueAt
                                    val dueEpochField = if (dueEpoch == null) "null" else dueEpoch.toString()
                                    """{ "intent": ${JSONObject.quote(intentVal)}, "summary": ${JSONObject.quote(summaryVal)}, "due_time_epoch": $dueEpochField }"""
                                } ?: run {
                                    val intentVal = l3ResolvedIntent ?: resolved
                                    """{ "intent": ${JSONObject.quote(intentVal)}, "summary": "", "due_time_epoch": null }"""
                                }
                            }
                        }
                        l3Time = t
                    }
                }) { Text("RUN L3") }
                // 一键创建 To‑Do（从 L3 JSON）
                Button(enabled = l3Out != null, onClick = {
                    val json = l3Out
                    if (!json.isNullOrBlank()) {
                        runCatching {
                            val obj = JSONObject(json)
                            val title = obj.optString("summary").ifBlank { null }
                            val intentVal = obj.optString("intent").ifBlank { null }
                            val dueEpoch = obj.opt("due_time_epoch")?.toString()?.toLongOrNull()
                            if (title != null) {
                                scope.launch {
                                    repo.saveTodo(
                                        TodoEntity(
                                            title = title,
                                            dueAt = dueEpoch,
                                            createdAt = System.currentTimeMillis(),
                                            status = "OPEN",
                                            sourceNotificationKey = intentVal
                                        )
                                    )
                                }
                            }
                        }
                    }
                }) { Text("Add To‑Do") }
            }
            Text(resultLine(l3Out, l3Time))

            Spacer(Modifier.height(28.dp))
            Divider()
            Text(
                "说明：L1=优先级；L2=意图分类；L3=按意图调用 SLM 生成 Todo。\n" +
                        "所有推理都在后台 Dispatcher 跑，返回时显示耗时(ms)。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun IntentDropdown(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    label: String = "意图",
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()              // ★ 必须
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreadsDropdown(
    selected: Int,
    max: Int,
    onSelect: (Int) -> Unit,
    label: String = "线程数",
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(max) { (1..max).toList() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.toString()) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun GpuLayersDropdown(
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    label: String = "GPU 卸载层数",
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.toString()) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}


private fun resultLine(s: String?, ms: Long?): String =
    (s ?: "—") + (ms?.let { "   • ${it}ms" } ?: "")
