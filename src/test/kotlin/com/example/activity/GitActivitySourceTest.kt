package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.LocalDate

private const val RS = "\u001e"
private const val US = "\u001f"

class GitActivitySourceTest {

    private val dubai = ZoneId.of("Asia/Dubai")

    /** 2026-09-21 21:22:06 +04:00 — the author time of this repository's HEAD commit. */
    private val headEpoch = 1790011326L

    // The record carries %at (absolute epoch seconds), not a pre-rendered date.
    private fun record(
        hash: String,
        author: String = "agegnew",
        email: String = "agegnew.mersha@gmail.com",
        epoch: Long = 1790011326L,
        parents: String = "abc0000",
        subject: String = "feat: something",
        body: String = "",
        numstat: String = ""
    ) = "${RS}C$US$hash$US$author$US$email$US$epoch$US$parents$US$subject$US$body$US\n$numstat"

    @Test
    fun `parses a commit with its file changes`() {
        val commits = GitActivitySource.parse(
            record(
                hash = "c5ca8ef3ad2991c45d50971221a925236dd51652",
                subject = "docs: add developer and collaborator guide",
                numstat = "430\t0\tGUIDE.md\n12\t3\tsrc/main/kotlin/com/example/VisualizerPanel.kt\n"
            ),
            dubai
        )

        assertEquals(1, commits.size)
        val commit = commits.first()
        assertEquals("c5ca8ef", commit.shortHash)
        assertEquals("agegnew", commit.author)
        assertEquals("2026-09-21", commit.date)
        assertEquals("docs: add developer and collaborator guide", commit.subject)
        assertFalse(commit.merge)
        assertEquals(2, commit.files.size)
        assertEquals("GUIDE.md", commit.files[0].path)
        assertEquals(430, commit.files[0].added)
        assertEquals(3, commit.files[1].deleted)
        assertEquals(442, listOf(commit).added())
    }

    @Test
    fun `keeps multi-line bodies and separators out of the fields`() {
        val commits = GitActivitySource.parse(
            record(
                hash = "1111111aaaa",
                subject = "fix: tabs\tand | pipes survive",
                body = "Why: the old parser split on pipes.\n\nRefs #12",
                numstat = "1\t1\tREADME.md\n"
            ),
            dubai
        )

        val commit = commits.single()
        assertEquals("fix: tabs\tand | pipes survive", commit.subject)
        assertTrue(commit.body.contains("Refs #12"))
        assertEquals(1, commit.files.size)
    }

    @Test
    fun `flags binary files and merge commits`() {
        val commit = GitActivitySource.parse(
            record(hash = "2222222bbbb", parents = "aaa111 bbb222", numstat = "-\t-\tassets/hero.png\n"),
            dubai
        ).single()

        assertTrue(commit.merge)
        assertTrue(commit.files.single().binary)
        assertEquals(0, commit.files.single().added)
    }

    @Test
    fun `each committed file carries its own status and the commit time`() {
        val raw = ":000000 100644 0000000 6e89cd6 A\tGUIDE.md\n" +
            ":100644 100644 aaaa111 bbbb222 M\tREADME.md\n" +
            ":100644 000000 cccc333 0000000 D\told/Gone.kt\n"
        val numstat = "430\t0\tGUIDE.md\n3\t1\tREADME.md\n0\t41\told/Gone.kt\n"

        val commit = GitActivitySource.parse(record(hash = "4444444ddd", numstat = raw + numstat), dubai).single()

        assertEquals(
            mapOf("GUIDE.md" to "added", "README.md" to "modified", "old/Gone.kt" to "deleted"),
            commit.files.associate { it.path to it.status }
        )
        assertEquals(listOf(430, 3, 0), commit.files.map { it.added })
        // Every file inherits the moment the commit landed, in the viewer's zone.
        assertEquals("2026-09-21", commit.date)
        assertEquals("21:22", commit.time)
    }

    @Test
    fun `a renamed file reports the new path with a renamed status`() {
        val raw = ":100644 100644 aaaa111 bbbb222 R100\told/Name.kt\tnew/Name.kt\n"
        val numstat = "0\t0\tnew/Name.kt\n"

        val file = GitActivitySource.parse(record(hash = "5555555eee", numstat = raw + numstat), dubai)
            .single().files.single()

        assertEquals("new/Name.kt", file.path)
        assertEquals("renamed", file.status)
    }

    @Test
    fun `a file with no raw row falls back to modified`() {
        val commit = GitActivitySource.parse(record(hash = "6666666fff", numstat = "1\t1\tREADME.md\n"), dubai).single()

        assertEquals("modified", commit.files.single().status)
    }

    @Test
    fun `the commit time follows the viewer's zone`() {
        val record = record(hash = "7777777ggg", epoch = headEpoch)

        assertEquals("21:22", GitActivitySource.parse(record, dubai).single().time)
        assertEquals("02:22", GitActivitySource.parse(record, ZoneId.of("Asia/Tokyo")).single().time)
    }

    @Test
    fun `reports the new path for renames`() {
        assertEquals("b.kt", GitActivitySource.normalizePath("a.kt => b.kt"))
        assertEquals(
            "src/main/kotlin/com/example/MyToolWindowFactory.kt",
            GitActivitySource.normalizePath("src/main/kotlin/{ => com/example}/MyToolWindowFactory.kt")
        )
        assertEquals("GUIDE.md", GitActivitySource.normalizePath("GUIDE.md"))
    }

    @Test
    fun `ignores trailing blank records`() {
        assertTrue(GitActivitySource.parse("", dubai).isEmpty())
        assertTrue(GitActivitySource.parse("\n$RS\n").isEmpty())
    }

    @Test
    fun `the timestamp is absolute and the date follows the given zone`() {
        val record = record(hash = "3333333ccc", epoch = headEpoch)

        // 2026-09-21 21:22 +04:00 is still the 21st in Dubai, but already the 22nd in Tokyo (+09:00).
        assertEquals("2026-09-21", GitActivitySource.parse(record, dubai).single().date)
        assertEquals("2026-09-22", GitActivitySource.parse(record, ZoneId.of("Asia/Tokyo")).single().date)
        assertEquals(headEpoch, GitActivitySource.parse(record, dubai).single().timestamp)
    }

    @Test
    fun `a record without a usable timestamp is dropped`() {
        assertTrue(GitActivitySource.parse("${RS}C${US}abcdef1234567${US}a${US}b${US}notanumber${US}p${US}s${US}${US}\n", dubai).isEmpty())
    }

    @Test
    fun `reads this repository end to end`() {
        val root = File(".").absoluteFile.let { generateSequence(it) { p -> p.parentFile }.first { File(it, ".git").exists() } }
        assumeTrue("Not a git checkout", GitActivitySource.isRepository(root))

        val commits = GitActivitySource(root).commits(
            ActivityRequest(since = "2000-01-01", until = LocalDate.now().toString())
        )

        assertTrue("Expected at least one commit", commits.isNotEmpty())
        assertTrue(commits.all { it.shortHash.length == 7 })
        assertTrue(commits.all { it.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")) })
        assertTrue("Expected file changes", commits.any { it.files.isNotEmpty() })
    }
}
