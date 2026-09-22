package com.example.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the paint and the status bar both depend on.
 *
 * Small on purpose: these are the functions where an off-by-one shows up as a band of colour
 * one line adrift, which nobody notices in a demo and everybody notices in use.
 */
class TrustModelTest {

    @Test
    fun `touching ranges become one band`() {
        val merged = LineRange.merge(
            listOf(LineRange(10, 12), LineRange(13, 15), LineRange(30, 31)),
        )

        assertEquals(listOf(LineRange(10, 15), LineRange(30, 31)), merged)
    }

    @Test
    fun `overlapping and unsorted ranges still merge`() {
        val merged = LineRange.merge(
            listOf(LineRange(30, 40), LineRange(5, 9), LineRange(35, 50)),
        )

        assertEquals(listOf(LineRange(5, 9), LineRange(30, 50)), merged)
    }

    @Test
    fun `a gap of two lines is left alone`() {
        val merged = LineRange.merge(listOf(LineRange(1, 2), LineRange(5, 6)))

        assertEquals(listOf(LineRange(1, 2), LineRange(5, 6)), merged)
    }

    @Test
    fun `percentage is of executable lines, and never divides by zero`() {
        val trust = FileTrust(
            path = "app/main.py",
            unproven = listOf(LineRange(1, 25)),
            totalLines = 100,
        )

        assertEquals(25, trust.percentUnproven())
        assertEquals(0, FileTrust("empty.py", totalLines = 0).percentUnproven())
    }

    @Test
    fun `a line is judged by the band it falls in`() {
        val trust = FileTrust(
            path = "app/main.py",
            unproven = listOf(LineRange(10, 20)),
            stale = listOf(LineRange(30, 32)),
            totalLines = 50,
        )

        assertEquals(TrustLevel.UNPROVEN, trust.levelAt(15))
        assertEquals(TrustLevel.STALE, trust.levelAt(31))
        assertEquals(TrustLevel.PROVEN, trust.levelAt(40))
    }

    @Test
    fun `a file with nothing to paint says so, so the painter can skip it`() {
        assertTrue(FileTrust("app/clean.py", totalLines = 10).isClean)
        assertFalse(FileTrust("app/dirty.py", unproven = listOf(LineRange(1, 1))).isClean)
    }

    @Test
    fun `dead needs both halves, never run and nothing pointing at it`() {
        val whole = listOf(LineRange(1, 40))

        val orphan = FileTrust("app/gone.py", whole, totalLines = 40, reachability = Reachability.UNREFERENCED)
        val untested = FileTrust("app/new.py", whole, totalLines = 40, reachability = Reachability.REFERENCED)

        assertTrue(orphan.isDead)
        assertFalse(untested.isDead)
        // Untested is the case that wants a test written, so it must not be filed as deletable.
        assertTrue(untested.isUnproven)
        assertFalse(orphan.isUnproven)
    }

    @Test
    fun `an unanswered lookup never counts as dead`() {
        // Dumb mode and a failed search both land here, and either one telling somebody to
        // delete working code would be the single worst thing this feature could do.
        val unknown = FileTrust(
            "app/maybe.py",
            listOf(LineRange(1, 12)),
            totalLines = 12,
            reachability = Reachability.UNKNOWN,
        )

        assertFalse(unknown.isDead)
        assertTrue(unknown.isUnproven)
    }

    @Test
    fun `a file that ran even once is not dead, whoever references it`() {
        val partly = FileTrust(
            "app/half.py",
            listOf(LineRange(1, 5)),
            totalLines = 20,
            reachability = Reachability.UNREFERENCED,
        )

        assertFalse(partly.isDead)
    }
}
