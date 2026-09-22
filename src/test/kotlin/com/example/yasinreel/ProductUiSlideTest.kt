package com.example.yasinreel

import com.example.yasinreel.deck.Deck
import com.example.yasinreel.deck.DeckGeometry
import com.example.yasinreel.deck.PptxWriter
import com.example.yasinreel.deck.Slide
import com.example.yasinreel.deck.SlideLayout
import com.example.yasinreel.harvest.DesignSystemHarvester
import com.example.yasinreel.harvest.UiStructureHarvester
import com.example.yasinreel.model.ProductUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recreated interface, drawn end to end from a real repository onto a real .pptx.
 *
 * The two halves are tested separately elsewhere: that the words are right, and that the
 * colours are right. This is the only place they meet, and the thing it is really here to
 * catch is a slide that renders but is wrong, which no assertion about the harvest can see.
 * So it writes the file out as well, for looking at.
 */
class ProductUiSlideTest {

    private val desktop = File(System.getProperty("user.home"), "Desktop")
    private val out = File(System.getProperty("java.io.tmpdir"), "nexus-product-ui")

    private val styleish = Regex("""(?i)\.(css|scss|sass|less)$|(tailwind\.config|tokens?|theme|palette)\.(js|ts|cjs|mjs|tsx)$""")
    private val uiish = Regex("""(?i)\.(tsx|jsx|ts|js|mjs|vue|svelte|html)$""")
    private val ignored = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage",
        ".next", ".nuxt", ".venv", "venv", "__pycache__", ".gradle", ".idea", ".git"
    )

    private fun uiOf(root: File): ProductUi? {
        if (!root.isDirectory) return null
        val files = root.walkTopDown()
            .onEnter { it.name !in ignored }
            .filter { it.isFile && it.length() in 1..512_000 }
            .take(3000)
            .toList()
        val design = DesignSystemHarvester.from(
            files.filter { styleish.containsMatchIn(it.name) }
                .map { DesignSystemHarvester.Source(it.relativeTo(root).path, it.readText()) }
        )
        return UiStructureHarvester.from(
            files.filter { uiish.containsMatchIn(it.name) }
                .map { UiStructureHarvester.Source(it.relativeTo(root).path, it.readText()) },
            design
        )
    }

    private fun deckOf(name: String, ui: ProductUi): Deck {
        val slots = ui.slots(name, null)!!
        slots.addProperty("eyebrow", "The product")
        slots.addProperty("heading", "What it looks like")
        return Deck("stakeholder", name, "", name, listOf(Slide(SlideLayout.PRODUCT_UI, slots, "notes")))
    }

    @Test
    fun `the slide is drawn in the product's colours and nowhere in ours`() {
        val ui = uiOf(File(desktop, "42-studio")) ?: return
        if (!ui.usable) return
        val art = DeckGeometry.render(deckOf("42 Studio", ui)).single()

        val roles = ui.design.roles!!
        val fills = art.shapes.filterIsInstance<com.example.yasinreel.deck.Box>().map { it.fill }.toSet()
        val theirs = setOf(
            roles.page, roles.surface, roles.line, roles.ink, roles.dim,
            roles.accent, roles.accentWash, roles.accentInk
        )
        // The header rule above the frame is deck furniture and is allowed to be ours.
        // Everything that draws the interface itself has to be theirs.
        val ours = fills - theirs - com.example.yasinreel.deck.DeckTheme.ACCENTS.toSet()
        assertTrue("every fill inside the frame must be the product's own: $ours", ours.isEmpty())
        assertTrue("the product's accent is on the slide", fills.contains(roles.accent))

        // Their eight rows, all eight of them, and not six.
        val labels = art.shapes.filterIsInstance<com.example.yasinreel.deck.Label>().flatMap { it.lines }
        ui.nav.take(ProductUi.MAX_ROWS).forEach { row ->
            assertTrue("the row '${row.label}' is missing from the slide", labels.any { it.contains(row.label) })
        }
        assertEquals("the frame is boxes and text only, never an image", 0, art.shapes.count { it is com.example.yasinreel.deck.Pic })
    }

    @Test
    fun `nothing on the slide spills off the frame`() {
        val ui = uiOf(File(desktop, "42-studio")) ?: return
        if (!ui.usable) return
        val art = DeckGeometry.render(deckOf("42 Studio", ui)).single()
        art.shapes.forEach {
            assertTrue("a shape starts off the slide: $it", it.x >= 0 && it.y >= 0)
            assertTrue("a shape runs off the right edge: $it", it.x + it.w <= com.example.yasinreel.deck.DeckTheme.W)
            assertTrue("a shape runs off the bottom: $it", it.y + it.h <= com.example.yasinreel.deck.DeckTheme.H)
        }
    }

    /** Writes one .pptx per readable project, so the result can actually be looked at. */
    @Test
    fun `write the slide for every project that has one`() {
        out.mkdirs()
        val names = desktop.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty() + listOf(".")
        names.forEach { name ->
            val root = if (name == ".") File("visualizer-ui") else File(desktop, name)
            val ui = uiOf(root) ?: return@forEach
            if (!ui.usable) return@forEach
            val label = if (name == ".") "visualizer-ui" else name
            val deck = deckOf(label, ui)
            val file = File(out, "$label.pptx")
            PptxWriter.write(DeckGeometry.render(deck), emptyMap(), label, "Nexus", file)
            println("wrote ${file.absolutePath} (${ui.nav.size} rows, accent ${ui.design.roles?.accent})")
        }
    }
}
