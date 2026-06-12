package com.dhairya.newsmemory.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.pipeline.DigestTimes

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

private fun formatMinutes(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

/** A slot card on today's home: either a digest that exists, or its upcoming time. */
data class SlotCard(val slot: DigestSlot, val digest: Digest?, val deliveryMinutes: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    slotCards: List<SlotCard>,
    times: DigestTimes,
    capturedCount: Int,
    allowlistSize: Int,
    listenerLastAlive: Long?,
    lastCapturedAt: Long?,
    onOpenDigest: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onEditAllowlist: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = onOpenArchive) {
                        Icon(Icons.Filled.Inventory2, contentDescription = "Archive")
                    }
                    IconButton(onClick = onEditAllowlist) {
                        Icon(Icons.Filled.Tune, contentDescription = "Allowlist")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            slotCards.forEach { card ->
                DigestCard(card, onOpenDigest)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Capture", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$capturedCount archived · $allowlistSize apps · " +
                            "listener ${relativeTime(listenerLastAlive)} · " +
                            "last capture ${relativeTime(lastCapturedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun DigestCard(card: SlotCard, onOpenDigest: (String) -> Unit) {
    val digest = card.digest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = digest != null) { digest?.let { onOpenDigest(it.id) } }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${card.slot.label} Digest",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (digest != null && digest.pipelineMode == "HEURISTIC" && digest.itemCount > 0) {
                    ModeTag()
                }
            }
            when {
                digest == null -> Text(
                    "Upcoming at ${formatMinutes(card.deliveryMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                digest.itemCount == 0 -> Text(
                    "Quiet window — nothing captured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                else -> Text(
                    "${digest.itemCount} stories from ${digest.sourceCount} apps" +
                        if (digest.openedAt == null) "  ·  unread" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun ModeTag() {
    Text(
        "basic grouping",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary
    )
}
