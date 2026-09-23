package com.example.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SwarmLaunchTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the ready line gives the port and the brain`() {
        val ready = SwarmLaunch.parseReady("""NEXUS_SWARM_READY {"port":53123,"brain":"gpt-4o-mini","outDir":"C:/x"}""")

        assertEquals(53123, ready?.port)
        assertEquals("gpt-4o-mini", ready?.brain)
    }

    @Test
    fun `other output and broken ready lines are ignored`() {
        assertNull(SwarmLaunch.parseReady("· 5 agents testing http://localhost:5174"))
        assertNull(SwarmLaunch.parseReady("NEXUS_SWARM_READY not json"))
        assertNull(SwarmLaunch.parseReady("""NEXUS_SWARM_READY {"brain":"scripted"}"""))
        assertNull(SwarmLaunch.parseReady("""NEXUS_SWARM_READY {"port":0}"""))
    }

    @Test
    fun `the first candidate holding run mjs wins`() {
        val empty = temp.newFolder("empty")
        val real = temp.newFolder("real").also { File(it, "run.mjs").writeText("") }
        val later = temp.newFolder("later").also { File(it, "run.mjs").writeText("") }

        assertEquals(real.absoluteFile.normalize(), SwarmLaunch.findSwarmDir(listOf(null, empty, real, later)))
        assertNull(SwarmLaunch.findSwarmDir(listOf(empty, File(temp.root, "missing"))))
    }

    @Test
    fun `a project two folders below the repository still finds swarm`() {
        val repo = temp.newFolder("repo")
        val swarm = File(repo, "swarm").apply { mkdirs(); File(this, "run.mjs").writeText("") }
        val demo = File(repo, "demo/nexus-demo-app").apply { mkdirs() }

        val found = SwarmLaunch.findSwarmDir(SwarmLaunch.candidates(override = null, bundled = null, projectBase = demo.path))

        assertEquals(swarm.absoluteFile.normalize(), found)
    }

    @Test
    fun `an explicit override comes before everything else`() {
        val override = temp.newFolder("override").also { File(it, "run.mjs").writeText("") }
        val bundled = temp.newFolder("bundled").also { File(it, "run.mjs").writeText("") }

        val found = SwarmLaunch.findSwarmDir(SwarmLaunch.candidates(override.path, bundled.path, temp.root.path))

        assertEquals(override.absoluteFile.normalize(), found)
    }

    @Test
    fun `dependencies are present only once playwright is installed`() {
        val dir = temp.newFolder("swarm")
        assertFalse(SwarmLaunch.hasDependencies(dir))

        File(dir, "node_modules/playwright").mkdirs()
        assertTrue(SwarmLaunch.hasDependencies(dir))
    }

    /**
     * Forgetting `npx playwright install chromium` fails much later and far less clearly than
     * forgetting `npm install`: the runner starts, the tab connects, the five agents sit saying
     * "Waiting for a mission", and the run ends with Playwright's own message about a missing
     * executable printed into the report twice. The check has to happen before any of that.
     */
    @Test
    fun `a cache with no chromium in it is reported as no browser`() {
        val home = temp.newFolder("home")
        File(home, "Library/Caches/ms-playwright/firefox-1234").mkdirs()

        assertFalse(SwarmLaunch.hasBrowser(emptyMap(), home.path))
    }

    @Test
    fun `a cache holding a chromium is enough`() {
        val home = temp.newFolder("home")
        File(home, "Library/Caches/ms-playwright/chromium_headless_shell-1243").mkdirs()

        assertTrue(SwarmLaunch.hasBrowser(emptyMap(), home.path))
    }

    @Test
    fun `an explicit browsers path is honoured, and zero means do not guess`() {
        val home = temp.newFolder("home")
        val custom = temp.newFolder("elsewhere")
        File(custom, "chromium-1200").mkdirs()

        assertTrue(SwarmLaunch.hasBrowser(mapOf("PLAYWRIGHT_BROWSERS_PATH" to custom.path), home.path))
        // "0" keeps the browsers inside node_modules, where this cannot see them. Saying "no"
        // there would block a setup that works, which is the worse mistake of the two.
        assertTrue(SwarmLaunch.hasBrowser(mapOf("PLAYWRIGHT_BROWSERS_PATH" to "0"), home.path))
    }
}
