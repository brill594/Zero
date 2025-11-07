@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.ui.screen

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Delete
import com.brill.zero.data.datasets.L1DatasetLogger
import com.brill.zero.worker.L1NightTrainWorker
import com.brill.zero.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DatasetManageScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val vt323 = remember(context) {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf"))
    }

    val file = remember { L1DatasetLogger.currentFile(context) }
    var fileSize by remember { mutableStateOf<Long?>(null) }
    var lineCount by remember { mutableStateOf<Int?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var last50 by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var progressEpoch by remember { mutableStateOf(0) }
    var trainStartTs by remember { mutableStateOf(0L) }
    var trainLastUpdateTs by remember { mutableStateOf(0L) }

    // 安排夜间训练（一次性），由 Worker 内部约束充电+闲置
    LaunchedEffect(Unit) {
        runCatching { L1NightTrainWorker.scheduleNightly(context) }
    }

    // 简易轮询训练进度（SharedPreferences）
    LaunchedEffect(Unit) {
        while (true) {
            progressEpoch = AppSettings.getL1TrainProgressEpoch(context)
            trainStartTs = AppSettings.getL1TrainStartTs(context)
            trainLastUpdateTs = AppSettings.getL1TrainLastUpdateTs(context)
            kotlinx.coroutines.delay(500)
        }
    }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            val exists = file.exists()
            fileSize = if (exists) file.length() else 0L
            lineCount = if (exists) file.bufferedReader().use { it.lineSequence().count() } else 0
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        if (file.exists()) file.inputStream().use { it.copyTo(out) }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部：左上角返回设置 + 居中标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Outlined.Menu, contentDescription = "返回设置")
            }
            Text(
                "数据集管理",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = vt323),
                color = Color(0xFFE6E6E6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "L1.csv（JSONL）",
                            color = Color(0xFFE6E6E6),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.1f,
                                fontFamily = vt323
                            )
                        )
                        val sizeStr = fileSize?.let { humanReadableSize(it) } ?: "--"
                        val linesStr = lineCount?.toString() ?: "--"
                        Text(
                            "路径：${file.absolutePath}",
                            color = Color(0xFF9E9E9E),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            "大小：$sizeStr   行数：$linesStr",
                            color = Color(0xFF9E9E9E),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportLauncher.launch("L1.csv") }) { Text("导出") }
                        Button(onClick = {
                            editorOpen = true
                            scope.launch(Dispatchers.IO) {
                                val all = if (file.exists()) file.bufferedReader().use { it.readLines() } else emptyList()
                                val start = (all.size - 50).coerceAtLeast(0)
                                val indexed = all.withIndex().drop(start).map { it.index to it.value }
                                withContext(Dispatchers.Main) { last50 = indexed }
                            }
                        }) { Text("编辑") }
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    if (!file.parentFile!!.exists()) file.parentFile!!.mkdirs()
                                    // 置空文件
                                    file.writeText("")
                                }
                                withContext(Dispatchers.Main) {
                                    fileSize = 0L
                                    lineCount = 0
                                    last50 = emptyList()
                                }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000), contentColor = Color.White)) { Text("清空") }
                    }
                }

                // 训练进度（左） + 训练按钮（右）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val progress = (progressEpoch.coerceIn(0, 80)) / 80f
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = Color(0xFF66CC66)
                    )
                    Spacer(Modifier.width(12.dp))
                    val etaText = remember(progressEpoch, trainStartTs) {
                        if (progressEpoch <= 0 || trainStartTs <= 0L) "预计剩余: --"
                        else {
                            val elapsed = System.currentTimeMillis() - trainStartTs
                            val avgPerEpoch = elapsed.toDouble() / progressEpoch.toDouble()
                            val remaining = (80 - progressEpoch).coerceAtLeast(0)
                            val etaMs = (avgPerEpoch * remaining).toLong()
                            val mins = etaMs / 60000
                            val secs = (etaMs % 60000) / 1000
                            "预计剩余: %02d:%02d".format(mins, secs)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("当前 Epoch: ${progressEpoch}/80", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
                        Text(etaText, color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                // 主动触发训练（忽略 gating）
                                L1NightTrainWorker.enqueueNow(context, force = true)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3366FF))
                    ) { Text("训练（NB）") }
                }
            }
        }

        if (editorOpen) {
            AlertDialog(
                onDismissRequest = { editorOpen = false },
                title = { Text("编辑最近 50 行") },
                text = {
                    Column {
                        if (last50.isEmpty()) {
                            Text("(无数据)", color = Color(0xFFE6E6E6))
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(last50) { (idx, line) ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(line, color = Color(0xFFE6E6E6), modifier = Modifier.weight(1f))
                                        IconButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                val all = if (file.exists()) file.bufferedReader().use { it.readLines() }.toMutableList() else mutableListOf()
                                                if (idx in all.indices) {
                                                    all.removeAt(idx)
                                                    val textOut = if (all.isNotEmpty()) all.joinToString(separator = "\n") + "\n" else ""
                                                    file.writeText(textOut)
                                                }
                                                val newSize = file.length()
                                                val newCount = all.size
                                                val start = (all.size - 50).coerceAtLeast(0)
                                                val indexed = all.withIndex().drop(start).map { it.index to it.value }
                                                withContext(Dispatchers.Main) {
                                                    fileSize = newSize
                                                    lineCount = newCount
                                                    last50 = indexed
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "删除")
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { editorOpen = false }) { Text("关闭") }
                }
            )
        }
    }
}

private fun humanReadableSize(size: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        size >= gb -> String.format("%.2f GB", size.toDouble() / gb)
        size >= mb -> String.format("%.2f MB", size.toDouble() / mb)
        size >= kb -> String.format("%.2f KB", size.toDouble() / kb)
        else -> "$size B"
    }
}