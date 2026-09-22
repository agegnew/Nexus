package com.example.yasinreel

import com.example.yasinreel.harvest.ColorMath
import com.example.yasinreel.harvest.DesignSystemHarvester
import com.example.yasinreel.model.DesignSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The design system read off real projects, because there is no other way to know.
 *
 * A fixture would prove only that the regexes match the fixture. What has to be true is
 * that a stranger's repository, written by people who never heard of this plugin, yields
 * the colours they actually see when they open their own app. So these run against real
 * checkouts when they are on the machine and skip when they are not.
 *
 * Every expected value below was read out of the project by hand first.
 */
class DesignSystemTest {

    private val desktop = File(System.getProperty("user.home"), "Desktop")

    private val styleish = Regex("""\.(css|scss|sass|less)$""")
    private val configish = Regex("""(?i)(tailwind\.config|tokens?|theme|palette)\.(js|ts|cjs|mjs|tsx)$""")
    private val ignored = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage",
        ".next", ".nuxt", ".venv", "venv", "__pycache__", ".gradle", ".idea", ".git"
    )

    /** Mirrors what EvidenceHarvester hands over: a bounded set of style and token files. */
    private fun sourcesOf(root: File): List<DesignSystemHarvester.Source> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it.name !in ignored }
            .filter { it.isFile && it.length() in 1..512_000 }
            .filter { styleish.containsMatchIn(it.name) || configish.containsMatchIn(it.name) }
            .take(400)
            .map { DesignSystemHarvester.Source(it.relativeTo(root).path, it.readText()) }
            .toList()
    }

    private fun read(name: String): DesignSystem? {
        val root = File(desktop, name)
        if (!root.isDirectory) return null
        return DesignSystemHarvester.from(sourcesOf(root))
    }

    private fun contrast(a: String, b: String) =
        ColorMath.contrast(ColorMath.parse("#$a")!!, ColorMath.parse("#$b")!!)

    /** What has to hold for any recreation to be worth drawing at all. */
    private fun assertCoherent(label: String, system: DesignSystem) {
        val roles = system.roles ?: return
        assertTrue("$label: text on the page must be readable", contrast(roles.ink, roles.page) >= 4.5)
        assertTrue("$label: secondary text must be readable", contrast(roles.dim, roles.page) >= 3.0)
        assertTrue("$label: a hairline must not read as a border", contrast(roles.line, roles.surface) < 2.2)
        assertTrue("$label: the panel must sit on the page, not fight it", contrast(roles.surface, roles.page) <= 3.0)
        assertTrue("$label: the accent must be a colour, not a grey", ColorMath.parse("#${roles.accent}")!!.chroma() >= 24)
        assertTrue(
            "$label: a label on the accent must be legible",
            contrast(roles.accentInk, roles.accent) >= 3.0
        )
        assertTrue("$label: the wash must be close to the panel", contrast(roles.accentWash, roles.surface) < 1.6)
    }

    @Test
    fun `42 studio yields the palette brag found by hand`() {
        val system = read("42-studio") ?: return
        val roles = system.roles
        assertNotNull("42-studio has a design system and it should be found", roles)
        assertCoherent("42-studio", system)
        // brag's plan lists these by hand from the same repo, which is the only
        // independent check available on whether this reads a design system correctly.
        assertEquals("the real ground", "FFFFFF", roles!!.page)
        assertEquals("the real ink", "0F1011", roles.ink)
        assertEquals("the real teal", "06B6A2", roles.accent)
        assertEquals("the real hairline", "E5E5E7", roles.line)
        assertEquals(DesignSystem.LIGHT, system.scheme)
        assertEquals(DesignSystem.TOKENS, system.confidence)
    }

    @Test
    fun `a project whose colours are all oklch still resolves`() {
        // This repo's own visualizer. Every colour in it is oklch, which a .pptx cannot
        // hold at all, so if this does not resolve the demo cannot show its own product.
        val root = File("visualizer-ui")
        if (!root.isDirectory) return
        val system = DesignSystemHarvester.from(sourcesOf(root))
        assertNotNull("the visualizer declares a full set of tokens", system.roles)
        assertCoherent("visualizer-ui", system)
        assertEquals("it ships dark and says so", DesignSystem.DARK, system.scheme)
        // The accent must not be the red it uses for unmatched calls.
        assertTrue(
            "a status colour must never be presented as the brand: got ${system.roles!!.accent}",
            ColorMath.parse("#${system.roles!!.accent}")!!.let { it.g > it.r || it.b > it.r }
        )
    }

    @Test
    fun `every real project on this machine is either read coherently or refused`() {
        val names = desktop.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        var read = 0
        names.forEach { name ->
            val system = read(name) ?: return@forEach
            assertCoherent(name, system)
            if (system.usable) read++
        }
        // Not an assertion about any one project: the point is that nothing anywhere
        // produced an unreadable or self-contradictory set, and the refusals are silent.
        println("Design systems read: $read of ${names.size} directories on the Desktop")
    }

    @Test
    fun `a project with no styling at all is refused rather than guessed`() {
        val system = DesignSystemHarvester.from(
            listOf(DesignSystemHarvester.Source("main.py", "print('hello')\n"))
        )
        assertNull("nothing was found, so nothing may be claimed", system.roles)
        assertEquals(DesignSystem.NONE, system.confidence)
    }
}
