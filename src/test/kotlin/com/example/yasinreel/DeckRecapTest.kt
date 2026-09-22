package com.example.yasinreel

import com.example.yasinreel.deck.Caps
import com.example.yasinreel.deck.DeckComposer
import com.example.yasinreel.deck.DeckGeometry
import com.example.yasinreel.deck.DeckIcons
import com.example.yasinreel.deck.DeckTheme
import com.example.yasinreel.deck.DeckValidator
import com.example.yasinreel.deck.PptxWriter
import com.example.yasinreel.deck.SlideLayout
import com.example.yasinreel.model.Architecture
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Capability
import com.example.yasinreel.model.Component
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.EvidenceStats
import com.example.yasinreel.model.FlowStep
import com.example.yasinreel.model.KeyFlow
import com.example.yasinreel.model.Layer
import com.example.yasinreel.model.Palette
import com.example.yasinreel.model.ProductModel
import com.example.yasinreel.model.RecapFacts
import com.example.yasinreel.model.ReelScope
import com.example.yasinreel.model.ScaleFact
import com.example.yasinreel.model.TechItem
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deck built from a date range, which is a different deck from the product one.
 *
 * The failure this guards against is the quiet one: a range is picked, the evidence is
 * narrowed correctly, and the deck that comes out is still a product tour with the word
 * "recap" nowhere on it. That deck is worse than no feature at all, because it is shown
 * to a room under a title that is not true of it. So these tests check what the slides
 * actually say, not only that a file was produced.
 */
class DeckRecapTest {

    private val out = File("build/decks").apply { mkdirs() }

    // ------------------------------------------------------------------ fixtures

    private fun recap() = RecapFacts(
        since = "2026-09-01",
        until = "2026-09-22",
        area = "all",
        commits = 17,
        filesTouched = 43,
        linesAdded = 5120,
        linesDeleted = 860,
        uncommittedFiles = 3,
        authors = listOf("Yasin Usman"),
        subjects = listOf(
            "Light theme, an icon set, and motion that reads as film",
            "Generate a presentation for each audience from one understanding",
            "Date scoped updates, and one key for both halves",
            "A recap that falls back was still a launch film",
            "Restore the original architecture view, keep the icons"
        )
    )

    private fun evidence(withRecap: Boolean = true) = Evidence(
        projectName = "Nexus",
        projectPath = "/tmp/nexus",
        readme = "Nexus reads a project and draws how it fits together.",
        stats = EvidenceStats(totalFiles = 43, totalLines = 5120, testFiles = 6, httpCalls = 0, httpRoutes = 0, matchedCalls = 0),
        languages = emptyList(),
        dependencies = emptyList(),
        topLevelDirs = emptyList(),
        entryPoints = emptyList(),
        chains = emptyList(),
        palette = Palette(emptyList(), null),
        notableFiles = emptyList(),
        recap = if (withRecap) recap() else null
    )

    private fun model() = ProductModel(
        productName = "Nexus",
        tagline = "Open a project you have never seen, and watch it explain itself.",
        problemStatement = "A new engineer faces forty thousand lines with no map.",
        targetUser = "engineers joining a team",
        confidence = "high",
        capabilities = listOf(
            Capability("cap-map", "See the whole shape at once", "Everything is on one picture instead of forty files", "The analyser walks the file index and emits a graph", listOf("file:Analyzer.kt"), "high"),
            Capability("cap-film", "Watch it rather than read it", "A short film explains the work to anyone in the room", "One seekable timeline, played in the IDE", listOf("file:timeline.js"), "high"),
            Capability("cap-deck", "Hand it to the room", "A presentation you can give without writing slides", "Office XML written straight to a zip", listOf("file:PptxWriter.kt"), "high"),
            Capability("cap-range", "Show one period", "Pick two dates and get only the work between them", "The history reader the activity view already uses", listOf("file:ChangedFiles.kt"), "high")
        ),
        architecture = Architecture(
            layers = listOf(
                Layer("Analysis", listOf(Component("EvidenceHarvester", "Kotlin"))),
                Layer("Understanding", listOf(Component("NarrativeEngine", "OpenAI"))),
                Layer("Presentation", listOf(Component("PptxWriter", "OOXML")))
            ),
            dataStores = listOf("on disk cache")
        ),
        techStack = listOf(
            TechItem("Kotlin", "Language", "the platform speaks it"),
            TechItem("GSAP", "Animation", "one seekable timeline")
        ),
        keyFlows = listOf(
            KeyFlow("From two dates to a deck", listOf(
                FlowStep("A developer", "picks a period", null),
                FlowStep("Nexus", "reads the history in it", null),
                FlowStep("The composer", "lays the work out as slides", null)
            ))
        ),
        scaleFacts = listOf(ScaleFact("Files read", "43", null)),
        gaps = listOf("Work that was never committed cannot be counted")
    )

