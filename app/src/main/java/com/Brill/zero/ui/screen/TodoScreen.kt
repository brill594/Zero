package com.Brill.zero.ui.screen


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.flow.collectLatest


@Composable
fun TodoScreen() {
    val ctx = LocalContext.current
    val repo = remember { ZeroRepository.get(ctx) }
    val items by repo.openTodos.collectAsState(initial = emptyList())


    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { t ->
            ElevatedCard { Column(Modifier.padding(16.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                if (t.dueAt != null) Text("Due: ${'$'}{java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t.dueAt))}", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { /* mark done */ }) { Text("完成") }
                }
            } }
        }
    }
}