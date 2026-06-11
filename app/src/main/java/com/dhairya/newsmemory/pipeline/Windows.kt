package com.dhairya.newsmemory.pipeline

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The three digest slots. Code letter is the window_id suffix (EDD §5.1). */
enum class DigestSlot(val code: String, val label: String) {
    MORNING("M", "Morning"),
    EVENING("E", "Evening"),
    NIGHT("N", "Night");

    companion object {
        fun fromCode(code: String): DigestSlot = entries.first { it.code == code }
    }
}

/** User-configured digest delivery times, minutes from midnight. Defaults per EDD §5.1. */
data class DigestTimes(
    val morningMinutes: Int = 8 * 60 + 30,   // 08:30
    val eveningMinutes: Int = 18 * 60,       // 18:00
    val nightMinutes: Int = 22 * 60 + 30     // 22:30
) {
    fun minutesFor(slot: DigestSlot): Int = when (slot) {
        DigestSlot.MORNING -> morningMinutes
        DigestSlot.EVENING -> eveningMinutes
        DigestSlot.NIGHT -> nightMinutes
    }
}

/**
 * Window math (EDD §5.1). Windows are defined by the three delivery times:
 *   MORNING delivers at morning time, covering night-time → morning-time (overnight),
 *   EVENING covers morning-time → evening-time,
 *   NIGHT covers evening-time → night-time.
 * window_id = "YYYY-MM-DD-{M|E|N}" where the date is the delivery date, and is the
 * idempotency key for the whole pipeline.
 */
object WindowCalculator {

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    /** The window a notification posted at [at] belongs to — i.e. the next digest to cover it. */
    fun windowIdFor(at: Instant, times: DigestTimes, zone: ZoneId): String {
        val dt = LocalDateTime.ofInstant(at, zone)
        val minutes = dt.hour * 60 + dt.minute
        return when {
            minutes < times.morningMinutes ->
                id(dt.toLocalDate(), DigestSlot.MORNING)
            minutes < times.eveningMinutes ->
                id(dt.toLocalDate(), DigestSlot.EVENING)
            minutes < times.nightMinutes ->
                id(dt.toLocalDate(), DigestSlot.NIGHT)
            else ->                                          // after night delivery → tomorrow's morning
                id(dt.toLocalDate().plusDays(1), DigestSlot.MORNING)
        }
    }

    /** [start, end) epoch millis for a window id, under the given configured times. */
    fun windowBounds(windowId: String, times: DigestTimes, zone: ZoneId): Pair<Long, Long> {
        val (date, slot) = parse(windowId)
        val end = date.atTime(timeOf(times.minutesFor(slot))).atZone(zone).toInstant().toEpochMilli()
        val start = when (slot) {
            DigestSlot.MORNING ->                            // overnight: previous day's night time
                date.minusDays(1).atTime(timeOf(times.nightMinutes)).atZone(zone)
                    .toInstant().toEpochMilli()
            DigestSlot.EVENING ->
                date.atTime(timeOf(times.morningMinutes)).atZone(zone).toInstant().toEpochMilli()
            DigestSlot.NIGHT ->
                date.atTime(timeOf(times.eveningMinutes)).atZone(zone).toInstant().toEpochMilli()
        }
        return start to end
    }

    /** Delivery instant for a window id. */
    fun deliveryTime(windowId: String, times: DigestTimes, zone: ZoneId): Long =
        windowBounds(windowId, times, zone).second

    /** The next window id whose delivery time is strictly after [now]. */
    fun nextWindowId(now: Instant, times: DigestTimes, zone: ZoneId): String {
        val dt = LocalDateTime.ofInstant(now, zone)
        val minutes = dt.hour * 60 + dt.minute
        return when {
            minutes < times.morningMinutes -> id(dt.toLocalDate(), DigestSlot.MORNING)
            minutes < times.eveningMinutes -> id(dt.toLocalDate(), DigestSlot.EVENING)
            minutes < times.nightMinutes -> id(dt.toLocalDate(), DigestSlot.NIGHT)
            else -> id(dt.toLocalDate().plusDays(1), DigestSlot.MORNING)
        }
    }

    /** All window ids whose delivery time falls in (since, until] — catch-up scanning. */
    fun windowIdsBetween(since: Instant, until: Instant, times: DigestTimes, zone: ZoneId): List<String> {
        val out = mutableListOf<String>()
        var cursor = nextWindowId(since, times, zone)
        while (deliveryTime(cursor, times, zone) <= until.toEpochMilli()) {
            out.add(cursor)
            cursor = nextWindowId(
                Instant.ofEpochMilli(deliveryTime(cursor, times, zone)), times, zone
            )
        }
        return out
    }

    fun parse(windowId: String): Pair<LocalDate, DigestSlot> {
        val date = LocalDate.parse(windowId.substring(0, 10), dateFmt)
        val slot = DigestSlot.fromCode(windowId.substring(11))
        return date to slot
    }

    private fun id(date: LocalDate, slot: DigestSlot): String =
        "${date.format(dateFmt)}-${slot.code}"

    private fun timeOf(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)
}
