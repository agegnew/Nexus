package com.example.trust

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The real answer: which lines a run actually executed, read from the coverage report the
 * project's own test run wrote.
 *
 * Reading the report rather than driving the IDE's coverage engine is a deliberate trade. The
 * report is the artefact every language already agrees on, it is produced by the command the
 * team runs anyway, it survives being generated on CI instead of locally, and it needs no
 * experimental API. The cost is that somebody has to run the tests with coverage once, which
 * is exactly the habit this feature is trying to encourage.
 *
 * Two formats cover almost everything a team will have lying around:
 *
 *  - **Cobertura XML** (`coverage.xml`), written by `coverage xml` for Python and by many others
 *  - **LCOV** (`lcov.info`), written by jest, vitest, c8, nyc and friends
 */
class CoverageReportSource : ExecutionSource {

    private val logger = Logger.getInstance(CoverageReportSource::class.java)

    /** Report path and mtime, so a stale cache is detected without re-parsing. */
    private var cachedReport: File? = null
    private var cachedStamp: Long = -1
    private var cached: Map<String, FileTrust> = emptyMap()

    override val label: String
        get() = cachedReport?.name ?: "coverage report"

    /** Null until a report has been read, used by the UI to say how old the answer is. */
    val reportedAt: Long? get() = cachedReport?.let { cachedStamp }

    val reportPath: String? get() = cachedReport?.path

    override fun trustFor(project: Project, file: VirtualFile): FileTrust? {
        val base = project.basePath ?: return null
        val relative = file.path.takeIf { it.startsWith(base) }?.removePrefix(base)?.trimStart('/')
            ?: return null
        return all(project)[relative]
    }

    override fun all(project: Project): Map<String, FileTrust> {
        val base = project.basePath ?: return emptyMap()
        return load(base)
    }

    /** Forces the next read to re-parse, called when a run finishes. */
    @Synchronized
    fun invalidate() {
        cachedStamp = -1
    }

    /** A human sentence for the tab: where the numbers came from and how old they are. */
    override fun describe(project: Project): String? {
        val report = cachedReport ?: return null
        val base = project.basePath
        val shown = if (base != null && report.path.startsWith(base)) {
            report.path.removePrefix(base).trimStart('/')
        } else {
            report.name
        }
        return "from $shown, ${ago(cachedStamp)}"
    }

    @Synchronized
    private fun load(base: String): Map<String, FileTrust> {
        val report = findReport(File(base))
        if (report == null) {
            cachedReport = null
            cached = emptyMap()
            return emptyMap()
        }

        val stamp = report.lastModified()
        if (report == cachedReport && stamp == cachedStamp) return cached

        cached = runCatching { parse(report, File(base)) }
            .onFailure { logger.warn("Nexus Trust could not read ${report.path}", it) }
            .getOrDefault(emptyMap())
        cachedReport = report
        cachedStamp = stamp
        logger.info("Nexus Trust read ${cached.size} file(s) of coverage from ${report.path}")
        return cached
    }

    /**
     * The newest report in the usual places.
     *
     * Newest rather than first, because a repository can easily hold an old report from a
     * different sub-project, and the freshest one is the one that matches what just ran.
     */
    private fun findReport(root: File): File? =
        SEARCH_DIRS
            .flatMap { dir -> REPORT_NAMES.map { File(File(root, dir), it) } }
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun parse(report: File, projectRoot: File): Map<String, FileTrust> {
        val hits: Map<String, MutableMap<Int, Int>> = when {
            report.name.endsWith(".xml") -> parseCobertura(report)
            else -> parseLcov(report)
        }

        val reportStamp = report.lastModified()

        // Canonical on both sides or neither. On macOS a project under /var resolves to
        // /private/var, and comparing one against the other silently yields absolute paths
        // that match nothing in the editor.
        val root = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)

