package com.example.yasinreel

import com.example.yasinreel.deck.Caps
import com.example.yasinreel.deck.DeckComposer
import com.example.yasinreel.deck.DeckGeometry
import com.example.yasinreel.deck.DeckIcons
import com.example.yasinreel.deck.DeckPreviewJson
import com.example.yasinreel.deck.DeckTheme
import com.example.yasinreel.deck.DeckValidator
import com.example.yasinreel.deck.Label
import com.example.yasinreel.deck.Pic
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
import com.example.yasinreel.model.ProductModel
import com.example.yasinreel.model.ScaleFact
import com.example.yasinreel.model.TechItem
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The deck's own guards, plus the thing a unit test cannot do on its own: write both
 * real `.pptx` files to disk so a human, or LibreOffice, can open them and look.
 *
 * Writing the package by hand rather than taking Apache POI was a deliberate trade, and
 * the price of it is that the format has to be checked rather than assumed. These tests
 * are half of that check. The other half is opening the files that land in `build/decks`.
 */
class DeckTest {

    private val out = File("build/decks").apply { mkdirs() }

    // ------------------------------------------------------------------ fixtures

    private fun evidence(): Evidence = Evidence(
        projectName = "Nexus",
        projectPath = "/tmp/nexus",
        readme = "Nexus reads a project and draws how it fits together.\n\nIt runs inside the IDE.",
        stats = EvidenceStats(totalFiles = 38, totalLines = 7420, testFiles = 2, httpCalls = 0, httpRoutes = 0, matchedCalls = 0),
        languages = emptyList(),
        dependencies = emptyList(),
        topLevelDirs = emptyList(),
        entryPoints = emptyList(),
        chains = emptyList(),
        palette = com.example.yasinreel.model.Palette(emptyList(), null),
        notableFiles = emptyList()
    )

    private fun model(): ProductModel = ProductModel(
        productName = "Nexus",
        tagline = "Open a project you have never seen, and watch it explain itself.",
        problemStatement = "A new engineer faces forty thousand lines with no map, and the only current fix is a senior engineer's afternoon.",
        targetUser = "engineers joining a team",
        confidence = "high",
        capabilities = listOf(
            Capability("cap-map", "See the whole shape at once", "Everything is on one picture instead of forty files", "ProjectFlowAnalyzer walks the file index and emits a graph", listOf("file:Analyzer.kt"), "high"),
            Capability("cap-film", "Watch it rather than read it", "A short film explains the work to anyone in the room", "GSAP timeline rendered in JCEF, exported to MP4", listOf("file:timeline.js"), "high"),
            Capability("cap-deck", "Hand it to the room", "A deck you can present without writing slides", "OOXML written straight to a zip", listOf("file:PptxWriter.kt"), "high"),
            Capability("cap-click", "Click straight into the code", "Stop the film on a step and land on the line that does it", "JBCefJSQuery to OpenFileDescriptor", listOf("file:bridge.js"), "medium")
        ),
        architecture = Architecture(
            layers = listOf(
                Layer("Analysis", listOf(Component("EvidenceHarvester", "Kotlin"), Component("ChainDetector", "Kotlin"))),
                Layer("Understanding", listOf(Component("NarrativeEngine", "OpenAI"), Component("ProductModelCache", null))),
                Layer("Presentation", listOf(Component("Reel runtime", "GSAP"), Component("PptxWriter", "OOXML")))
            ),
            dataStores = listOf("on disk cache")
        ),
        techStack = listOf(
            TechItem("Kotlin", "Language", "the platform speaks it"),
            TechItem("GSAP", "Animation", "one seekable timeline"),
            TechItem("OpenAI", "AI", "understanding, once per project"),
            TechItem("Gradle", "Build", "the IntelliJ plugin toolchain")
        ),
        keyFlows = listOf(
            KeyFlow("From a click to a picture", listOf(
                FlowStep("A developer", "opens the tool window", null),
                FlowStep("Nexus", "reads every file in the project", null),
                FlowStep("The analyser", "matches what calls what", null),
                FlowStep("The panel", "draws the result", null)
            ))
        ),
        scaleFacts = listOf(
            ScaleFact("Files read", "38", null),
            ScaleFact("Lines of source", "7,420", null),
            ScaleFact("Languages", "3", null)
        ),
        gaps = listOf("No tests were found outside the reel", "Twelve calls have no matching handler")
    )

    // --------------------------------------------------------------------- tests

