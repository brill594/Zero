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

private fun labelOf(n: com.brill.zero.data.db.NotificationEntity): String {
    return n.title?.takeIf { it.isNotBlank() }
        ?: n.text?.takeIf { it.isNotBlank() }
        ?: "(无内容)"
}

@Composable
fun HistoryScreen() {
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
        // 删除标题栏背景，并将标题上移至内容顶部
        Text(
            "History",
            style = MaterialTheme.typography.titleLarge,
            color = androidx.compose.ui.graphics.Color(0xFFE6E6E6),
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )

        LazyColumn(
            Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Text("高优先级", style = MaterialTheme.typography.titleLarge) }
            items(highs) { n -> NotificationRow(n) }

            item { Spacer(Modifier.height(16.dp)); Text("中优先级", style = MaterialTheme.typography.titleLarge) }
            items(meds)  { n -> NotificationRow(n) }

            item { Spacer(Modifier.height(16.dp)); Text("低优先级", style = MaterialTheme.typography.titleLarge) }
            items(lows)  { n -> NotificationRow(n) }
        }
    }
}

@Composable
private fun NotificationRow(n: com.brill.zero.data.db.NotificationEntity) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val iconBitmap = remember(n.pkg) {
        runCatching { pm.getApplicationIcon(n.pkg).toBitmap(48, 48) }.getOrNull()
    }
    val appName = remember(n.pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0)).toString() }.getOrNull() ?: n.pkg
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (iconBitmap != null) {
            Image(bitmap = iconBitmap.asImageBitmap(), contentDescription = appName)
        } else {
            Box(Modifier.size(48.dp)) {} // fallback empty space
        }
        Column(Modifier.weight(1f)) {
            Text(appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val titleLine = n.title?.takeIf { it.isNotBlank() } ?: "(无标题)"
            Text(titleLine, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val infoLine = n.text?.takeIf { it.isNotBlank() } ?: "(无内容)"
            Text(infoLine, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}