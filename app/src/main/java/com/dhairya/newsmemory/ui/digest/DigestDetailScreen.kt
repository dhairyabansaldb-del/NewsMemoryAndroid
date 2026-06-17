package com.dhairya.newsmemory.ui.digest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.ui.components.AlmanacCard
import com.dhairya.newsmemory.ui.components.SourceMonogram
import com.dhairya.newsmemory.ui.theme.Eyebrow
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.components.TopicPill
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.ui.theme.display
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun timeLabel(epoch: Long): String =
    DateTimeFormatter.ofPattern("h:mm a").format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

@Composable
fun DigestDetailScreen(digestId: String, db: ArchiveDatabase, onBack: () -> Unit) {
    val a = LocalAlmanac.current
    LaunchedEffect(digestId) { db.digestDao().markOpened(digestId, System.currentTimeMillis()) }

    val digest by produceState<Digest?>(null, digestId) { value = db.digestDao().digest(digestId) }
    val items by produceState(emptyList<DigestItem>(), digestId) { value = db.digestDao().itemsFor(digestId) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    val dateLabel = remember(digest) {
        digest?.let {
            DateTimeFormatter.ofPattern("EEEE, d MMMM")
                .format(Instant.ofEpochMilli(it.windowEnd).atZone(ZoneId.systemDefault()))
        } ?: ""
    }

    Box(Modifier.fillMaxSize().background(a.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item("head") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = a.ink,
                            modifier = Modifier.size(24.dp).clickable(onClick = onBack)
                        )
                        Spacer(Modifier.weight(1f))
                        if (digest?.pipelineMode == "HEURISTIC" && (digest?.itemCount ?: 0) > 0) {
                            Text("basic grouping", style = body(11.0), color = a.faint)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${digest?.slot?.let { DigestSlot.valueOf(it).label } ?: ""} Digest",
                        style = display(27), color = a.ink
                    )
                    Text(
                        "$dateLabel · ${digest?.itemCount ?: 0} stories · ${digest?.sourceCount ?: 0} sources",
                        style = body(12.5), color = a.inkMed
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (items.isEmpty()) {
                item("empty") {
                    Text(
                        "Quiet window — nothing was captured.",
                        style = body(14.0), color = a.inkMed,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }

            items(items.size, key = { items[it].id }) { idx ->
                val item = items[idx]
                StoryCard(
                    item = item,
                    db = db,
                    expanded = expanded[item.id] == true,
                    onToggle = { expanded[item.id] = !(expanded[item.id] ?: false) }
                )
            }
        }
    }
}

@Composable
private fun StoryCard(
    item: DigestItem,
    db: ArchiveDatabase,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val a = LocalAlmanac.current
    AlmanacCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (expanded) a.accent.copy(alpha = 0.4f) else a.line,
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onToggle)
    ) {
        Column(Modifier.padding(15.dp)) {
            TopicPill(item.topicLabel)
            Spacer(Modifier.height(10.dp))
            Text(item.headline, style = display(18).copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), color = a.ink)
            Spacer(Modifier.height(12.dp))

            // Footer row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.sourceCount > 1) {
                    Box(Modifier.width((minOf(item.sourceCount, 3) * 16 + 6).dp).height(22.dp)) {
                        repeat(minOf(item.sourceCount, 3)) { i ->
                            Box(Modifier.offset(x = (i * 16).dp)) {
                                SourceMonogram(item.headline + i, size = 22)
                            }
                        }
                    }
                }
                Text(
                    if (item.sourceCount > 1) "${item.sourceCount} sources merged" else "1 source",
                    style = body(11.5),
                    color = if (expanded) a.accent else a.inkMed,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                    null, tint = if (expanded) a.accent else a.inkLow
                )
            }

            AnimatedVisibility(visible = expanded) {
                SourceDrawer(itemId = item.id, db = db)
            }
        }
    }
}

@Composable
private fun SourceDrawer(itemId: Long, db: ArchiveDatabase) {
    val a = LocalAlmanac.current
    val sources by produceState(emptyList<RawNotification>(), itemId) {
        value = db.digestDao().sourcesFor(itemId)
    }
    Column(Modifier.padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(a.line))
        Spacer(Modifier.height(12.dp))
        Text("RAW NOTIFICATIONS · ${sources.size}", style = Eyebrow, color = a.inkMed)
        Spacer(Modifier.height(10.dp))
        sources.forEachIndexed { i, raw ->
            Row(verticalAlignment = Alignment.Top) {
                SourceMonogram(raw.publisher ?: raw.packageName, size = 30)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            raw.publisher ?: raw.packageName,
                            style = body(12.5, FontWeight.SemiBold), color = a.ink
                        )
                        Text("  ·  ${timeLabel(raw.postedAt)}", style = body(11.0), color = a.inkLow)
                    }
                    Text(raw.title ?: "", style = body(13.0), color = a.ink)
                    raw.body?.let { Text(it, style = body(11.5), color = a.inkMed) }
                }
            }
            if (i < sources.lastIndex) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(a.line))
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
