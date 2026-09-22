package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DateRangeTest {

    private val dubai = ZoneId.of("Asia/Dubai")       // UTC+4, no DST
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    private fun epoch(zone: ZoneId, date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, minute), zone).toEpochSecond()

    @Test
    fun `both ends of the range are inclusive`() {
        val range = DateRange.of("2026-09-14", "2026-09-20", dubai)!!

        assertTrue(range.contains(epoch(dubai, "2026-09-14", 0, 0)))
        assertTrue(range.contains(epoch(dubai, "2026-09-20", 23, 59)))
        assertFalse(range.contains(epoch(dubai, "2026-09-13", 23, 59)))
        assertFalse(range.contains(epoch(dubai, "2026-09-21", 0, 0)))
    }

    @Test
    fun `a late night commit counts as that day in the users zone`() {
        val range = DateRange.of("2026-09-20", "2026-09-20", dubai)!!
        // 23:30 in Dubai is still the 20th locally, though it is already the 21st in UTC+6.
        val lateNight = epoch(dubai, "2026-09-20", 23, 30)

        assertTrue(range.contains(lateNight))
        assertEquals(LocalDate.parse("2026-09-20"), range.dayOf(lateNight))
    }

    @Test
    fun `the same instant can be different days in different zones`() {
        // 2026-09-21 02:00 in Dubai is 2026-09-20 15:00 in Los Angeles.
        val instant = epoch(dubai, "2026-09-21", 2, 0)

        assertEquals(LocalDate.parse("2026-09-21"), DateRange.of("2026-01-01", "2026-12-31", dubai)!!.dayOf(instant))
        assertEquals(LocalDate.parse("2026-09-20"), DateRange.of("2026-01-01", "2026-12-31", losAngeles)!!.dayOf(instant))
    }

    @Test
    fun `the git window is widened so rebased commits still reach us`() {
        val range = DateRange.of("2026-09-14", "2026-09-20", dubai)!!

        // git filters on the committer date, which a rebase moves; the margin keeps them in range.
        assertEquals("2026-09-11", range.gitSince())
        assertEquals("2026-09-23", range.gitUntil(today = LocalDate.parse("2026-09-30")))
    }

    @Test
    fun `a future upper bound is clamped before the margin is added`() {
        val range = DateRange.of("2026-09-14", "2100-01-01", dubai)!!
        // git's date parser returns nothing for far-future dates, so it must never see one.
        assertEquals("2026-09-25", range.gitUntil(today = LocalDate.parse("2026-09-22")))
    }

    @Test
    fun `a reversed range is corrected rather than returning nothing`() {
        val range = DateRange.of("2026-09-20", "2026-09-14", dubai)!!

        assertEquals(LocalDate.parse("2026-09-14"), range.since)
        assertEquals(LocalDate.parse("2026-09-20"), range.until)
        assertEquals(7, range.days)
    }

    @Test
    fun `an unreadable date gives null instead of a silent empty result`() {
        assertNull(DateRange.of("not a date", "2026-09-20", dubai))
        assertNull(DateRange.of("2026-09-14", "", dubai))
    }

    @Test
    fun `a single day range covers exactly that day`() {
        val range = DateRange.of("2026-09-22", "2026-09-22", dubai)!!
        assertEquals(1, range.days)
        assertTrue(range.contains(epoch(dubai, "2026-09-22", 12)))
        assertFalse(range.contains(epoch(dubai, "2026-09-23", 0)))
    }
}
