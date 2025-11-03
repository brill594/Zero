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
import com.brill.zero.domain.model.Priority
import com.brill.zero.ml.NlpProcessor
import com.brill.zero.ml.PriorityClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 模型实例（避免频繁重建）
    val l1 = remember { PriorityClassifier(ctx) }
    val nlp = remember { NlpProcessor(ctx) }
    val slm = remember { nlp.l3SlmProcessor }

    var input by remember { mutableStateOf("测试：明早9点和王总对齐下财务周报，下午取快递 取件码A8B3。") }

    var l1Out by remember { mutableStateOf<Priority?>(null) }
    var l1Time by remember { mutableStateOf<Long?>(null) }

    var l2Out by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var l2Time by remember { mutableStateOf<Long?>(null) }

    val l3Intents = remember { nlp.debugL3Intents() }
    var l3Intent by remember { mutableStateOf(l3Intents.first()) }
    var l3Out by remember { mutableStateOf<String?>(null) }
    var l3Time by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        // 可选：预热，避免首次点击 jank
        runCatching { l1.preload() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Zero · Debug Console") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
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
                Text(resultLine(l1Out?.name, l1Time))
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
                Text(resultLine(
                    l2Out?.first?.let { "$it  (p=${"%.2f".format(l2Out!!.second)})" },
                    l2Time
                ))
            }

            Spacer(Modifier.height(20.dp))

            // ---- L3 ----
            SectionTitle("L3 · SLM (Qwen / 本地)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntentDropdown(l3Intents, l3Intent, onSelect = { l3Intent = it })
                Button(onClick = {
                    scope.launch {
                        val t = measureTimeMillis {
                            l3Out = withContext(Dispatchers.Default) {
                                val todo = slm.process(input, l3Intent)
                                todo?.let { "Todo(title=${it.title}, due=${it.dueAt})" } ?: "null"
                            }
                        }
                        l3Time = t
                    }
                }) { Text("Run L3") }
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
    label: String = "意图"
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
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


private fun resultLine(s: String?, ms: Long?): String =
    (s ?: "—") + (ms?.let { "   • ${it}ms" } ?: "")
