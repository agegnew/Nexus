package com.example.activity

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the seam between the two halves of the plugin.
 *
 * The Map tab loads http://localhost:5173, which ReelServer answers from the bundled
 * `nexus-map` resources whenever no Vite dev server holds the port. Those resources are
 * a build output committed to the repository, so they go stale silently: the plugin still
 * starts, the Map still renders, and the newest view is simply missing for anyone who is
 * not running `npm run dev`. Nothing else would catch that.
 */
class BundledMapTest {

    private val repoRoot = File(".").absoluteFile
        .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }

    private val mapRoot = File(repoRoot, "src/main/resources/nexus-map")

    private fun asset(name: String) = File(mapRoot, name)

    @Test
    fun `the bundled map exists`() {
        assertTrue("Missing $mapRoot — run ./gradlew copyVisualizerUi", mapRoot.isDirectory)
        assertTrue("Missing index.html in $mapRoot", asset("index.html").isFile)
    }

    @Test
    fun `every asset index html references is present`() {
        val html = asset("index.html").readText()
        val referenced = Regex("""(?:src|href)="/?(assets/[A-Za-z0-9._-]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        assertTrue("index.html references no assets at all", referenced.isNotEmpty())
        val missing = referenced.filterNot { asset(it).isFile }
        assertTrue("index.html points at files that are not bundled: $missing", missing.isEmpty())
    }

    @Test
    fun `the bundled map carries every view in the row`() {
        val scripts = File(mapRoot, "assets").listFiles { file -> file.extension == "js" }.orEmpty()
        assertTrue("No scripts bundled in $mapRoot/assets", scripts.isNotEmpty())

        // The labels of the row itself, which is the thing that changes whenever a view is
        // added or dropped, and therefore the thing whose absence means the bundle is old.
        val markers = listOf("Code tree", "Architecture", "Reel", "Deck", "Trust")
        val found = markers.filter { marker -> scripts.any { it.readText().contains(marker) } }

        assertTrue(
            "The bundled Map is stale: it is missing ${markers - found}. " +
                "Rebuild it with ./gradlew copyVisualizerUi and commit the result.",
            found.containsAll(markers)
        )
    }

    @Test
    fun `no orphaned assets are left behind from an earlier build`() {
        val html = asset("index.html").readText()
        val entryPoints = Regex("""(?:src|href)="/?(assets/[A-Za-z0-9._-]+)"""")
            .findAll(html).map { it.groupValues[1] }.toSet()

        // Lazy chunks are not named in index.html, so only entry-point duplicates are a
        // reliable sign that Sync was downgraded to Copy and stale files are piling up.
        val entryPrefixes = entryPoints.map { it.substringAfter("assets/").substringBefore('-') }
        val assets = File(mapRoot, "assets").listFiles().orEmpty().map { it.name }

        entryPrefixes.forEach { prefix ->
            val matching = assets.filter { it.startsWith("$prefix-") }
            assertTrue(
                "Several builds of \"$prefix\" are bundled: $matching — copyVisualizerUi should Sync, not Copy",
                matching.size <= 2
            )
        }
    }
}
