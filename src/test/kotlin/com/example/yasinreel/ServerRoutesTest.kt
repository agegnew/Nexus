package com.example.yasinreel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The routes the plugin's HTTP server answers, against the files it serves.
 *
 * ### The failure this exists to stop happening again
 *
 * `com.sun.net.httpserver` picks a handler by plain string prefix, longest match first. The
 * trust panel's action route was `/trust/run` and its files were served from `/trust`, and
 * `/trust/run` is a prefix of `/trust/runtime/trust.js`. So a request for the panel's only
 * script was answered by the run-coverage handler: the page shipped with no JavaScript at all,
 * every control on it was dead, and merely opening the panel started a coverage build on
 * whatever project was open. Two faults at once, each hiding the other, and neither visible in
 * a compiler or in any test that did not know the two lists had to be compared.
 *
 * Reading the source rather than starting a server on purpose: this has to hold for a route
 * somebody adds next month, and a test that binds a port to find that out is a test people
 * switch off.
 */
class ServerRoutesTest {

    private val source = File("src/main/kotlin/com/example/yasinreel/render/ReelServer.kt").readText()

    private val resources = File("src/main/resources")

    private val constants = Regex("""private const val (\w+) = "([^"]*)"""")
        .findAll(source)
        .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * Every `createContext` line, as the path it registers and whether it serves a file tree.
     *
     * The distinction is the whole test. A static root is SUPPOSED to prefix every file beneath
     * it: that is how `/reel` serves `/reel/runtime/player.js`. A handler that prefixes a file
     * is the bug, because the file is then never reached.
     */
    private fun routes(): List<Pair<String, Boolean>> =
        source.lineSequence()
            .filter { it.contains("createContext(") }
            .mapNotNull { line ->
                val call = Regex("""createContext\(([^)]*)\)""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                // `"/" + TRUST_API + "/run"` and `"/nexus.json"` are both written here.
                val path = Regex("""("([^"]*)")|(\b[A-Z_]{2,}\b)""").findAll(call).joinToString("") { piece ->
                    piece.groupValues[2].ifEmpty { constants[piece.groupValues[3]] ?: "" }
                }
                if (!path.startsWith("/")) null else path to line.contains("serve(")
            }
            .filter { it.first != "/" }
            .toList()

    /** Every file actually served, as the path a browser would ask for. */
    private fun served(root: String, prefix: String): List<String> {
        val dir = File(resources, root)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { "/$prefix/" + it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    }

    @Test
    fun `no handler route is a prefix of a file the server also serves`() {
        val all = routes()
        assertTrue("found no routes at all, the reader is broken: $all", all.size >= 5)
        val handlers = all.filterNot { it.second }.map { it.first }
        assertTrue("found no handler routes, the reader is broken: $all", handlers.isNotEmpty())

        val files = served("yasin-reel", "reel") +
            served("yasin-deck", "deck") +
            served("yasin-shared", "shared") +
            served("yasin-trust", "trust")

        val shadowed = files.filter { file -> handlers.any { file.startsWith(it) } }

        assertTrue(
            "these files are swallowed by a handler route that prefixes them: $shadowed\n" +
                "handler routes: $handlers\n" +
                "Give the handler its own top level prefix rather than nesting it under a served one.",
            shadowed.isEmpty(),
        )
    }

    @Test
    fun `every route that changes something requires POST`() {
        // A GET must never be able to start a build or flip a setting: anything can issue one,
        // including a browser prefetching a link, and one of them did.
        listOf("trustRun", "trustPaint").forEach { handler ->
            val body = Regex("""private fun $handler\(exchange: HttpExchange\) \{(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
                .find(source)?.groupValues?.get(1)
            assertTrue("could not find $handler, was it renamed?", body != null)
            assertTrue("$handler does not guard on the request method", body!!.contains("requirePost(exchange)"))
        }
    }

    @Test
    fun `the page asks for the routes the server answers`() {
        val page = File("src/main/resources/yasin-trust/runtime/trust.js").readText()
        val asked = Regex("""fetch\('(/[^']+)'""").findAll(page).map { it.groupValues[1].substringBefore('?') }.toSet()
        assertFalse("the trust page fetches nothing, the reader is broken", asked.isEmpty())

        val routes = routes().map { it.first }.toSet()
        val unanswered = asked.filterNot { url -> routes.any { url == it || url.startsWith("$it/") } }
        assertTrue("the page asks for paths no route serves: $unanswered, routes: $routes", unanswered.isEmpty())
    }
}
