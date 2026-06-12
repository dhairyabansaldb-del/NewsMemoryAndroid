package com.dhairya.newsmemory.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private fun relativeTime(then: Long?, now: Long = System.currentTimeMillis()): String {
    if (then == null) return "never"
    val mins = (now - then) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ${mins % 60} min ago"
        else -> "${mins / (60 * 24)} days ago"
    }
}

/**
 * Phase 3 home: capture/allowlist/heartbeat status.
 * Phase 4 replaces this with today's three digest cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    capturedCount: Int,
    allowlistSize: Int,
    listenerLastAlive: Long?,
    lastCapturedAt: Long?,
    onEditAllowlist: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("News Memory") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Capture status", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$allowlistSize apps on the allowlist",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "$capturedCount notifications in the archive",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Listener alive: ${relativeTime(listenerLastAlive)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Last capture: ${relativeTime(lastCapturedAt)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Digests arrive in Phase 4.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            OutlinedButton(onClick = onEditAllowlist, modifier = Modifier.fillMaxWidth()) {
                Text("Edit allowlist")
            }
        }
    }
}