    private fun built(audience: String, withRecap: Boolean = true): DeckValidator.Result {
        val ev = evidence(withRecap)
        return DeckValidator.validate(DeckComposer.fromModel(model(), ev, audience), ev)
    }

    // --------------------------------------------------------------------- tests

    @Test
    fun `an update opens on the period rather than on the problem`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val deck = built(audience).deck
            val layouts = deck.slides.map { it.layout }

            assertFalse(
                "$audience update should not re-explain the product's problem",
                SlideLayout.PROBLEM in layouts
            )
            assertEquals("the deck should still open on a title", SlideLayout.TITLE, layouts.first())

            // The counted period is the first thing after the title and the agenda.
            val statsAt = layouts.indexOf(SlideLayout.STATS)
            assertTrue("$audience update has no period slide", statsAt > 0)
            assertTrue("$audience update buries the period at slide ${statsAt + 1}", statsAt <= 2)

            val period = deck.slides[statsAt]
            assertEquals("2026-09-01 to 2026-09-22", period.slots.get("heading").asString)

            val stats = period.slots.getAsJsonArray("stats").map {
                it.asJsonObject.get("label").asString to it.asJsonObject.get("value").asString
            }.toMap()
            assertTrue("no more tiles than a stats slide can hold", stats.size <= Caps.MAX_STATS)
            // The counts are the same counts whichever room they are shown in; only the
            // labels change, because "commits" is a word about the tool and not the work.
            assertTrue("$audience update lost the commit count", stats.containsValue("17"))
            assertTrue("$audience update lost the lines added", stats.containsValue("+5,120"))
            assertTrue("$audience update lost the lines removed", stats.containsValue("-860"))

