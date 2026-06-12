package com.dhairya.newsmemory.ui.digest

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.ui.home.ModeTag
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The product's main surface (EDD §8): items grouped under topic headers,
 * source-count chip, long-press → the underlying source headlines.
 * Opening marks the digest read (write-once, EDD §3).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DigestDetailScreen(
    digestId: String,
    db: ArchiveDatabase,
    onBack: () -> Unit
) {
    LaunchedEffect(digestId) {
        db.digestDao().markOpened(digestId, System.currentTimeMillis())
    }

    val digest by produceState<com.dhairya.newsmemory.data.db.Digest?>(null, digestId) {
        value = db.digestDao().digest(digestId)
    }
    val items by produceState(emptyList<DigestItem>(), digestId) {
        value = db.digestDao().itemsFor(digestId)
    }

    var sourcesFor by remember { mutableStateOf<Pair<DigestItem, List<RawNotification>>?>(null) }

    val dateLabel = remember(digest) {
        digest?.let {
            DateTimeFormatter.ofPattern("EEE d MMM").format(
                Instant.ofEpochMilli(it.windowEnd).atZone(ZoneId.systemDefault())
            )
        } ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${digest?.slot?.let { DigestSlot.valueOf(it).label } ?: ""} Digest"
                        )
                        Text(
                            dateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (digest?.pipelineMode == "HEURISTIC" && (digest?.itemCount ?: 0) > 0) {
                        ModeTag()
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                Text(
                    "Quiet window — nothing was captured.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return@Scaffold
        }

        val grouped = remember(items) { items.groupBy { it.topicLabel } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            grouped.forEach { (topic, topicItems) ->
                item(key = "header-$topic") {
                    Text(
                        topic,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(topicItems.size, key = { "item-${topicItems[it].id}" }) { idx ->
                    val item = topicItems[idx]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    sourcesFor = item to emptyList()
                                }
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(item.headline, style = MaterialTheme.typography.bodyLarge)
                            if (item.sourceCount > 1) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SuggestionChip(
                                        onClick = { sourcesFor = item to emptyList() },
                                        label = { Text("${item.sourceCount} sources") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-press: fetch + show the underlying source headlines
    sourcesFor?.let { (item, sources) ->
        LaunchedEffect(item.id) {
            if (sources.isEmpty()) {
                sourcesFor = item to db.digestDao().sourcesFor(item.id)
            }
        }
        AlertDialog(
            onDismissRequest = { sourcesFor = null },
            title = { Text("Sources", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sources.forEach { raw ->
                        Column {
                            Text(
                                raw.title ?: raw.body ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                raw.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sourcesFor = null }) { Text("Close") }
            }
        )
    }
}
