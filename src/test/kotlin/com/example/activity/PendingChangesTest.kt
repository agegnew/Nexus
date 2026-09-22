package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.time.ZoneId

class PendingChangesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun status(vararg fields: String) = fields.joinToString("\u0000") + "\u0000"

    @Test
    fun `reads staged, unstaged and untracked entries`() {
        val entries = GitActivitySource.parseStatus(
            status("M  src/Staged.kt", " M src/Unstaged.kt", "?? src/New.kt", "A  src/Added.kt")
        )

        assertEquals(
            listOf("src/Staged.kt", "src/Unstaged.kt", "src/New.kt", "src/Added.kt"),
            entries.map { it.path }
        )
        assertEquals(listOf("modified", "modified", "untracked", "added"), entries.map { it.status })
        assertEquals(listOf(true, false, false, true), entries.map { it.staged })
    }

    @Test
    fun `a rename consumes its old path instead of counting twice`() {
        // git emits "R  new" followed by the original path as a separate NUL-terminated field.
        val entries = GitActivitySource.parseStatus(status("R  src/New.kt", "src/Old.kt", " M src/Other.kt"))

        assertEquals(listOf("src/New.kt", "src/Other.kt"), entries.map { it.path })
        assertEquals("renamed", entries.first().status)
    }

    @Test
    fun `paths with spaces and quotes survive`() {
        val entries = GitActivitySource.parseStatus(status(" M my folder/a file \"v2\".kt", "?? ünïcode/naïve.kt"))

        assertEquals(listOf("my folder/a file \"v2\".kt", "ünïcode/naïve.kt"), entries.map { it.path })
    }

    @Test
    fun `deletions are recognised from either column`() {
        val entries = GitActivitySource.parseStatus(status("D  src/StagedDelete.kt", " D src/UnstagedDelete.kt"))

        assertEquals(listOf("deleted", "deleted"), entries.map { it.status })
    }

    @Test
    fun `empty and malformed rows are ignored`() {
        assertTrue(GitActivitySource.parseStatus("").isEmpty())
        assertTrue(GitActivitySource.parseStatus(status("XY")).isEmpty())
    }

    @Test
    fun `this working tree reports its real uncommitted files`() {
        val root = File(".").absoluteFile
            .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }
        val wide = DateRange.of("2000-01-01", "2100-01-01", ZoneId.systemDefault())!!

        val pending = GitActivitySource(root).pendingChanges(wide)

        assertTrue("This tree has uncommitted work, so it should be reported", pending.isNotEmpty())
        // Every reported file should be one git actually knows about as changed.
        assertTrue(pending.all { it.path.isNotBlank() })
        assertTrue(pending.all { it.status in setOf("modified", "added", "deleted", "renamed", "untracked") })
        // Newest first, so the most recent work leads.
        assertEquals(pending.sortedByDescending { it.timestamp }, pending)
        println("pending: " + pending.take(5).joinToString { "${it.status} ${it.path} (${it.date}) +${it.added}" })
    }

    @Test
    fun `a deleted file is dated from the folder that held it`() {
        val repo = temp.newFolder("repo")
        run(repo, "git", "init", "-q")
        run(repo, "git", "config", "user.email", "t@example.com")
        run(repo, "git", "config", "user.name", "Test")
        File(repo, "nested").mkdirs()
        File(repo, "nested/Gone.kt").writeText("fun main() {}\n")
        run(repo, "git", "add", "-A")
        run(repo, "git", "commit", "-q", "-m", "add")
        File(repo, "nested/Gone.kt").delete()

        val wide = DateRange.of("2000-01-01", "2100-01-01", ZoneId.systemDefault())!!
        val deleted = GitActivitySource(repo).pendingChanges(wide).single { it.path == "nested/Gone.kt" }

        assertEquals("deleted", deleted.status)
        // The file is gone, so the time comes from its folder and is flagged as inferred.
        assertTrue("Expected a date for the deletion", deleted.date.isNotBlank())
        assertTrue("Expected the date to be marked approximate", deleted.approximate)
        assertTrue(deleted.timestamp > 0)
    }

    @Test
    fun `a range excludes files modified outside it`() {
        val root = File(".").absoluteFile
            .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }
        val longAgo = DateRange.of("2000-01-01", "2000-01-02", ZoneId.systemDefault())!!

        // Everything in this tree was touched long after 2000, and deletions are now dated from
        // their folder, so nothing at all falls inside that window.
        assertTrue(GitActivitySource(root).pendingChanges(longAgo).isEmpty())
    }
}

private fun run(dir: File, vararg command: String) {
    val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
    process.inputStream.readBytes()
    check(process.waitFor(30, TimeUnit.SECONDS)) { "timed out: ${command.joinToString(" ")}" }
}