            // Work in progress is a caveat, so it is said rather than shown as a win.
            assertTrue(
                "$audience update never warns that some of the work is unfinished",
                deck.slides.any { slide ->
                    slide.notes?.let { it.contains("not committed yet") || it.contains("not finished") } == true
                }
            )
        }
    }

    @Test
    fun `the title and the closing both name the period`() {
        val technical = built(Audience.TECHNICAL).deck
        val stakeholder = built(Audience.STAKEHOLDER).deck

        assertEquals(
            "What changed between 1 and 22 September 2026",
            technical.slides.first().slots.get("tagline").asString
        )
        assertEquals(
            "What we shipped between 1 and 22 September 2026",
            stakeholder.slides.first().slots.get("tagline").asString
        )
        // The stamp carries the exact bounds, so a reader can check them against git.
        assertEquals("Engineering update · 2026-09-01 to 2026-09-22", technical.slides.first().slots.get("stamp").asString)
        assertEquals("Progress update · 2026-09-01 to 2026-09-22", stakeholder.slides.first().slots.get("stamp").asString)

        listOf(technical, stakeholder).forEach { deck ->
            val closing = deck.slides.last { it.layout == SlideLayout.CLOSING }
            assertTrue(
                "the closing slide should name the period, not the product",
                closing.slots.get("headline").asString.contains("September 2026")
            )
        }
    }

    @Test
    fun `the commit subjects are shown to engineers and not to the room`() {
        val technical = built(Audience.TECHNICAL).deck
        val work = technical.slides.firstOrNull { it.slots.get("heading")?.asString == "What was done" }
        assertNotNull("a technical update should show what the commits said", work)

        val titles = work!!.slots.getAsJsonArray("cards").map { it.asJsonObject.get("title").asString }
        assertEquals("Light theme, an icon set, and motion that reads as film", titles.first())
        assertTrue("the grid should not exceed its cap", titles.size <= Caps.MAX_CARDS)

        val stakeholder = built(Audience.STAKEHOLDER).deck
        assertTrue(
            "commit subjects are written for the person running git log, not for the room",
            stakeholder.slides.none { it.slots.get("heading")?.asString == "What was done" }
        )
    }

    @Test
    fun `no slide is dropped for repeating a layout`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val result = built(audience)
            val repeated = result.issues.filter { it.contains("repeated") }
            assertTrue(
                "$audience update lost a slide to the repeat cap: $repeated",
                repeated.isEmpty()
            )
            assertTrue(
                "$audience update is too short at ${result.deck.slides.size} slides",
                result.deck.slides.size >= Caps.MIN_SLIDES
            )
        }
    }

    @Test
    fun `a stakeholder update still speaks no engineering`() {
        val result = built(Audience.STAKEHOLDER)
        // Nothing was removed on the way through, so the scrub had nothing to take: a deck
        // that passes this by having lost its numbers has not passed it.
        assertTrue("the scrub had to edit the update: ${result.issues}", result.issues.isEmpty())
        result.deck.slides.forEach { slide ->
            StoryboardValidator.bannedMatches(slide.slots.toString(), emptySet(), "Nexus").let { hits ->
                assertTrue("a ${slide.layout} slide says ${hits.joinToString()}", hits.isEmpty())
            }
            slide.notes?.let { notes ->
                val hits = StoryboardValidator.bannedMatches(notes, emptySet(), "Nexus")
                assertTrue("the notes on a ${slide.layout} slide say ${hits.joinToString()}", hits.isEmpty())
            }
        }
    }

    @Test
    fun `an update draws inside the slide and is written as a real package`() {
        val icons = DeckIcons.load()
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val art = DeckGeometry.render(built(audience).deck)
            art.forEachIndexed { index, slide ->
                slide.shapes.forEach { shape ->
                    val where = "$audience update slide ${index + 1}, ${shape::class.simpleName}"
                    assertTrue("$where starts outside the slide", shape.x >= 0 && shape.y >= 0)
                    assertTrue("$where runs off the right edge", shape.x + shape.w <= DeckTheme.W)
                    assertTrue("$where runs off the bottom edge", shape.y + shape.h <= DeckTheme.H)
                }
            }
            // Left on disk on purpose: the only real check of an .pptx is opening it.
            val file = PptxWriter.write(
                art, icons, "Nexus update, $audience", "Nexus",
                File(out, "nexus-$audience-recap-2026-09-01-to-2026-09-22.pptx")
            )
            assertTrue("the update should be on disk", file.isFile)
        }
    }

    @Test
    fun `the product deck is untouched by any of this`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val deck = built(audience, withRecap = false).deck
            assertTrue(
                "a product deck still opens on the problem",
                deck.slides.any { it.layout == SlideLayout.PROBLEM }
            )
            assertEquals(
                "a product deck is still titled after the product",
                model().tagline,
                deck.slides.first().slots.get("tagline").asString
            )
        }
    }

    /**
     * One single slide package per slide, because LibreOffice exports only the first slide
     * of a file. This is how the three defects in the product deck were found, and it is
     * the only check that sees what a reader sees.
     */
    @Test
    fun `each slide of an update is written on its own so it can be looked at`() {
        val icons = DeckIcons.load()
        val dir = File(out, "recap-slides").apply { mkdirs() }
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val art = DeckGeometry.render(built(audience).deck)
            art.forEachIndexed { index, slide ->
                val name = "%s-%02d-%s.pptx".format(audience, index + 1, slide.title.take(24)
                    .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "slide" })
                PptxWriter.write(listOf(slide), icons, slide.title, "Nexus", File(dir, name))
            }
        }
        assertTrue("one file per slide should have landed", (dir.listFiles()?.size ?: 0) >= Caps.MIN_SLIDES)
    }

    // ------------------------------------------------------ the control itself

    @Test
    fun `both tabs read one panel the same way`() {
        val message = JsonParser.parseString(
            """{"scope":"recap","since":"2026-09-01","until":"2026-09-22",
               "area":"module:visualizer-ui","mine":false,"uncommitted":false}"""
        ).asJsonObject

        val scope = ReelScope.from(message)
        assertTrue(scope.isRecap)
        assertEquals("2026-09-01", scope.since)
        assertEquals("2026-09-22", scope.until)
        assertEquals("module:visualizer-ui", scope.area)
        assertFalse(scope.mine)
        assertFalse(scope.includeUncommitted)
        assertEquals("recap-2026-09-01-to-2026-09-22", scope.fileTag())

        // Anything a page can send that is not a whole range is the whole project, which
        // is the one answer that cannot be wrong.
        listOf(
            """{"scope":"launch"}""",
            """{"scope":"recap","since":"2026-09-01"}""",
            """{"scope":"recap","since":"","until":"2026-09-22"}""",
            """{}"""
        ).forEach { raw ->
            val fallback = ReelScope.from(JsonParser.parseString(raw).asJsonObject)
            assertFalse("$raw should not have produced a recap", fallback.isRecap)
            assertEquals("", fallback.fileTag())
        }
    }

    /**
     * The panel's markup lives in two pages and its meaning lives in one script. That only
     * holds while all three agree on the element ids, and nothing but this test would
     * notice the day one of them is renamed: the control would simply stop working, in
     * whichever tab was edited second.
     */
    @Test
    fun `the shared range control and both pages agree on their ids`() {
        val shared = File("src/main/resources/yasin-shared/scope.js").readText()
        val reel = File("src/main/resources/yasin-reel/index.html").readText()
        val deck = File("src/main/resources/yasin-deck/index.html").readText()

        val ids = Regex("el\\('([a-z-]+)'\\)").findAll(shared).map { it.groupValues[1] }.toSet()
        assertTrue("the shared control should read some ids", ids.size >= 6)

        ids.forEach { id ->
            assertTrue("the reel page has no #$id", reel.contains("id=\"$id\""))
            assertTrue("the deck page has no #$id", deck.contains("id=\"$id\""))
        }

        // Both pages have to load it, or one of them silently falls back to launch only.
        listOf(reel to "reel", deck to "deck").forEach { (page, name) ->
            assertTrue("the $name page does not load the shared control", page.contains("/shared/scope.js"))
        }

        /*
         * Every period the pages offer has to be one the script can resolve, or it quietly
         * produces an empty range and the IDE is asked for the whole project instead of
         * the fortnight somebody picked.
         *
         * Two of them resolve to no dates deliberately and are read out of the script
         * rather than listed here, so adding a third cannot pass by being forgotten:
         * `custom` means the calendar decides, and `whole` means there is no period at all.
         */
        val presets = Regex("case '([a-z0-9-]+)':").findAll(shared).map { it.groupValues[1] }.toSet()
        val whole = Regex("var WHOLE = '([a-z]+)'").find(shared)?.groupValues?.get(1)
        assertNotNull("the shared control should name its widest period", whole)
        val known = presets + "custom" + whole!!

        listOf(reel to "reel", deck to "deck").forEach { (page, name) ->
            val block = page.substringAfter("id=\"scope-period\"").substringBefore("</select>")
            val offered = Regex("value=\"([a-z0-9-]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
            offered.forEach { option ->
                assertTrue("the $name page offers '$option', which the shared control cannot resolve", option in known)
            }

            /*
             * The chips and the hidden select are two lists of the same thing, and the
             * chips are the only one a person can see. A chip with no matching option
             * writes a value the select rejects, so the period silently does not change.
             */
            val chips = Regex("data-period=\"([a-z0-9-]+)\"").findAll(page).map { it.groupValues[1] }.toSet()
            assertTrue("the $name page shows no period chips", chips.size >= 4)
            assertEquals("the $name page's chips and its period list disagree", offered, chips)
        }
    }
}
