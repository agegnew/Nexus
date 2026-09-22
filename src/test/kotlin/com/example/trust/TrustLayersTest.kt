package com.example.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rollup, which is what turns a list of files into a sentence about the project.
 */
class TrustLayersTest {

    private fun file(path: String, never: Int, total: Int) = FileTrust(
        path = path,
        unproven = if (never > 0) listOf(LineRange(1, never)) else emptyList(),
        totalLines = total,
    )

    @Test
    fun `the folder prefix every file shares is stripped from the labels`() {
        val layers = TrustLayers.of(
            listOf(
                file("backend/app/services/report.py", 90, 100),
                file("backend/app/agents/vuln.py", 50, 50),
                file("backend/app/api/v1/auth.py", 10, 100),
            ),
        )

        // "backend/app" is on every one of them, so it earns no space on screen.
        assertEquals(setOf("services", "agents", "api/v1"), layers.map { it.name }.toSet())
        // The full folder is kept, because navigation needs the real path.
        assertTrue(layers.any { it.folder == "backend/app/api/v1" })
    }

    @Test
    fun `worst folder comes first, because that is the one worth saying out loud`() {
        val layers = TrustLayers.of(
            listOf(
                file("src/util/small.kt", 5, 10),
                file("src/agents/big.kt", 300, 300),
                file("src/api/mid.kt", 40, 100),
            ),
        )

        assertEquals(listOf("agents", "api", "util"), layers.map { it.name })
        assertEquals(100, layers.first().percentUnproven())
    }

    @Test
    fun `a prefix is never stripped so hard that a folder loses its name`() {
        // "app" is shared, but it is also a folder in its own right, so stripping it would
        // leave the first file with an empty label.
        val layers = TrustLayers.of(
            listOf(file("app/main.py", 1, 10), file("app/sub/other.py", 2, 10)),
        )

        assertEquals(setOf("app", "app/sub"), layers.map { it.name }.toSet())
    }

    @Test
    fun `files at the project root group under a name rather than an empty label`() {
        val layers = TrustLayers.of(listOf(file("build.gradle.kts", 3, 9)))

        assertEquals(TrustLayers.ROOT, layers.single().name)
    }

    @Test
    fun `a folder totals its files and counts its dead`() {
        val layers = TrustLayers.of(
            listOf(
                file("a/one.py", 10, 10).copy(reachability = Reachability.UNREFERENCED),
                file("a/two.py", 5, 20),
            ),
        )

        val layer = layers.single()
        assertEquals(15, layer.unprovenLines)
        assertEquals(30, layer.totalLines)
        assertEquals(50, layer.percentUnproven())
        assertEquals(1, layer.deadFiles)
    }

    @Test
    fun `no files means no layers, not a layer with nothing in it`() {
        assertTrue(TrustLayers.of(emptyList()).isEmpty())
    }
}
