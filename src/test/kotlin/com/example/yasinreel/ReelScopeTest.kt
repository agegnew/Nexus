package com.example.yasinreel

import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.EvidenceStats
import com.example.yasinreel.model.Palette
import com.example.yasinreel.model.RecapFacts
import com.example.yasinreel.model.ReelScope
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReelScopeTest {

    private val gson = Gson()

    @Test
    fun `a launch reel is the default and covers the whole project`() {
        val scope = ReelScope.launch()

        assertFalse(scope.isRecap)
        assertEquals("the whole project", scope.describe())
        assertEquals(ReelScope.LAUNCH, ReelScope().kind)
    }

    @Test
    fun `a recap names its range`() {
        val scope = ReelScope(ReelScope.RECAP, since = "2026-09-14", until = "2026-09-20")

        assertTrue(scope.isRecap)
        assertEquals("between 2026-09-14 and 2026-09-20", scope.describe())
    }

    /**
     * ProductModelCache writes evidence.json and reads it back on the next run. Making `recap`
     * non-optional would have made every cached file fail to parse, which would surface as reels
     * silently re-harvesting — or worse, as a crash on a user's machine and not on ours.
     */
    @Test
    fun `evidence written before recap existed still parses`() {
        val old = """
            {"projectName":"demo","projectPath":"/tmp/demo","readme":null,
             "stats":{"totalFiles":10,"totalLines":100,"testFiles":1,"httpCalls":2,"httpRoutes":3,"matchedCalls":2},
             "languages":[],"dependencies":[],"topLevelDirs":[],"entryPoints":[],"chains":[],
             "palette":{"colors":[],"source":null},"notableFiles":[]}
        """.trimIndent()

        val evidence = gson.fromJson(old, Evidence::class.java)

        assertNotNull(evidence)
        assertNull("Evidence with no recap field must read back as a launch harvest", evidence.recap)
        assertEquals("demo", evidence.projectName)
    }

    @Test
    fun `recap facts survive a round trip`() {
        val evidence = Evidence(
            projectName = "demo",
            projectPath = "/tmp/demo",
            readme = null,
            stats = EvidenceStats(10, 100, 1, 2, 3, 2),
            languages = emptyList(),
            dependencies = emptyList(),
            topLevelDirs = emptyList(),
            entryPoints = emptyList(),
            chains = emptyList(),
            palette = Palette(emptyList(), null),
            notableFiles = emptyList(),
            recap = RecapFacts(
                since = "2026-09-14",
                until = "2026-09-20",
                area = "frontend",
                commits = 7,
                filesTouched = 23,
                linesAdded = 900,
                linesDeleted = 120,
                uncommittedFiles = 4,
                authors = listOf("agegnew"),
                subjects = listOf("feat: activity tab", "fix: date filtering")
            )
        )

        val restored = gson.fromJson(gson.toJson(evidence), Evidence::class.java)

        assertEquals(evidence.recap, restored.recap)
        assertEquals(7, restored.recap?.commits)
        assertEquals(listOf("feat: activity tab", "fix: date filtering"), restored.recap?.subjects)
    }

    /**
     * The whole point of a recap is that the reel stops describing the product and starts
     * describing the work. That switch is one `if` in the engine; if it is ever dropped, a recap
     * still renders and still looks plausible, which is exactly why it needs pinning down.
     */
    @Test
    fun `the engine frames a recap differently and only when there is one`() {
        val repoRoot = File(".").absoluteFile
            .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }
        val engine = File(repoRoot, "src/main/kotlin/com/example/yasinreel/llm/OpenAiNarrativeEngine.kt").readText()
        val prompts = File(repoRoot, "src/main/kotlin/com/example/yasinreel/llm/Prompts.kt").readText()

        assertTrue(
            "The recap overlay must be applied only when evidence carries recap facts",
            engine.contains("if (evidence.recap != null) system + \"\\n\" + Prompts.RECAP_OVERLAY else system")
        )
        assertTrue("RECAP_OVERLAY must exist", prompts.contains("val RECAP_OVERLAY"))
        assertTrue(
            "The overlay must tell the model the evidence is not the whole product",
            prompts.contains("NOT the whole product")
        )
        assertTrue(
            "Uncommitted work must not be narrated as finished",
            prompts.contains("Uncommitted work is work in progress") ||
                prompts.contains("uncommitted") && prompts.contains("work in progress")
        )
    }

    @Test
    fun `harvesting the whole project stays the default`() {
        val repoRoot = File(".").absoluteFile
            .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }
        val harvester = File(repoRoot, "src/main/kotlin/com/example/yasinreel/harvest/EvidenceHarvester.kt").readText()

        // Every existing caller passes one argument; a required filter would break them all.
        assertTrue(
            "harvest() must keep a whole-project default",
            harvester.contains("fun harvest(project: Project, only: Set<String>? = null)")
        )
        assertTrue(
            "The filter must be applied in the scan loop",
            harvester.contains("(only == null || relative in only)")
        )
    }
}