    @Test
    fun `both decks are written and open as real packages`() {
        val icons = DeckIcons.load()
        assertEquals("every icon should be on the classpath", DeckIcons.NAMES.size, icons.size)

        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), audience), evidence())
            val art = DeckGeometry.render(checked.deck)
            assertTrue("$audience deck should have real slides", art.size >= Caps.MIN_SLIDES)

            val file = PptxWriter.write(art, icons, "Nexus $audience", "Nexus", File(out, "nexus-$audience.pptx"))
            assertTrue("the file should exist", file.isFile)

            // The parts a reader looks for first. A package missing any of these is the
            // one that opens with a repair dialog instead of a slide.
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }.toSet()
                listOf(
                    "[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml",
                    "ppt/_rels/presentation.xml.rels", "ppt/slideMasters/slideMaster1.xml",
                    "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml",
                    "ppt/notesMasters/notesMaster1.xml"
                ).forEach { part -> assertTrue("$audience deck is missing $part", part in names) }

                for (i in 1..art.size) {
                    assertTrue("missing slide $i", "ppt/slides/slide$i.xml" in names)
                    assertTrue("missing rels for slide $i", "ppt/slides/_rels/slide$i.xml.rels" in names)
                    assertTrue("missing notes for slide $i", "ppt/notesSlides/notesSlide$i.xml" in names)
                }
                // Every part has to be well formed, or nothing opens it.
                names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { part ->
                    val text = zip.getInputStream(zip.getEntry(part)).readBytes().toString(Charsets.UTF_8)
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                        .newDocumentBuilder()
                        .parse(text.byteInputStream())
                }
            }
        }
    }

    @Test
    fun `every image a slide points at is actually in the package`() {
        val icons = DeckIcons.load()
        val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), Audience.TECHNICAL), evidence())
        val art = DeckGeometry.render(checked.deck)
        val file = PptxWriter.write(art, icons, "rels", "Nexus", File(out, "rels-check.pptx"))

        ZipFile(file).use { zip ->
            val media = zip.entries().toList().map { it.name }.filter { it.startsWith("ppt/media/") }.toSet()
            assertTrue("a deck with icons should embed some", media.isNotEmpty())
            for (i in 1..art.size) {
                val rels = zip.getInputStream(zip.getEntry("ppt/slides/_rels/slide$i.xml.rels"))
                    .readBytes().toString(Charsets.UTF_8)
                val slide = zip.getInputStream(zip.getEntry("ppt/slides/slide$i.xml"))
                    .readBytes().toString(Charsets.UTF_8)
                // Every r:embed on the slide must name a relationship that exists, and every
                // image relationship must point at a part that is really in the zip.
                Regex("r:embed=\"(rId\\d+)\"").findAll(slide).forEach { match ->
                    assertTrue(
                        "slide $i references ${match.groupValues[1]} with no such relationship",
                        rels.contains("Id=\"${match.groupValues[1]}\"")
                    )
                }
                Regex("Target=\"\\.\\./(media/[^\"]+)\"").findAll(rels).forEach { match ->
                    assertTrue("slide $i points at a missing ${match.groupValues[1]}", "ppt/${match.groupValues[1]}" in media)
                }
            }
        }
    }

    @Test
    fun `nothing on a slide is drawn outside the slide`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), audience), evidence())
            DeckGeometry.render(checked.deck).forEachIndexed { index, slide ->
                slide.shapes.forEach { shape ->
                    val where = "$audience slide ${index + 1}, ${shape::class.simpleName}"
                    assertTrue("$where starts left of the slide", shape.x >= 0)
                    assertTrue("$where starts above the slide", shape.y >= 0)
                    assertTrue("$where runs off the right edge", shape.x + shape.w <= DeckTheme.W)
                    assertTrue("$where runs off the bottom edge", shape.y + shape.h <= DeckTheme.H)
                    assertTrue("$where has no size", shape.w > 0 && shape.h > 0)
                }
            }
        }
    }

    @Test
    fun `text is predicted to fit the box it was given`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), audience), evidence())
            DeckGeometry.render(checked.deck).forEachIndexed { index, slide ->
                slide.shapes.filterIsInstance<Label>().forEach { label ->
                    val needed = label.lines.sumOf { line ->
                        DeckTheme.linesNeeded(line, label.w, label.sizePx, label.bold)
                    } * label.sizePx * label.linePct / 100
                    assertTrue(
                        "$audience slide ${index + 1}: '${label.lines.firstOrNull()?.take(30)}' " +
                            "needs ${needed}px in a ${label.h}px box",
                        needed <= label.h + label.sizePx
                    )
                }
            }
        }
    }

    @Test
    fun `the stakeholder deck speaks no engineering`() {
        val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), Audience.STAKEHOLDER), evidence())
        DeckGeometry.render(checked.deck).forEachIndexed { index, slide ->
            slide.shapes.filterIsInstance<Label>().forEach { label ->
                label.lines.forEach { line ->
                    val hits = StoryboardValidator.bannedMatches(line, emptySet(), "Nexus")
                    assertTrue("slide ${index + 1} says ${hits.joinToString()} to a stakeholder: '$line'", hits.isEmpty())
                }
            }
        }
        assertFalse(
            "a stakeholder deck must never carry an architecture slide",
            checked.deck.slides.any { it.layout in SlideLayout.TECHNICAL_ONLY }
        )
    }

    @Test
    fun `every slide carries speaker notes`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), audience), evidence())
            DeckGeometry.render(checked.deck).forEachIndexed { index, slide ->
                assertTrue(
                    "$audience slide ${index + 1} has nothing for the presenter to say",
                    slide.notes.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `a project with no model still gets a whole deck`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val deck = DeckComposer.fromEvidence(evidence(), audience)
            val checked = DeckValidator.validate(deck, evidence())
            val art = DeckGeometry.render(checked.deck)
            assertTrue("$audience fallback deck was empty", art.isNotEmpty())
            PptxWriter.write(art, DeckIcons.load(), "fallback", "Nexus", File(out, "fallback-$audience.pptx"))
        }
    }

    /**
     * Writes every slide as its own one slide package.
     *
     * Not an assertion, a darkroom. Hand writing OOXML buys no dependency and total
     * control, and the price is that the result has to be looked at rather than assumed.
     * An office suite exports the first slide of a file, so one file per slide is what
     * turns a single `soffice --convert-to png` over `build/decks/slides` into a contact
     * sheet of the whole deck. Every real layout defect in the film was found by looking
     * at frames like these, and none of them by reading the code.
     */
    @Test
    fun `each slide is written on its own so it can be looked at`() {
        val slides = File(out, "slides").apply { mkdirs() }
        slides.listFiles()?.forEach { it.delete() }
        val icons = DeckIcons.load()
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), audience), evidence())
            DeckGeometry.render(checked.deck).forEachIndexed { index, art ->
                PptxWriter.write(
                    listOf(art), icons, art.title, "Nexus",
                    File(slides, "%s-%02d.pptx".format(audience, index + 1))
                )
            }
        }
        assertTrue("nothing was written to look at", slides.listFiles().orEmpty().isNotEmpty())
    }

    /**
     * Dumps what the tab's preview is handed, so it can be loaded in a plain browser.
     *
     * The .pptx can be opened and looked at. The preview cannot, without an IDE around
     * it, and an unlooked-at renderer is exactly where the film hid a bug for a week.
     */
    @Test
    fun `the preview payload is written so it can be loaded in a browser`() {
        listOf(Audience.TECHNICAL, Audience.STAKEHOLDER).forEach { audience ->
            val checked = DeckValidator.validate(DeckComposer.fromModel(model(), evidence(), audience), evidence())
            val art = DeckGeometry.render(checked.deck)
            val payload = DeckPreviewJson.of(
                deck = checked.deck, art = art, fileName = "nexus-$audience.pptx",
                slides = art.size, byAi = true, cacheHit = false, issues = checked.issues
            )
            File(out, "preview-$audience.json").writeText(payload.toString())
            assertEquals("every slide should be in the payload", art.size, payload.getAsJsonArray("slides_art").size())
        }
    }

    /**
     * The port in [DeckIcons] mirrors `runtime/icons.js`, and a comment asking people to
     * keep two lists in step is not a mechanism. This is the mechanism.
     */
    @Test
    fun `the deck and the film choose icons from the same catalogue`() {
        val js = javaClass.classLoader.getResourceAsStream("yasin-reel/runtime/icons.js")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue("runtime/icons.js should be on the classpath", js != null)

        val inFilm = Regex("'([a-z]+)':\\s*\\{\\s*tint:").findAll(js!!).map { it.groupValues[1] }.toList()
        assertEquals("the two icon sets have drifted apart", DeckIcons.NAMES.sorted(), inFilm.sorted())

        inFilm.forEach { name ->
            val block = Regex("'$name':\\s*\\{\\s*tint:[^}]*?words:\\s*(\\[[^\\]]*\\])").find(js)
            assertTrue("no keywords for $name in icons.js", block != null)
            val filmWords = Gson().fromJson(block!!.groupValues[1], Array<String>::class.java).toList()
            assertEquals("keywords for $name have drifted apart", filmWords, DeckIcons.KEYWORDS.getValue(name))
        }
    }
}
