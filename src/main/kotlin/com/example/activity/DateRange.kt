package com.example.activity

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * An inclusive range of calendar days in the user's own time zone.
 *
 * Dates are the whole point of this feature, so they are handled here rather than left to git:
 *
 *  - `git log --since/--until` filters on the **committer** date, but what the user did is the
 *    **author** date. A rebase or amend moves the committer date and the two disagree, so git is
 *    used only as a coarse pre-filter and the exact test happens in [contains].
 *  - `%ad` with `--date=short` renders in the *author's* recorded time zone. A commit written at
 *    23:30 in one zone lands on a different calendar day elsewhere, so timestamps are captured as
 *    absolute epoch seconds and converted here.
 */
data class DateRange(val since: LocalDate, val until: LocalDate, val zone: ZoneId = ZoneId.systemDefault()) {

    /** True when [epochSeconds] falls on any day in the range, read in [zone]. */
    fun contains(epochSeconds: Long): Boolean {
        val day = dayOf(epochSeconds)
        return !day.isBefore(since) && !day.isAfter(until)
    }

    fun dayOf(epochSeconds: Long): LocalDate =
        Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

    fun format(epochSeconds: Long): String = DATE.format(dayOf(epochSeconds))

    /**
     * The window handed to git. It is widened because git filters on the committer date while we
     * filter on the author date; without the margin a rebased commit authored inside the range
     * would never reach [contains].
     */
    fun gitSince(): String = since.minusDays(MARGIN_DAYS).toString()

    fun gitUntil(today: LocalDate = LocalDate.now(zone)): String {
        val end = if (until.isAfter(today)) today else until
        return end.plusDays(MARGIN_DAYS).toString()
    }

    val days: Long get() = java.time.temporal.ChronoUnit.DAYS.between(since, until) + 1

    companion object {
        /** Days of slack on each side of the git query; see [gitSince]. */
        const val MARGIN_DAYS = 3L

        private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * Builds a range from two yyyy-MM-dd strings, tolerating a reversed pair rather than
         * silently returning nothing. Returns null when either date cannot be read.
         */
        fun of(since: String, until: String, zone: ZoneId = ZoneId.systemDefault()): DateRange? {
            val from = parse(since) ?: return null
            val to = parse(until) ?: return null
            return if (from.isAfter(to)) DateRange(to, from, zone) else DateRange(from, to, zone)
        }

        private fun parse(value: String): LocalDate? =
            runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    }
}
