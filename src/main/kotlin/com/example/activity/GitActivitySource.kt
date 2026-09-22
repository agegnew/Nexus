package com.example.activity

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Where a range of commits comes from. Git is the only implementation today; a source backed by the
 * IDE's local history can be added later without touching the summarizer or the UI.
 */
interface ActivitySource {
    fun commits(request: ActivityRequest): List<Commit>
}

/**
 * Reads history by shelling out to `git log`. That avoids a dependency on git4idea or JGit, and the
 * porcelain format below is stable across git versions.
 *
 * Fields are separated by US (0x1f) and records by RS (0x1e) so that multi-line commit bodies,
 * quotes and non-ASCII paths survive parsing intact.
 */
class GitActivitySource(
    private val repoRoot: File,
    private val maxCommits: Int = 500,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ActivitySource {

    override fun commits(request: ActivityRequest): List<Commit> {
        val range = DateRange.of(request.since, request.until, zone) ?: return emptyList()
        val command = mutableListOf(
            "git", "log",
            "--no-merges",
            "-n", maxCommits.toString(),
            // --raw carries the per-file status letter; --numstat carries the line counts. Raw rows
            // start with ':', so the two interleaved blocks are told apart while parsing.
            "--raw",
            "--numstat",
            "--since=${range.gitSince()}",
            "--until=${range.gitUntil()}",
            "--pretty=format:${RECORD}C$FIELD%H$FIELD%an$FIELD%ae$FIELD%at$FIELD%P$FIELD%s$FIELD%b$FIELD"
        )
        request.authorEmail?.takeIf { it.isNotBlank() }?.let { command += "--author=$it" }
        // git's own window filters on the committer date, so the exact day test is ours.
        return parse(run(command), zone)
            .filter { range.contains(it.timestamp) }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Work that exists on disk but not in history: staged, unstaged and untracked files.
     *
     * Line counts come from `git diff` for tracked files; an untracked file has nothing to diff
     * against, so its lines are counted directly. Dates come from the file's modification time,
     * which is the only timestamp uncommitted work has.
     */
    fun pendingChanges(range: DateRange): List<PendingChange> {
        val statuses = runCatching { run(listOf("git", "status", "--porcelain=v1", "-z", "--untracked-files=all")) }
            .getOrElse { return emptyList() }
        if (statuses.isBlank()) return emptyList()

        val counts = numstatOf(listOf("git", "diff", "HEAD", "--numstat", "-z")) +
            numstatOf(listOf("git", "diff", "--cached", "--numstat", "-z"))

        return parseStatus(statuses)
            .map { entry ->
                val file = File(repoRoot, entry.path)
                val exists = file.isFile
                val counted = counts[entry.path]
                    ?: if (entry.status == "untracked" && exists) FileChange(entry.path, countLines(file), 0) else null
                // A deleted file has no modification time of its own, but removing it touches the
                // directory that held it, which dates the deletion closely enough to be useful.
                val exact = if (exists) file.lastModified() / 1000 else 0L
                val inferred = if (exact == 0L) (file.parentFile?.takeIf { it.isDirectory }?.lastModified() ?: 0L) / 1000 else 0L
                val modified = if (exact > 0) exact else inferred
                val stamp = if (modified > 0) Instant.ofEpochSecond(modified).atZone(zone) else null
                PendingChange(
                    path = entry.path,
                    status = entry.status,
                    added = counted?.added ?: 0,
                    deleted = counted?.deleted ?: 0,
                    staged = entry.staged,
                    date = stamp?.toLocalDate()?.toString() ?: "",
                    time = stamp?.let(TIME::format) ?: "",
                    timestamp = modified,
                    approximate = exact == 0L && modified > 0
                )
            }
            // A deleted file has no modification time left, so it is always shown as current work.
            .filter { it.timestamp == 0L || range.contains(it.timestamp) }
            .sortedByDescending { it.timestamp }
    }

    private fun numstatOf(command: List<String>): Map<String, FileChange> =
        runCatching { run(command) }.getOrDefault("")
            .split('\u0000')
            .mapNotNull { line ->
                val columns = line.trim().split('\t')
                if (columns.size < 3 || columns[2].isBlank()) return@mapNotNull null
                val path = normalizePath(columns[2])
                path to FileChange(path, columns[0].toIntOrNull() ?: 0, columns[1].toIntOrNull() ?: 0)
            }
            .toMap()

    /** Counts lines without reading a huge or binary file into memory. */
    private fun countLines(file: File): Int {
        if (file.length() > MAX_COUNTED_BYTES) return 0
        return runCatching { file.useLines { lines -> lines.count() } }.getOrDefault(0)
    }

    /** The commit the cache keys on, so a summary is reused until the history moves. */
    fun head(): String = runCatching { run(listOf("git", "rev-parse", "HEAD")).trim() }.getOrDefault("")

    /** The email `git log --author` should match for "only my commits". */
    fun userEmail(): String = runCatching { run(listOf("git", "config", "user.email")).trim() }.getOrDefault("")

    private fun run(command: List<String>): String {
        check(repoRoot.isDirectory) { "Not a directory: $repoRoot" }
        val process = ProcessBuilder(command)
            .directory(repoRoot)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GitUnavailable("git log timed out")
        }
        if (process.exitValue() != 0) throw GitUnavailable(stderr.trim().ifBlank { "git log failed" })
        return stdout
    }

    /** Thrown when git is missing, the directory is not a repository, or the command fails. */
    class GitUnavailable(message: String) : RuntimeException(message)

    companion object {
        private const val RECORD = "\u001e"
        private const val FIELD = "\u001f"
        private const val MAX_COUNTED_BYTES = 2L * 1024 * 1024
        private val TIME: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm")

        /** One row of `git status --porcelain`. */
        data class StatusEntry(val path: String, val status: String, val staged: Boolean)

        /**
         * Parses `git status --porcelain=v1 -z`. The NUL form is used because it is the only one
         * that leaves paths with spaces, quotes or non-ASCII characters intact; a rename also emits
         * its old path as a separate NUL-terminated entry, which is consumed here.
         */
        fun parseStatus(output: String): List<StatusEntry> {
            val fields = output.split('\u0000').filter { it.isNotEmpty() }
            val entries = mutableListOf<StatusEntry>()
            var index = 0
            while (index < fields.size) {
                val field = fields[index]
                if (field.length < 4) { index++; continue }
                val index0 = field[0]
                val worktree = field[1]
                val path = field.substring(3)
                val renamed = index0 == 'R' || worktree == 'R'
                entries += StatusEntry(
                    path = path,
                    status = statusName(index0, worktree),
                    staged = index0 != ' ' && index0 != '?'
                )
                // A rename is followed by the original path; it is not a change of its own.
                index += if (renamed) 2 else 1
            }
            return entries
        }

        private fun statusName(index0: Char, worktree: Char): String = when {
            index0 == '?' || worktree == '?' -> "untracked"
            index0 == 'D' || worktree == 'D' -> "deleted"
            index0 == 'R' || worktree == 'R' -> "renamed"
            index0 == 'A' -> "added"
            else -> "modified"
        }

        /** True when [root] or one of its parents holds a .git entry. */
        fun isRepository(root: File): Boolean =
            generateSequence(root.absoluteFile) { it.parentFile }.any { File(it, ".git").exists() }

        /** Parses the output of the `git log` invocation above. Pure, so it is covered by tests. */
        fun parse(output: String, zone: ZoneId = ZoneId.systemDefault()): List<Commit> = output.split(RECORD)
            .asSequence()
            .map { it.trim('\n', '\r') }
            .filter { it.isNotBlank() }
            .mapNotNull { parseRecord(it, zone) }
            .toList()

        private fun parseRecord(record: String, zone: ZoneId): Commit? {
            val parts = record.split(FIELD)
            // marker, hash, author, email, date, parents, subject, body, then the numstat tail.
            if (parts.size < 9 || parts[0] != "C") return null
            val hash = parts[1]
            if (hash.length < 7) return null
            val timestamp = parts[4].trim().toLongOrNull() ?: return null
            return Commit(
                hash = hash,
                shortHash = hash.take(7),
                author = parts[2],
                email = parts[3],
                date = Instant.ofEpochSecond(timestamp).atZone(zone).toLocalDate().toString(),
                time = TIME.format(Instant.ofEpochSecond(timestamp).atZone(zone)),
                timestamp = timestamp,
                subject = parts[6],
                body = parts[7].trim(),
                merge = parts[5].trim().split(' ').filter { it.isNotBlank() }.size > 1,
                files = parseNumstat(parts[8])
            )
        }

        private fun parseNumstat(tail: String): List<FileChange> {
            val lines = tail.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            val statuses = lines.filter { it.startsWith(":") }.mapNotNull(::parseRawStatus).toMap()

            return lines.filterNot { it.startsWith(":") }.mapNotNull { line ->
                val columns = line.split('\t')
                if (columns.size < 3) return@mapNotNull null
                val path = normalizePath(columns.drop(2).joinToString("\t"))
                FileChange(
                    path = path,
                    added = columns[0].toIntOrNull() ?: 0,
                    deleted = columns[1].toIntOrNull() ?: 0,
                    binary = columns[0] == "-" || columns[1] == "-",
                    status = statuses[path] ?: "modified"
                )
            }
        }

        /** `:100644 100644 abc1234 def5678 M\tpath` — the status letter is the last tab-free field. */
        private fun parseRawStatus(line: String): Pair<String, String>? {
            val parts = line.split('\t')
            if (parts.size < 2) return null
            val letter = parts[0].trimEnd().substringAfterLast(' ').firstOrNull() ?: return null
            // A rename lists the old path then the new one; the new path is what we report.
            val path = normalizePath(parts.last())
            return path to statusLetter(letter)
        }

        private fun statusLetter(letter: Char): String = when (letter) {
            'A' -> "added"
            'D' -> "deleted"
            'R' -> "renamed"
            'C' -> "copied"
            else -> "modified"
        }

        private val renameInPath = Regex("""\{([^{}]*) => ([^{}]*)}""")

        /** Renames arrive as "a.kt => b.kt" or "src/{old => new}/f.kt"; we keep the new path. */
        internal fun normalizePath(raw: String): String {
            val path = raw.trim()
            if (!path.contains("=>")) return path
            if (renameInPath.containsMatchIn(path)) {
                return renameInPath.replace(path) { it.groupValues[2] }
                    .replace("//", "/")
                    .removePrefix("/")
            }
            return path.substringAfter("=>").trim()
        }
    }
}
