package com.dhairya.newsmemory.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.pipeline.WindowCalculator
import com.dhairya.newsmemory.ui.components.AlmanacCard
import com.dhairya.newsmemory.ui.theme.Eyebrow
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.ui.theme.display
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/** The dates of a month laid out in calendar weeks (Monday-first); nulls pad the edges. */
private fun monthWeeks(month: YearMonth): List<List<LocalDate?>> {
    val lead = month.atDay(1).dayOfWeek.value - 1   // Monday = 0
    val cells = ArrayList<LocalDate?>()
    repeat(lead) { cells.add(null) }
    for (d in 1..month.lengthOfMonth()) cells.add(month.atDay(d))
    while (cells.size % 7 != 0) cells.add(null)
    return cells.chunked(7)
}

/**
 * Archive (Almanac redesign feedback #3): a month calendar. Dates that produced any digest
 * are marked and tappable; selecting one shows that day's three slots below. window_id
 * ("YYYY-MM-DD-{M|E|N}") carries the date + slot, so grouping is a pure parse.
 */
@Composable
fun ArchiveScreen(db: ArchiveDatabase, onOpenDigest: (String) -> Unit) {
    val a = LocalAlmanac.current
    val digests by db.digestDao().allDigests().collectAsState(initial = emptyList())
    val byDate = remember(digests) { digests.groupBy { WindowCalculator.parse(it.id).first } }
    // allDigests() is ordered by created_at DESC, so the first is the most recent.
    val latestDate = remember(digests) {
        digests.firstOrNull()?.let { WindowCalculator.parse(it.id).first }
    }

    Box(Modifier.fillMaxSize().background(a.bg)) {
        if (digests.isEmpty()) {
            Text(
                "No digests yet — the first arrives at the next scheduled time.",
                style = body(14.0), color = a.inkMed, modifier = Modifier.padding(24.dp)
            )
            return@Box
        }

        var selectedDate by remember(latestDate) { mutableStateOf(latestDate) }
        var month by remember(latestDate) {
            mutableStateOf(YearMonth.from(latestDate ?: LocalDate.now()))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("title") {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("Archive", style = display(27), color = a.ink)
                }
            }

            item("calendar") {
                CalendarCard(
                    month = month,
                    datesWithDigests = byDate.keys,
                    selectedDate = selectedDate,
                    onPrev = { month = month.minusMonths(1) },
                    onNext = { month = month.plusMonths(1) },
                    onSelect = { selectedDate = it }
                )
            }

            val day = selectedDate
            if (day != null) {
                item("dayhead-$day") {
                    Text(
                        DAY_FMT.format(day).uppercase(), style = Eyebrow, color = a.inkMed,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                val dayDigests = byDate[day].orEmpty()
                DigestSlot.entries.forEach { slot ->
                    item("slot-$day-${slot.code}") {
                        DaySlotRow(
                            slot = slot,
                            digest = dayDigests.firstOrNull { it.slot == slot.name },
                            onOpenDigest = onOpenDigest
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCard(
    month: YearMonth,
    datesWithDigests: Set<LocalDate>,
    selectedDate: LocalDate?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val a = LocalAlmanac.current
    val today = LocalDate.now()
    AlmanacCard(fill = a.card, modifier = Modifier.fillMaxWidth(), radius = 20) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.KeyboardArrowLeft, "Previous month", tint = a.ink,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onPrev)
                )
                Text(
                    MONTH_FMT.format(month), style = display(17), color = a.ink,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Outlined.KeyboardArrowRight, "Next month", tint = a.ink,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onNext)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                WEEKDAYS.forEach { wd ->
                    Text(
                        wd, style = body(11.0, FontWeight.Medium), color = a.inkLow,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            monthWeeks(month).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        DayCell(
                            date = date,
                            hasDigests = date != null && date in datesWithDigests,
                            selected = date != null && date == selectedDate,
                            isToday = date == today,
                            onClick = { if (date != null) onSelect(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    hasDigests: Boolean,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val a = LocalAlmanac.current
    Box(modifier = modifier.aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
        if (date == null) return@Box
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) a.accent else Color.Transparent)
                .then(if (isToday && !selected) Modifier.border(1.dp, a.line2, CircleShape) else Modifier)
                .clickable(enabled = hasDigests, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfMonth.toString(),
                    style = body(13.0, if (hasDigests) FontWeight.SemiBold else FontWeight.Normal),
                    color = when {
                        selected -> a.accentInk
                        hasDigests -> a.ink
                        else -> a.faint
                    }
                )
                if (hasDigests && !selected) {
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(a.accent))
                }
            }
        }
    }
}

@Composable
private fun DaySlotRow(slot: DigestSlot, digest: Digest?, onOpenDigest: (String) -> Unit) {
    val a = LocalAlmanac.current
    val clickable = digest != null
    AlmanacCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpenDigest(digest!!.id) } else Modifier)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${slot.label} Digest", style = display(16),
                    color = if (clickable) a.ink else a.inkLow
                )
                Text(
                    when {
                        digest == null -> "not delivered"
                        digest.itemCount == 0 -> "quiet window"
                        else -> "${digest.itemCount} stories · ${digest.sourceCount} sources" +
                            if (digest.openedAt == null) " · unread" else ""
                    },
                    style = body(11.5), color = a.inkMed
                )
            }
            if (digest != null && digest.pipelineMode == "HEURISTIC" && digest.itemCount > 0) {
                Text("basic grouping", style = body(10.5), color = a.faint)
            }
        }
    }
}
