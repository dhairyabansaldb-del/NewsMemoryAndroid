package com.dhairya.newsmemory.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
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
import com.dhairya.newsmemory.ui.components.AlmanacCard
import com.dhairya.newsmemory.ui.theme.Eyebrow
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.ui.theme.display
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ArchiveScreen(db: ArchiveDatabase, onOpenDigest: (String) -> Unit) {
    val a = LocalAlmanac.current
    val digests by db.digestDao().allDigests().collectAsState(initial = emptyList())
    val byDate = remember(digests) {
        val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")
        digests.groupBy { fmt.format(Instant.ofEpochMilli(it.windowEnd).atZone(ZoneId.systemDefault())) }
    }

    Box(Modifier.fillMaxSize().background(a.bg)) {
        if (digests.isEmpty()) {
            Text(
                "No digests yet — the first arrives at the next scheduled time.",
                style = body(14.0), color = a.inkMed, modifier = Modifier.padding(24.dp)
            )
            return@Box
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item("title") {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("Archive", style = display(27), color = a.ink)
                    Spacer(Modifier.height(8.dp))
                }
            }
            byDate.forEach { (date, day) ->
                item("d-$date") {
                    Text(
                        date.uppercase(), style = Eyebrow, color = a.inkMed,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                items(day.size, key = { day[it].id }) { i -> ArchiveRow(day[i], onOpenDigest) }
            }
        }
    }
}

@Composable
private fun ArchiveRow(digest: Digest, onOpenDigest: (String) -> Unit) {
    val a = LocalAlmanac.current
    AlmanacCard(modifier = Modifier.fillMaxWidth().clickable { onOpenDigest(digest.id) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${DigestSlot.valueOf(digest.slot).label} Digest", style = display(16), color = a.ink)
                Text(
                    if (digest.itemCount == 0) "quiet window"
                    else "${digest.itemCount} stories · ${digest.sourceCount} sources" +
                        if (digest.openedAt == null) " · unread" else "",
                    style = body(11.5), color = a.inkMed
                )
            }
            if (digest.pipelineMode == "HEURISTIC" && digest.itemCount > 0) {
                Text("basic grouping", style = body(10.5), color = a.faint)
            }
        }
    }
}
