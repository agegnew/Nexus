package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The whole chain on this repository's real history: git log -> scope filter -> model.
 * Needs a key, so it is opt-in:
 *   $env:OPENAI_API_KEY="sk-..."; ./gradlew test --tests '*ActivityEndToEndTest*' -PskipUiBuild
 */
class ActivityEndToEndTest {

    private val root = File(".").absoluteFile
        .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }

    private val modules = ModuleDetector.detect(root)

    private fun range() = ActivityRequest(since = "2000-01-01", until = LocalDate.now().toString())

    @Test
    fun `the frontend scope sees only frontend files`() {
        val commits = GitActivitySource(root).commits(range())
        assumeTrue("No history", commits.isNotEmpty())

        val frontend = ScopeFilter.apply(commits, modules, "frontend")

        assertTrue(
            "Frontend scope leaked non-frontend files",
            frontend.flatMap { it.files }.all { it.path.startsWith("visualizer-ui/") }
        )
    }

    @Test
    fun `summarises this repository including work in progress`() {
        val key = ActivitySettings.resolveKey(null, System.getenv("OPENAI_API_KEY"), ActivitySettings.keyFile())
        assumeTrue("No API key available", key.isNotBlank())

        val request = range()
        val range = DateRange.of(request.since, request.until)!!
        val git = GitActivitySource(root)
        val commits = ScopeFilter.apply(git.commits(request), modules, request.scope)
        val pending = ScopeFilter.applyPending(git.pendingChanges(range), modules, request.scope)
        assumeTrue("No uncommitted work to describe", pending.isNotEmpty())

        val report = ActivitySummarizer(OpenAiClient(key)).summarize(commits, modules, request, pending)

        assertEquals("ready", report.status)
        assertEquals(pending.size, report.uncommitted.size)
        assertEquals(pending.size, report.stats.pendingFiles)
        // The whole point of sending pending work is that the summary acknowledges it.
        assertTrue(
            "No 'In progress' theme in " + report.themes.map { it.title },
            report.themes.any { it.title.contains("In progress", ignoreCase = true) }
        )

        println("=== ${report.stats.commits} commits + ${report.stats.pendingFiles} uncommitted files")
        println(report.headline)
        report.themes.forEach { println("- ${it.title}: ${it.summary}") }
        println("pending sample: " + report.uncommitted.take(4).joinToString { "${it.status} ${it.path} (${it.date})" })
    }

    @Test
    fun `summarises this repository`() {
        // Resolved exactly as the plugin does, so the key file fallback is covered too.
        val key = ActivitySettings.resolveKey(null, System.getenv("OPENAI_API_KEY"), ActivitySettings.keyFile())
        assumeTrue("No API key available", key.isNotBlank())

        val request = range()
        val commits = ScopeFilter.apply(GitActivitySource(root).commits(request), modules, request.scope)
        assumeTrue("No history", commits.isNotEmpty())

        val report = ActivitySummarizer(OpenAiClient(key)).summarize(commits, modules, request)

        assertEquals("ready", report.status)
        assertTrue(report.headline.isNotBlank())
        assertTrue(report.themes.isNotEmpty())
        assertEquals(commits.size, report.stats.commits)

        println("=== ${report.stats.commits} commits, ${report.stats.files} files, +${report.stats.added} -${report.stats.deleted}")
        println(report.headline)
        report.themes.forEach { println("- ${it.title} ${it.scopes}: ${it.summary}") }
    }
}
