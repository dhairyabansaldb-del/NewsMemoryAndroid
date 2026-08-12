package com.dhairya.newsmemory.ui.home

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.memory.BuildingSignal
import com.dhairya.newsmemory.memory.RecurrenceEngine
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
    /** Null until the archive has enough history to have noticed anything recurring. */
    building: BuildingSignal?,
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

        WhatsBuilding(building)

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
private fun WhatsBuilding(signal: BuildingSignal?) {
    val a = LocalAlmanac.current
    // Read outside the draw lambda: DrawScope is not a composable scope.
    val highlight = a.highlightBg
    AlmanacCard(fill = a.tintA, modifier = Modifier.fillMaxWidth(), radius = 20) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WHAT'S BUILDING", style = Eyebrow, color = a.inkMed, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if (signal == null) {
                // Not a "coming soon" notice any more: the engine is live and has simply not
                // seen the same subject often enough yet.
                Text(
                    "Nothing recurring yet — the stories that keep returning will surface here once the archive has seen them a few times.",
                    style = body(12.0), color = a.inkMed
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotMatrix(signal.activeDayOffsets)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            signal.entityName,
                            style = display(16).copy(
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = a.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.drawBehind {
                                // Highlighter swipe, not a full block fill: a marker band across
                                // the lower part of the line, bleeding slightly past both ends.
                                drawRect(
                                    color = highlight,
                                    topLeft = Offset(-3.dp.toPx(), size.height * 0.34f),
                                    size = Size(size.width + 6.dp.toPx(), size.height * 0.60f)
                                )
                            }
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(buildingCounts(signal), style = body(12.0), color = a.inkMed)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Handoff says tapping opens this signal's story cluster. There is no
                    // navigation callback plumbed through to Home for it yet, so the card is
                    // presentational — the chevron is drawn but nothing is clickable.
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = a.inkLow)
                }
            }
        }
    }
}

/**
 * The counts line under the entity name — the handoff's "4th day · 8 stories this week"
 * rhythm, worded for the window the engine actually counts over.
 *
 * Both numbers come from [RecurrenceEngine]'s trailing 30-day tally, which is WIDER than the
 * 28-day matrix — that is why the period reads "this month" and why `dayCount` is text only.
 * `dayCount` can legitimately exceed `activeDayOffsets.size`; the matrix draws from the
 * offsets, never from this number.
 */
private fun buildingCounts(signal: BuildingSignal): String {
    val days = "${signal.dayCount} ${if (signal.dayCount == 1) "day" else "days"}"
    val stories = "${signal.itemCount} ${if (signal.itemCount == 1) "story" else "stories"}"
    return "$days · $stories this month"
}

/** The Home sparkline is 7 columns × 4 rows = [RecurrenceEngine.MATRIX_DAYS] days. */
private const val MATRIX_COLS = 7
private const val MATRIX_ROWS = 4

/**
 * The 7×4 dot-matrix sparkline (handoff §What's building): active days r3 in `accent`, the
 * rest r1.5 in `line2`.
 *
 * **Reading direction:** cells run left→right, top→bottom from oldest to newest, the way text
 * reads. So the TOP-LEFT dot is 27 days back and the BOTTOM-RIGHT dot is TODAY. Each row is a
 * seven-day stretch and the bottom row is the current one, so the eye finishes reading on
 * "now" — which is the part the card is about.
 *
 * Drawn purely from [BuildingSignal.activeDayOffsets] (0 = today), which the engine has
 * already bounded to the matrix.
 */
@Composable
private fun DotMatrix(activeDayOffsets: Set<Int>, modifier: Modifier = Modifier) {
    val a = LocalAlmanac.current
    val onColor = a.accent
    val offColor = a.line2
    Canvas(modifier.size(width = 60.dp, height = 34.dp)) {
        val cellW = size.width / MATRIX_COLS
        val cellH = size.height / MATRIX_ROWS
        for (cell in 0 until RecurrenceEngine.MATRIX_DAYS) {
            val dayOffset = RecurrenceEngine.MATRIX_DAYS - 1 - cell
            val active = dayOffset in activeDayOffsets
            drawCircle(
                color = if (active) onColor else offColor,
                radius = if (active) 3.dp.toPx() else 1.5.dp.toPx(),
                center = Offset(
                    x = (cell % MATRIX_COLS + 0.5f) * cellW,
                    y = (cell / MATRIX_COLS + 0.5f) * cellH
                )
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
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = a.inkLow)
    }
}