        return hits.mapNotNull { (reported, lines) ->
            // A file with no executable lines has no verdict to give. Reports list every
            // __init__.py and every empty module, and counting them inflates "N files"
            // with entries that could neither pass nor fail anything.
            if (lines.isEmpty()) return@mapNotNull null
            val resolved = resolve(reported, report, root) ?: return@mapNotNull null
            val relative = resolved.path.removePrefix(root.path).trimStart('/')

            val never = lines.filterValues { it <= 0 }.keys.sorted()
            val ranges = LineRange.merge(never.map { LineRange(it, it) })

            relative to FileTrust(
                path = relative,
                unproven = ranges,
                // Executable lines, not physical ones: "39% of the code that can run, never ran"
                // is a claim about behaviour, and counting blank lines would only dilute it.
                totalLines = lines.size,
                changedSinceRun = resolved.lastModified() > reportStamp,
            )
        }.toMap()
    }

    /** `<class filename="app/main.py">` then `<line number="12" hits="0"/>` until the next class. */
    private fun parseCobertura(report: File): Map<String, MutableMap<Int, Int>> {
        val result = LinkedHashMap<String, MutableMap<Int, Int>>()
        var current: MutableMap<Int, Int>? = null

        report.forEachLine { line ->
            FILENAME.find(line)?.let { match ->
                current = result.getOrPut(match.groupValues[1]) { LinkedHashMap() }
            }
            LINE_HITS.findAll(line).forEach { match ->
                val number = match.groupValues[1].toIntOrNull() ?: return@forEach
                val count = match.groupValues[2].toIntOrNull() ?: return@forEach
                // Max, because a line can appear more than once in a merged report and any
                // execution at all counts as proven.
                current?.merge(number, count, ::maxOf)
            }
        }
        return result
    }

    /** `SF:<path>`, then `DA:<line>,<hits>` per line, then `end_of_record`. */
    private fun parseLcov(report: File): Map<String, MutableMap<Int, Int>> {
        val result = LinkedHashMap<String, MutableMap<Int, Int>>()
        var current: MutableMap<Int, Int>? = null

        report.forEachLine { line ->
            when {
                line.startsWith("SF:") ->
                    current = result.getOrPut(line.removePrefix("SF:").trim()) { LinkedHashMap() }

                line.startsWith("DA:") -> {
                    val parts = line.removePrefix("DA:").split(',')
                    val number = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (number != null && count != null) current?.merge(number, count, ::maxOf)
                }

                line.startsWith("end_of_record") -> current = null
            }
        }
        return result
    }

    /**
     * Turns the path a report mentions into a file on this disk.
     *
     * Reports are written relative to wherever the test command ran, which is rarely the project
     * root: `coverage.xml` inside `backend/` names `app/main.py`, meaning `backend/app/main.py`.
     * Absolute paths from CI point at a machine that is not this one, so those are matched by
     * their tail instead.
     */
    private fun resolve(reported: String, report: File, projectRoot: File): File? {
        val cleaned = reported.removePrefix("./").trim()
        if (cleaned.isEmpty()) return null

        val candidates = buildList {
            add(File(projectRoot, cleaned))
            report.parentFile?.let { add(File(it, cleaned)) }
            report.parentFile?.parentFile?.let { add(File(it, cleaned)) }
            add(File(cleaned))
        }
        candidates.firstOrNull { it.isFile }?.let { return it.canonicalFile }

        // Last resort for CI paths: keep trimming leading segments until something exists here.
        var tail = cleaned
        while (tail.contains('/')) {
            tail = tail.substringAfter('/')
            val guess = File(projectRoot, tail)
            if (guess.isFile) return guess.canonicalFile
        }
        return null
    }

    private fun ago(stamp: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - stamp)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minute(s) ago"
            minutes < 60 * 24 -> "${minutes / 60} hour(s) ago"
            else -> "${minutes / (60 * 24)} day(s) ago"
        }
    }

    companion object {
        private val FILENAME = Regex("""filename="([^"]+)"""")
        private val LINE_HITS = Regex("""<line[^>]*number="(\d+)"[^>]*hits="(\d+)"""")

        /** Where test runners drop reports, in the order a human would look. */
        private val SEARCH_DIRS = listOf(
            "", "coverage", "backend", "backend/coverage", "frontend/coverage",
            "htmlcov", "build/reports/coverage", "target/site/cobertura", ".nexus",
        )

        internal val REPORT_NAMES = listOf("coverage.xml", "cobertura.xml", "lcov.info", "coverage-final.info")
    }
}
