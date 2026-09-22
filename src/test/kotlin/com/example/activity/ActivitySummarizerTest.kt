package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ActivitySummarizerTest {

    private val modules = listOf(
        ActivityModule("ui", "visualizer-ui", "visualizer-ui", "frontend"),
        ActivityModule("plugin", "plugin", "src/main/kotlin", "backend")
    )

    private val request = ActivityRequest(since = "2026-09-14", until = "2026-09-21")

    private fun commit(hash: String, subject: String, vararg files: String) = Commit(
        hash = hash.padEnd(40, '0'), shortHash = hash.take(7), author = "agegnew",
        email = "agegnew.mersha@gmail.com", date = "2026-09-18", time = "14:05", timestamp = 1790000000L, subject = subject, body = "",
        merge = false, files = files.map { FileChange(it, 10, 2) }
    )

    private val commits = listOf(
        commit("aaaaaaa", "feat: add JWT refresh", "src/main/kotlin/com/example/Auth.kt"),
        commit("bbbbbbb", "fix: login redirect loop", "visualizer-ui/src/App.jsx"),
        commit("ccccccc", "refactor: extract user service", "src/main/kotlin/com/example/Users.kt")
    )

    /** A stand-in for the API so prompt building and parsing are tested without a network call. */
    private class FakeClient(private val reply: String) : LlmClient {
        var lastPrompt: String? = null
        override fun completeJson(system: String, user: String): String {
            lastPrompt = user
            return reply
        }
    }

    @Test
    fun `an empty range short-circuits without calling the model`() {
        val client = FakeClient("{}")
        val report = ActivitySummarizer(client).summarize(emptyList(), modules, request)

        assertEquals("empty", report.status)
        assertEquals(null, client.lastPrompt)
    }

    @Test
    fun `builds a report from the model's json`() {
        val client = FakeClient(
            """
            {"headline": "You built authentication.",
             "themes": [{"title": "Auth", "summary": "Added JWT refresh.", "scopes": ["plugin"], "commits": ["aaaaaaa", "ccccccc"]},
                        {"title": "UI fixes", "summary": "Fixed the redirect loop.", "scopes": ["visualizer-ui"], "commits": ["bbbbbbb"]}]}
            """
        )

        val report = ActivitySummarizer(client).summarize(commits, modules, request)

        assertEquals("ready", report.status)
        assertEquals("You built authentication.", report.headline)
        assertEquals(listOf("Auth", "UI fixes"), report.themes.map { it.title })
        assertEquals(listOf("aaaaaaa", "ccccccc"), report.themes.first().commits)
        assertEquals(3, report.stats.commits)
        assertEquals(3, report.stats.files)
        assertEquals(30, report.stats.added)
        assertEquals(6, report.stats.deleted)
        assertEquals(listOf("agegnew"), report.stats.authors)
        assertEquals(commits, report.commits)
    }

    @Test
    fun `the prompt carries subjects, modules and file paths but no source`() {
        val client = FakeClient("""{"headline":"h","themes":[]}""")
        ActivitySummarizer(client).summarize(commits, modules, request)

        val prompt = client.lastPrompt!!
        assertTrue(prompt.contains("feat: add JWT refresh"))
        assertTrue(prompt.contains("src/main/kotlin/com/example/Auth.kt"))
        // Every commit line carries its exact date and time, and each file its status.
        assertTrue(prompt.contains("2026-09-18 14:05"))
        assertTrue(prompt.contains("modified src/main/kotlin/com/example/Auth.kt"))
        assertTrue(prompt.contains("modules: plugin"))
        assertTrue(prompt.contains("2026-09-14 to 2026-09-21"))
    }

    @Test
    fun `tolerates malformed themes`() {
        val client = FakeClient("""{"headline":"h","themes":[{"summary":"no title"},{"title":"Kept"}]}""")
        val report = ActivitySummarizer(client).summarize(commits, modules, request)

        assertEquals(listOf("Kept"), report.themes.map { it.title })
        assertEquals(emptyList<String>(), report.themes.single().commits)
    }

    @Test(expected = OpenAiClient.OpenAiException::class)
    fun `rejects a non-json reply`() {
        ActivitySummarizer(FakeClient("Sorry, I cannot do that.")).summarize(commits, modules, request)
    }

    /**
     * The real thing. Opt in with:
     *   $env:OPENAI_API_KEY="sk-..."; ./gradlew test --tests '*ActivitySummarizerTest*' -PskipUiBuild
     */
    @Test
    fun `summarises against the live api`() {
        val key = System.getenv("OPENAI_API_KEY")
        assumeTrue("OPENAI_API_KEY not set", !key.isNullOrBlank())

        val report = ActivitySummarizer(OpenAiClient(key!!)).summarize(commits, modules, request)

        assertEquals("ready", report.status)
        assertTrue("Expected a headline", report.headline.isNotBlank())
        assertTrue("Expected themes", report.themes.isNotEmpty())
        assertTrue("Themes should cite commits", report.themes.any { it.commits.isNotEmpty() })
        println("headline: ${report.headline}")
        report.themes.forEach { println("- ${it.title}: ${it.summary} ${it.commits}") }
    }
}
