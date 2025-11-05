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
import org.json.JSONObject
import java.io.File

@Composable
fun ModelManageScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val vt323 = remember(context) {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf"))
    }

    val modelsDir = remember { File(context.noBackupFilesDir, "models").apply { mkdirs() } }
    var models by remember { mutableStateOf(listModelsWithMetrics(modelsDir)) }
    var toExport by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val src = toExport
        if (uri != null && src != null && src.exists()) {
            runCatching {
                val ctx = context
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { `in` -> `in`.copyTo(out) }
                }
            }.onFailure { e ->
                // 仅日志记录，避免 UI 崩溃
                android.util.Log.w("ModelManage", "Export failed: ${e.message}")
            }
        }
        toExport = null
    }

    // 当前选择与使用状态
    var useLearned by remember { mutableStateOf(com.brill.zero.settings.AppSettings.getUseLearnedL1(context)) }
    var selectedPath by remember { mutableStateOf(com.brill.zero.settings.AppSettings.getL1SelectedModelPath(context)) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
            Text("本地模型管理", style = MaterialTheme.typography.titleMedium.copy(fontFamily = vt323), color = Color(0xFFE6E6E6), modifier = Modifier.align(Alignment.Center))
        }

        ElevatedCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1A1A1A)), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("使用本地训练模型", color = Color(0xFFE6E6E6), style = MaterialTheme.typography.titleMedium.copy(fontFamily = vt323), modifier = Modifier.weight(1f))
                Switch(checked = useLearned, onCheckedChange = { v ->
                    useLearned = v
                    com.brill.zero.settings.AppSettings.setUseLearnedL1(context, v)
                })
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(models) { m ->
                ElevatedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1A1A1A)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(m.name, color = Color(0xFFE6E6E6), style = MaterialTheme.typography.titleMedium.copy(fontFamily = vt323))
                        val accText = m.accuracy?.let { "准确率: %.2f%%".format(it * 100) } ?: "准确率: 未知"
                        Text("大小: ${formatSize(m.size)} | ${accText}", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)

                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val isActive = selectedPath?.let { File(it).absolutePath == m.file.absolutePath } == true && useLearned
                            if (isActive) {
                                AssistChip(onClick = {}, label = { Text("正在使用") }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF2E7D32)))
                            }
                            Button(onClick = {
                                selectedPath = m.file.absolutePath
                                com.brill.zero.settings.AppSettings.setL1SelectedModelPath(context, m.file.absolutePath)
                                com.brill.zero.settings.AppSettings.setUseLearnedL1(context, true)
                            }) { Text("使用此模型") }
                            OutlinedButton(onClick = {
                                toExport = m.file
                                exportLauncher.launch(m.file.name)
                            }) { Text("导出模型") }
                            OutlinedButton(onClick = {
                                // 刷新列表（用户可能替换了文件）
                                models = listModelsWithMetrics(modelsDir)
                            }) { Text("刷新") }
                        }
                    }
                }
            }
        }
    }
}

private data class ModelItem(val file: File, val name: String, val size: Long, val accuracy: Double?)

private fun listModelsWithMetrics(dir: File): List<ModelItem> {
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".tflite") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    return files.map { f ->
        val metricsFile = File(dir, f.nameWithoutExtension + ".metrics.json")
        val acc = runCatching {
            if (metricsFile.exists()) JSONObject(metricsFile.readText()).optDouble("accuracy", Double.NaN) else Double.NaN
        }.getOrDefault(Double.NaN)
        ModelItem(file = f, name = f.name, size = f.length(), accuracy = if (acc.isNaN()) null else acc)
    }
}

private fun formatSize(size: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        size >= gb -> "%.2f GB".format(size / gb)
        size >= mb -> "%.2f MB".format(size / mb)
        size >= kb -> "%.0f KB".format(size / kb)
        else -> "$size B"
    }
}