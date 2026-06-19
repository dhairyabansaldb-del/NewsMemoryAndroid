package com.dhairya.newsmemory.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.pipeline.DigestTimes
import com.dhairya.newsmemory.ui.components.AlmanacCard
import com.dhairya.newsmemory.ui.components.SourceMonogram
import com.dhairya.newsmemory.ui.theme.Eyebrow
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.PillLabel
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.ui.theme.display

data class SlotCard(val slot: DigestSlot, val digest: Digest?, val deliveryMinutes: Int)

private fun fmt(min: Int): String {
    val h = min / 60; val m = min % 60
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
    return "%d:%02d %s".format(h12, m, ampm)
}

private fun slotIcon(slot: DigestSlot): ImageVector = when (slot) {
    DigestSlot.MORNING -> Icons.Filled.WbSunny
    DigestSlot.EVENING -> Icons.Filled.WbTwilight
    DigestSlot.NIGHT -> Icons.Filled.Bedtime
}

@Composable
fun HomeScreen(
    dateLabel: String,
    slotCards: List<SlotCard>,
    times: DigestTimes,
    topicCounts: List<Pair<String, Int>>,
    listeningCount: Int,
    listeningLabels: List<String>,
    onOpenDigest: (String) -> Unit,
    onEditAllowlist: () -> Unit
) {
    val a = LocalAlmanac.current
    // Hero = the most recently delivered digest, whatever slot it is — so opening the app
    // at night surfaces the Night digest, not the first non-empty slot of the day.
    val hero = slotCards.mapNotNull { it.digest }.maxByOrNull { it.createdAt }
    val next = slotCards.firstOrNull { it.digest == null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Today", style = display(34), color = a.ink)
                Text(dateLabel, style = body(12.5), color = a.inkMed)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, a.ink, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Notifications, null, tint = a.ink, modifier = Modifier.size(18.dp))
            }
        }

        // Topic chips
        if (topicCounts.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { TopicChip("All", topicCounts.sumOf { it.second }, active = true) }
                items(topicCounts.size, key = { topicCounts[it].first }) {
                    TopicChip(topicCounts[it].first, topicCounts[it].second, active = false)
                }
            }
        }

        // Bento row: hero + next read
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.fillMaxWidth()) {
            HeroBlock(
                digest = hero,
                modifier = Modifier.weight(1f),
                onClick = { hero?.let { onOpenDigest(it.id) } }
            )
            NextReadBlock(next = next, times = times, modifier = Modifier.weight(1f))
        }

        WhatsBuilding()

        ThreeReads(slotCards = slotCards, onOpenDigest = onOpenDigest)

        ListeningStrip(
            count = listeningCount,
            labels = listeningLabels,
            onClick = onEditAllowlist
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TopicChip(label: String, count: Int, active: Boolean) {
    val a = LocalAlmanac.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) a.chipActiveBg else a.bg)
            .then(
                if (active) Modifier
                else Modifier.border(1.dp, a.line2, RoundedCornerShape(20.dp))
            )
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, style = body(12.5), color = if (active) a.chipActiveInk else a.inkMed)
        if (count > 0) Text(
            count.toString(),
            style = display(13),
            color = if (active) a.chipActiveInk else a.inkLow
        )
    }
}

@Composable
private fun HeroBlock(digest: Digest?, modifier: Modifier, onClick: () -> Unit) {
    val a = LocalAlmanac.current
    Box(
        modifier = modifier
            .heightIn(min = 156.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(a.hero)
            .clickable(enabled = digest != null, onClick = onClick)
            .padding(16.dp)
    ) {
        val heroIcon = digest?.let { slotIcon(DigestSlot.valueOf(it.slot)) } ?: Icons.Filled.WbSunny
        Icon(heroIcon, null, tint = a.heroInk, modifier = Modifier.size(22.dp))
        Text(
            if (digest != null) "READY" else "SOON",
            style = PillLabel, color = a.heroTag,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                (digest?.itemCount ?: 0).toString(),
                style = display(54).copy(lineHeight = 46.sp), color = a.heroInk
            )
            Text(
                if (digest != null) "stories in your ${DigestSlot.valueOf(digest.slot).label} Digest"
                else "no digest yet",
                style = body(12.5), color = a.heroInk.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun NextReadBlock(next: SlotCard?, times: DigestTimes, modifier: Modifier) {
    val a = LocalAlmanac.current
    val slot = next?.slot ?: DigestSlot.MORNING
    val minutes = next?.deliveryMinutes ?: times.morningMinutes
    Box(
        modifier = modifier
            .heightIn(min = 156.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(a.blockB)
            .padding(16.dp)
    ) {
        Icon(slotIcon(slot), null, tint = a.ink, modifier = Modifier.size(20.dp))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text("Next read", style = body(12.0), color = a.ink.copy(alpha = 0.7f))
            Text(fmt(minutes), style = display(30), color = a.ink)
            Text("${slot.label} Digest", style = body(12.5), color = a.ink.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun WhatsBuilding() {
    val a = LocalAlmanac.current
    AlmanacCard(fill = a.tintA, modifier = Modifier.fillMaxWidth(), radius = 20) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WHAT'S BUILDING", style = Eyebrow, color = a.inkMed, modifier = Modifier.weight(1f))
                Text("V1 · MEMORY", style = Eyebrow.copy(fontSize = 9.sp), color = a.faint)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Recurrence tracking arrives in v1 — once a few weeks of archive build up, the stories that keep returning surface here.",
                style = body(12.0), color = a.inkMed
            )
        }
    }
}

@Composable
private fun ThreeReads(slotCards: List<SlotCard>, onOpenDigest: (String) -> Unit) {
    val a = LocalAlmanac.current
    AlmanacCard(fill = a.tintB, modifier = Modifier.fillMaxWidth(), radius = 20) {
        Row(Modifier.height(76.dp)) {
            slotCards.forEachIndexed { i, card ->
                val done = card.digest != null
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (done) Modifier.clickable { onOpenDigest(card.digest!!.id) } else Modifier)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        slotIcon(card.slot), null,
                        tint = if (done) a.accent else a.inkLow,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(card.slot.label, style = body(11.0, FontWeight.Medium), color = a.inkMed)
                    Text(
                        if (done) "Read" else fmt(card.deliveryMinutes),
                        style = body(10.5), color = a.inkLow
                    )
                }
                if (i < slotCards.lastIndex) {
                    Box(Modifier.width(1.dp).height(76.dp).background(a.line))
                }
            }
        }
    }
}

@Composable
private fun ListeningStrip(count: Int, labels: List<String>, onClick: () -> Unit) {
    val a = LocalAlmanac.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            labels.take(4).forEachIndexed { i, label ->
                Box(modifier = Modifier.offset(x = (i * 17).dp)) {
                    SourceMonogram(label, size = 24)
                }
            }
        }
        Spacer(Modifier.width((labels.take(4).size * 17 + 8).dp))
        Text(
            "Listening to $count ${if (count == 1) "source" else "sources"}",
            style = body(12.0), color = a.inkMed, modifier = Modifier.weight(1f)
        )
        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = a.inkLow)
    }
}
