package com.dhairya.newsmemory.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.ui.home.ModeTag
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Archive browser (EDD §8): reverse-chron, grouped by date, pipeline-mode tag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    db: ArchiveDatabase,
    onOpenDigest: (String) -> Unit,
    onBack: () -> Unit
) {
    val digests by db.digestDao().allDigests().collectAsState(initial = emptyList())

    val byDate = remember(digests) {
        val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")
        digests.groupBy {
            fmt.format(Instant.ofEpochMilli(it.windowEnd).atZone(ZoneId.systemDefault()))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (digests.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                Text(
                    "No digests yet — the first one arrives at the next scheduled time.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            byDate.forEach { (date, dayDigests) ->
                item(key = "date-$date") {
                    Text(
                        date,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(dayDigests.size, key = { dayDigests[it].id }) { idx ->
                    ArchiveRow(dayDigests[idx], onOpenDigest)
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(digest: Digest, onOpenDigest: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDigest(digest.id) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${DigestSlot.valueOf(digest.slot).label} Digest",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    when {
                        digest.itemCount == 0 -> "quiet window"
                        else -> "${digest.itemCount} stories · ${digest.sourceCount} apps" +
                            if (digest.openedAt == null) " · unread" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (digest.pipelineMode == "HEURISTIC" && digest.itemCount > 0) ModeTag()
        }
    }
}
