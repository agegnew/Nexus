package com.example.trust

/**
 * What the trust light measures.
 *
 * Deliberately **not** "is this code correct" and **not** "did a human read it". Those are
 * either impossible to know or insulting to guess at. The only claim made here is one the
 * machine can actually back up: has anything ever executed these lines.
 */
enum class TrustLevel {

    /** Something ran through these lines and finished green. */
    PROVEN,

    /** It was proven once, but the code has changed since, so the proof is out of date. */
    STALE,

    /** Nothing has ever executed these lines. This is what gets painted. */
    UNPROVEN,
}

/**
 * A closed range of **1-based** line numbers, the way an editor shows them.
 *
 * Inclusive on both ends because every source of this data (coverage reports, git diffs,
 * the fixture file) counts that way, and converting at the edges is where off-by-ones live.
 */
data class LineRange(val start: Int, val end: Int) {

    val lineCount: Int get() = (end - start + 1).coerceAtLeast(0)

    fun contains(line: Int): Boolean = line in start..end

    companion object {
        /** Collapses touching or overlapping ranges, so the paint has no seams. */
        fun merge(ranges: List<LineRange>): List<LineRange> {
            if (ranges.size < 2) return ranges
            val sorted = ranges.sortedBy { it.start }
            val merged = ArrayList<LineRange>(sorted.size)
            var current = sorted.first()
            for (next in sorted.drop(1)) {
                current = if (next.start <= current.end + 1) {
                    LineRange(current.start, maxOf(current.end, next.end))
                } else {
                    merged.add(current)
                    next
                }
            }
            merged.add(current)
            return merged
        }
    }
}

/**
 * The verdict for one file.
 *
 * [path] is relative to the project root, because absolute paths differ on every machine and
 * this data is meant to survive being written to disk and read back on someone else's laptop.
 */
data class FileTrust(
    val path: String,
    val unproven: List<LineRange> = emptyList(),
    val stale: List<LineRange> = emptyList(),
    val totalLines: Int = 0,
    /**
     * The file was edited after the run that produced this verdict.
     *
     * Kept as a flag rather than by painting the whole file amber: the honest statement is
     * "this answer is older than the code", not "every line here is suspect".
     */
    val changedSinceRun: Boolean = false,
) {

    val unprovenLines: Int get() = unproven.sumOf { it.lineCount }

    val staleLines: Int get() = stale.sumOf { it.lineCount }

    /** Nothing to paint, so the painter can skip the file without building any highlighters. */
    val isClean: Boolean get() = unproven.isEmpty() && stale.isEmpty()

    /** Whole percent of the file that has never run, for the status bar. */
    fun percentUnproven(): Int =
        if (totalLines <= 0) 0 else (unprovenLines * 100) / totalLines

    fun levelAt(line: Int): TrustLevel = when {
        unproven.any { it.contains(line) } -> TrustLevel.UNPROVEN
        stale.any { it.contains(line) } -> TrustLevel.STALE
        else -> TrustLevel.PROVEN
    }

    companion object {
        fun clean(path: String, totalLines: Int) = FileTrust(path, emptyList(), emptyList(), totalLines)
    }
}
