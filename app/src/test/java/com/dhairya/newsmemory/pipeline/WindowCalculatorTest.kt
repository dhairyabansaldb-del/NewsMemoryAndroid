package com.dhairya.newsmemory.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class WindowCalculatorTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val times = DigestTimes()  // 08:30 / 18:00 / 22:30

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    @Test
    fun `early morning belongs to same-day morning window`() {
        assertEquals("2026-06-10-M", WindowCalculator.windowIdFor(at(2026, 6, 10, 6, 0), times, zone))
    }

    @Test
    fun `daytime belongs to evening window`() {
        assertEquals("2026-06-10-E", WindowCalculator.windowIdFor(at(2026, 6, 10, 12, 0), times, zone))
    }

    @Test
    fun `after evening belongs to night window`() {
        assertEquals("2026-06-10-N", WindowCalculator.windowIdFor(at(2026, 6, 10, 21, 0), times, zone))
    }

    @Test
    fun `late night rolls into next morning window`() {
        assertEquals("2026-06-11-M", WindowCalculator.windowIdFor(at(2026, 6, 10, 23, 45), times, zone))
    }

    @Test
    fun `boundary minute belongs to the next window`() {
        // 08:30 exactly: morning digest has fired; capture goes to evening window
        assertEquals("2026-06-10-E", WindowCalculator.windowIdFor(at(2026, 6, 10, 8, 30), times, zone))
        // 22:30 exactly → tomorrow's morning
        assertEquals("2026-06-11-M", WindowCalculator.windowIdFor(at(2026, 6, 10, 22, 30), times, zone))
    }

    @Test
    fun `morning window spans overnight`() {
        val (start, end) = WindowCalculator.windowBounds("2026-06-11-M", times, zone)
        assertEquals(at(2026, 6, 10, 22, 30).toEpochMilli(), start)
        assertEquals(at(2026, 6, 11, 8, 30).toEpochMilli(), end)
    }

    @Test
    fun `evening window spans working day`() {
        val (start, end) = WindowCalculator.windowBounds("2026-06-10-E", times, zone)
        assertEquals(at(2026, 6, 10, 8, 30).toEpochMilli(), start)
        assertEquals(at(2026, 6, 10, 18, 0).toEpochMilli(), end)
    }

    @Test
    fun `changing digest times reshapes neighbouring windows`() {
        val custom = DigestTimes(morningMinutes = 7 * 60, eveningMinutes = 19 * 60, nightMinutes = 23 * 60)
        val (start, end) = WindowCalculator.windowBounds("2026-06-10-E", custom, zone)
        assertEquals(at(2026, 6, 10, 7, 0).toEpochMilli(), start)
        assertEquals(at(2026, 6, 10, 19, 0).toEpochMilli(), end)
    }

    @Test
    fun `next window id after night delivery is tomorrow morning`() {
        assertEquals("2026-06-11-M", WindowCalculator.nextWindowId(at(2026, 6, 10, 22, 31), times, zone))
    }

    @Test
    fun `catch-up enumerates every missed window`() {
        val missed = WindowCalculator.windowIdsBetween(
            at(2026, 6, 10, 8, 0), at(2026, 6, 11, 9, 0), times, zone
        )
        assertEquals(listOf("2026-06-10-M", "2026-06-10-E", "2026-06-10-N", "2026-06-11-M"), missed)
    }

    @Test
    fun `round trip parse`() {
        val (date, slot) = WindowCalculator.parse("2026-06-10-N")
        assertEquals(2026, date.year)
        assertEquals(6, date.monthValue)
        assertEquals(10, date.dayOfMonth)
        assertEquals(DigestSlot.NIGHT, slot)
    }
}
