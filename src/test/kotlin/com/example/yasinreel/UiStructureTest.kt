package com.example.yasinreel

import com.example.yasinreel.harvest.UiStructureHarvester
import com.example.yasinreel.model.DesignSystem
import com.example.yasinreel.model.ProductUi
import com.example.yasinreel.model.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The interface read off real projects, because there is no other way to know.
 *
 * A fixture would prove the regexes match the fixture. What has to be true is that a
 * stranger's repository yields the words its own users see, and, far more important,
 * that it never yields the words its own users must not see. Several of the assertions
 * below are negative for that reason: they name specific strings that were measured
 * reaching a slide from these exact repositories, and they fail if any of them comes back.
 */
class UiStructureTest {

    private val desktop = File(System.getProperty("user.home"), "Desktop")

    private val ignored = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage",
        ".next", ".nuxt", ".venv", "venv", "__pycache__", ".gradle", ".idea", ".git"
    )
    private val uiFile = Regex("""(?i)\.(tsx|jsx|ts|js|mjs|vue|svelte|html)$""")

    /** A design system good enough that [ProductUi.usable] turns on the parts that need one. */
    private val anyDesign = DesignSystem(
        roles = Roles("FFFFFF", "FAFAFA", "E5E5E7", "0F1011", "71717A", "06B6A2", "FFFFFF", "EBF9F8"),
        confidence = DesignSystem.TOKENS
    )

    private fun sourcesOf(root: File): List<UiStructureHarvester.Source> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it.name !in ignored }
            .filter { it.isFile && it.length() in 1..512_000 && uiFile.containsMatchIn(it.name) }
            .take(1200)
            .map { UiStructureHarvester.Source(it.relativeTo(root).path, it.readText()) }
            .toList()
    }

    private fun read(name: String): ProductUi? {
        val root = File(desktop, name)
        if (!root.isDirectory) return null
        return UiStructureHarvester.from(sourcesOf(root), anyDesign)
    }

    private fun labels(ui: ProductUi) = ui.nav.map { it.label }

    @Test
    fun `the flagship project yields its real sidebar, in order, with its badges`() {
        val ui = read("42-studio") ?: return
        // Read by hand out of frontend/src/components/Sidebar.tsx, and independently the
        // same eight rows the reference implementation put on screen for this project.
        assertEquals(
            listOf("Dashboard", "Brand", "Assets", "Ideation", "Producer", "SMAA intelligence", "Library", "Settings"),
            labels(ui)
        )
        assertEquals("both beta tags are real and both survive", 2, ui.nav.count { it.badge == "beta" })
        assertTrue("it came from the shell, not from a settings menu", ui.source!!.contains("Sidebar"))
        assertTrue(ui.usable)
    }

    @Test
    fun `the flagship project yields the stage labels, not the internal keys`() {
        val ui = read("42-studio") ?: return
        if (ui.stages.isEmpty()) return
        // The file holds both, ten lines apart, and its own comment warns that the keys
        // stay while the labels change. Taking the first match takes the wrong one.
        assertFalse("the internal keys must never be shown: ${ui.stages}", ui.stages.contains("producer"))
        assertEquals(listOf("Ideation", "Editing", "Finalization", "Scheduling", "Approval"), ui.stages)
    }

    @Test
    fun `a project whose words live only in its markup is still read`() {
        // This repo's own visualizer declares no navigation array at all: the three
        // labels are written into the JSX. An array-only extractor reports that a
        // project with an interface has none, which is a false statement about it.
        val root = File("visualizer-ui")
        if (!root.isDirectory) return
        val ui = UiStructureHarvester.from(sourcesOf(root), anyDesign)
        assertEquals(listOf("Code tree", "Architecture", "Activity"), labels(ui))
    }

    @Test
    fun `an internal console is never what gets recreated`() {
        val ui = read("MurAi-Frontend") ?: read("MurAi") ?: return
        val shown = labels(ui)
        // Measured reaching a slide before the path filter existed. Two are an operations
        // console, two are a platform-admin console, and the last two are commercial.
        listOf("Dead-letter", "Breaker", "System Health", "Disputes", "Change Client Plan", "Platform Staff")
            .forEach { assertFalse("an internal row reached the screen: $it in $shown", shown.contains(it)) }
        // And a legal footer is navigation without being the navigation.
        assertFalse("the legal footer is not the product: $shown", shown.contains("Privacy Policy"))
    }

    @Test
    fun `an untranslated key is never shown as a label`() {
        val ui = read("MurAi-Frontend") ?: read("MurAi") ?: return
        labels(ui).forEach {
            assertFalse("a raw i18n key reached the screen: $it", Regex("""^[a-z0-9]+([_.:-][a-z0-9]+)+$""").matches(it))
        }
    }

    @Test
    fun `every project on this machine is either read or refused, and never shows the unshowable`() {
        val names = desktop.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        var read = 0
        names.forEach { name ->
            val ui = read(name) ?: return@forEach
            if (ui.nav.isEmpty()) return@forEach
            read++
            assertTrue("$name: a navigation is at least ${ProductUi.MIN_ROWS} rows", ui.nav.size >= ProductUi.MIN_ROWS)
            (labels(ui) + ui.stages).forEach { label ->
                assertTrue("$name: '$label' is too long for a nav row", label.length <= 24)
                assertFalse("$name: '$label' carries an em dash", label.contains('\u2014'))
                assertFalse("$name: '$label' is a path or a URL", label.contains('/') || label.contains("http"))
                assertFalse(
                    "$name: '$label' is a failure state, not a feature",
                    Regex("""(?i)\b(failed|error|canned|mock|breaker|dead-letter)\b""").containsMatchIn(label)
                )
            }
            println("$name -> ${ui.source}: ${labels(ui)}" + if (ui.stages.isEmpty()) "" else " | stages ${ui.stages}")
        }
        println("Interfaces read: $read of ${names.size} directories on the Desktop")
    }

    @Test
    fun `a project with no interface is refused rather than invented`() {
        val ui = UiStructureHarvester.from(
            listOf(UiStructureHarvester.Source("main.py", "print('hello')\n")), anyDesign
        )
        assertTrue(ui.nav.isEmpty())
        assertFalse("nothing was found, so nothing may be drawn", ui.usable)
    }

    @Test
    fun `their words in our colours is not a recreation`() {
        val nav = listOf(UiRowOf("Home"), UiRowOf("Inbox"), UiRowOf("Settings"))
        val ui = ProductUi(nav = nav, design = DesignSystem())
        assertFalse("with no design system there is nothing honest to draw", ui.usable)
        assertEquals(null, ui.slots("Acme", null))
    }

    private fun UiRowOf(label: String) = com.example.yasinreel.model.UiRow(label)
}
