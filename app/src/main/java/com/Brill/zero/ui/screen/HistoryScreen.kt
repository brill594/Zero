package com.brill.zero.ui.screen

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brill.zero.data.repo.ZeroRepository
import androidx.compose.ui.platform.LocalContext      // ← 新增

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


    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("高优先级", style = MaterialTheme.typography.titleLarge) }
        items(highs) { n -> Text("• ${labelOf(n)}", maxLines = 1, overflow = TextOverflow.Ellipsis) }

        item { Spacer(Modifier.height(16.dp)); Text("中优先级", style = MaterialTheme.typography.titleLarge) }
        items(meds)  { n -> Text("• ${labelOf(n)}", maxLines = 1, overflow = TextOverflow.Ellipsis) }

        item { Spacer(Modifier.height(16.dp)); Text("低优先级", style = MaterialTheme.typography.titleLarge) }
        items(lows)  { n -> Text("• ${labelOf(n)}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}