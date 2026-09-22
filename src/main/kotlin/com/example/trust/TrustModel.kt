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
 * Whether anything else in the project mentions this file.
 *
 * Static analysis alone cannot tell you a thing is unused: one caller is enough to make an
 * inspection call it "used", even when that caller is itself dead. Runtime alone cannot tell
 * you why nothing ran: an untested path and an orphan look identical in a coverage report.
 * Putting the two together is the only way to say "delete this" and mean it.
 */
enum class Reachability {

    /** Some other file names this one, so it is wired in even if no run reached it. */
    REFERENCED,

    /** Nothing anywhere names it. Combined with never having run, that is dead code. */
    UNREFERENCED,

    /** Not looked up yet, or the indexes were not ready. Never treated as dead. */
    UNKNOWN,
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
    /**
     * Filled in separately from the coverage read, because it costs an index lookup and only
     * matters for the handful of files that never ran at all.
     */
    val reachability: Reachability = Reachability.UNKNOWN,
) {

    val unprovenLines: Int get() = unproven.sumOf { it.lineCount }

    val staleLines: Int get() = stale.sumOf { it.lineCount }

    /** Nothing to paint, so the painter can skip the file without building any highlighters. */
    val isClean: Boolean get() = unproven.isEmpty() && stale.isEmpty()

    val fileName: String get() = path.substringAfterLast('/')

    /** "" for a file at the project root, which groups under [TrustLayers.ROOT]. */
    val folder: String get() = path.substringBeforeLast('/', "")

    /**
     * Nothing ever ran it and nothing anywhere refers to it.
     *
     * Both halves are required. A file that never ran but is imported somewhere is untested,
     * which is a reason to write a test. Only when neither the runtime nor the rest of the
     * codebase has any use for it is deleting it the honest advice.
     */
    val isDead: Boolean
        get() = totalLines > 0 &&
            unprovenLines == totalLines &&
            reachability == Reachability.UNREFERENCED

    /** Wired in, but no run has ever reached it. The case that wants a test written. */
    val isUnproven: Boolean get() = unprovenLines > 0 && !isDead

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

/**
 * One folder's worth of files, rolled up.
 *
 * This is the unit that turns a list into a finding. "34 files have unproven lines" is a
 * chore; "every agent and every tool in this project has never executed" is a sentence
 * somebody repeats afterwards, and it is the same data.
 */
data class LayerSummary(
    /** What to show: the folder with the part every folder shares stripped off the front. */
    val name: String,
    /** The full project-relative folder, kept for navigation and tooltips. */
    val folder: String,
    val files: List<FileTrust>,
) {

    val totalLines: Int get() = files.sumOf { it.totalLines }

    val unprovenLines: Int get() = files.sumOf { it.unprovenLines }

    val deadFiles: Int get() = files.count { it.isDead }

    fun percentUnproven(): Int =
        if (totalLines <= 0) 0 else (unprovenLines * 100) / totalLines
}

/**
 * Groups files by the folder they sit in, then throws away the prefix they all share.
 *
 * Grouping by the immediate parent rather than by a list of known layer names is what makes
 * this work on a codebase nobody has seen: `app/services` and `src/main/java/.../service`
 * both reduce to something a human recognises, with no configuration and no language check.
 */
object TrustLayers {

    const val ROOT = "(root)"

    /**
     * [namingBasis] is the set the shared prefix is measured on, and it should always be
     * everything known, whatever subset is being drawn. Measured on the subset instead, a
     * filter down to two files in `app/tools` and `app/models` would find `app` shared and
     * strip it, and the same folder would be called `app/tools` in one view and `tools` in
     * the next. Names that move when you filter are names nobody trusts.
     */
    fun of(
        files: Collection<FileTrust>,
        namingBasis: Collection<FileTrust> = files,
    ): List<LayerSummary> {
        if (files.isEmpty()) return emptyList()

        val prefix = commonPrefix(namingBasis.map { it.folder }.toSet())

        return files.groupBy { it.folder }.map { (folder, group) ->
            LayerSummary(name = display(folder, prefix), folder = folder, files = group)
        }.sortedByDescending { it.unprovenLines }
    }

    /**
     * The longest run of leading path segments every folder shares.
     *
     * Stripping it is what turns `backend/app/services` and `backend/app/agents` into
     * `services` and `agents`. Without it every label starts with the same nine characters
     * and the eye has to skip past them on every row.
     */
    internal fun commonPrefix(folders: Collection<String>): List<String> {
        val split = folders.filter { it.isNotEmpty() }.map { it.split('/') }
        if (split.size < 2) return emptyList()

        var shared = split.first()
        for (parts in split.drop(1)) {
            shared = shared.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
            if (shared.isEmpty()) break
        }
        return shared
    }

    private fun display(folder: String, prefix: List<String>): String {
        if (folder.isEmpty()) return ROOT
        val parts = folder.split('/')
        val stripped = if (parts.size >= prefix.size && parts.take(prefix.size) == prefix) {
            parts.drop(prefix.size)
        } else {
            parts
        }
        // The folder that *is* the prefix keeps its own last name: files sitting directly in
        // `app` are labelled `app`, not left with nothing.
        return stripped.joinToString("/").ifEmpty { parts.last() }
    }
}
