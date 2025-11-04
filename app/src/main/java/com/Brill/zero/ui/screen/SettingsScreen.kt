@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.brill.zero.ui.screen

import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Settings
import com.brill.zero.settings.AppSettings

@Composable
fun SettingsScreen(onOpenDashboard: () -> Unit = {}, onOpenDataset: () -> Unit = {}) {
    val context = LocalContext.current

    val vt323 = remember(context) {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf"))
    }

    // Load persisted values
    var batteryThreshold by remember { mutableStateOf(AppSettings.getBatteryThreshold(context)) }
    var l3Threads by remember { mutableStateOf(AppSettings.getL3Threads(context)) }
    val maxThreads = remember { Runtime.getRuntime().availableProcessors().coerceAtMost(8) }

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
                "设置",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = vt323),
                color = Color(0xFFE6E6E6),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 电量判定阈值（%）
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
                    Icon(Icons.Outlined.BatteryStd, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp))
                    Text(
                        "电量判定阈值(%)",
                        color = Color(0xFFE6E6E6),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.2f,
                            fontFamily = vt323
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    BatteryDropdown(
                        selected = batteryThreshold,
                        options = listOf(5, 10, 15, 20, 30, 40, 50),
                        onSelect = {
                            batteryThreshold = it
                            AppSettings.setBatteryThreshold(context, it)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // L3 模型线程数
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
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp))
                    Text(
                        "L3 模型线程数",
                        color = Color(0xFFE6E6E6),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.2f,
                            fontFamily = vt323
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    ThreadsDropdown(
                        selected = l3Threads,
                        max = maxThreads,
                        onSelect = {
                            l3Threads = it
                            AppSettings.setL3Threads(context, it)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 数据集管理（L1.csv）
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
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = Color(0xFFE6E6E6), modifier = Modifier.size(32.dp))
                    Text(
                        "数据集管理 (L1.csv)",
                        color = Color(0xFFE6E6E6),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.2f,
                            fontFamily = vt323
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onOpenDataset, modifier = Modifier.weight(1f)) { Text("打开") }
                }
            }
        }
    }
}

@Composable
private fun BatteryDropdown(
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    label: String = "阈值",
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