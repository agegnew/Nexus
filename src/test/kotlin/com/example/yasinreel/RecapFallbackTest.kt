package com.example.yasinreel

import com.example.yasinreel.llm.FallbackDirector
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.EvidenceStats
import com.example.yasinreel.model.LanguageStat
import com.example.yasinreel.model.Palette
import com.example.yasinreel.model.RecapFacts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline director is not a curiosity — it is what actually ships.
 *
 * It runs whenever the model is unreachable, out of credits, or its cut fails the scene
 * minimum. The first recap ever generated hit that third case: the model wrote a short film,
 * the repair pass cut it to three scenes, the minimum rejected it, and this director built a
 * product tour instead. The reel was then about the whole product while claiming to be about
 * one week. So a recap has to survive the fallback path too.
 */
class RecapFallbackTest {

    private fun evidence(recap: RecapFacts?) = Evidence(
        projectName = "Nexus",
        projectPath = "/tmp/nexus",
        readme = "Nexus turns a codebase into something you can look at.",
        stats = EvidenceStats(20, 28_573, 3, 4, 6, 4),
        languages = listOf(LanguageStat("Kotlin", 14, 20_000), LanguageStat("JavaScript", 6, 8_573)),
        dependencies = emptyList(),
        topLevelDirs = emptyList(),
        entryPoints = emptyList(),
        chains = emptyList(),
        palette = Palette(emptyList(), null),
        notableFiles = emptyList(),
        recap = recap
    )

    private val facts = RecapFacts(
        since = "2026-09-14",
        until = "2026-09-20",
        area = "all",
        commits = 7,
        filesTouched = 21,
        linesAdded = 2_935,
        linesDeleted = 118,
        uncommittedFiles = 4,
        authors = listOf("agegnew"),
        subjects = listOf(
            "feat: git activity tracking with an LLM summary",
            "fix: filter commits on the author date",
            "feat: uncommitted work in the activity tab"
        )
    )

    private fun narration(storyboard: com.example.yasinreel.model.Storyboard) =
        storyboard.scenes.mapNotNull { it.narration }.joinToString(" ")

    private fun slotText(storyboard: com.example.yasinreel.model.Storyboard) =
        storyboard.scenes.joinToString(" ") { it.slots.toString() }

    @Test
    fun `a recap opens on the period, not on what the product is`() {
        val cut = FallbackDirector.direct(evidence(facts), Audience.TECHNICAL, 60_000)
        val spoken = narration(cut)

        assertTrue("The range should be named out loud: $spoken", spoken.contains("2026-09-14"))
        assertTrue(spoken.contains("2026-09-20"))
        assertFalse(
            "A recap must not introduce the product as if it were a launch film: $spoken",
            spoken.contains("described by its own source rather than by its README")
        )
    }

    @Test
    fun `the counted facts of the period reach the screen`() {
        val slots = slotText(FallbackDirector.direct(evidence(facts), Audience.TECHNICAL, 60_000))

        assertTrue("Commit count missing: $slots", slots.contains("Commits"))
        assertTrue("File count missing", slots.contains("Files"))
        assertTrue("Added lines missing", slots.contains("Added"))
    }

    @Test
    fun `uncommitted work is called out rather than narrated as finished`() {
        val spoken = narration(FallbackDirector.direct(evidence(facts), Audience.TECHNICAL, 60_000))

        assertTrue(
            "Work in progress must be flagged when there is any: $spoken",
            spoken.contains("uncommitted") || spoken.contains("in progress")
        )
    }

    @Test
    fun `the developer's own commit subjects are used`() {
        val slots = slotText(FallbackDirector.direct(evidence(facts), Audience.TECHNICAL, 60_000))

        assertTrue("Commit subjects should reach the cards: $slots", slots.contains("git activity tracking"))
    }

    @Test
    fun `the stakeholder recap also talks about the period`() {
        val spoken = narration(FallbackDirector.direct(evidence(facts), Audience.STAKEHOLDER, 60_000))

        assertTrue("The stakeholder recap should name the range: $spoken", spoken.contains("2026-09-14"))
    }

    @Test
    fun `a launch film is untouched by any of this`() {
        val cut = FallbackDirector.direct(evidence(null), Audience.TECHNICAL, 60_000)
        val spoken = narration(cut)

        assertFalse("A launch film must not mention a range: $spoken", spoken.contains("2026-09-14"))
        assertFalse("A launch film must not show commit counts", slotText(cut).contains("Commits"))
        assertTrue(
            "The original opening should survive for launch films",
            spoken.contains("described by its own source rather than by its README")
        )
    }

    @Test
    fun `a recap still fills the film, because a short one is thrown away`() {
        // The pipeline rejects anything under five scenes and replaces it with this director's
        // output, so this director must not itself come in under that bar.
        val cut = FallbackDirector.direct(evidence(facts), Audience.TECHNICAL, 60_000)

        assertTrue("Only ${cut.scenes.size} scenes; the minimum is 5", cut.scenes.size >= 5)
    }
}
