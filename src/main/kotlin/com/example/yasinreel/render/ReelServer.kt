package com.example.yasinreel.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serves the Nexus Reel player out of the plugin jar over loopback.
 *
 * Two reasons this exists instead of [com.intellij.ui.jcef.JBCefBrowser.loadHTML]:
 *  - relative asset URLs (fonts, audio, the GSAP bundle) only resolve against a real origin
 *  - `127.0.0.1` is a secure context, so the browser APIs the player needs are all available
 *
 * It also means the Reel tab works with nothing else running, unlike the `visualizer-ui`
 * tool window which still needs `npm run dev` on port 5173.
 */
@Service(Service.Level.APP)
class ReelServer : Disposable {

    private val logger = Logger.getInstance(ReelServer::class.java)
    private var server: HttpServer? = null
    private var mapServer: HttpServer? = null

    /**
     * Where narration clips live, outside the plugin jar.
     *
     * The server is application level but the clips belong to one project, so the tool
     * window hands over the open project's path when it loads the player. Last one wins,
     * which matches the fact that only one Reel tab can be playing at a time anyway.
     */
    @Volatile
    private var ttsRoot: Path? = null

    /** Called by the tool window alongside [baseUrl], so `tts/<hash>.mp3` resolves. */
    fun serveTtsFrom(path: String?) {
        ttsRoot = path
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it, IDEA_DIR, WORK_DIR, TTS_DIR) }.getOrNull() }
        logger.info("Nexus Reel narration will be served from ${ttsRoot ?: "nowhere, no project path"}")
    }

    /** Loopback base URL, e.g. `http://127.0.0.1:52431`. Starts the server on first call. */
    @Synchronized
    fun baseUrl(): String {
        val running = server ?: start().also { server = it }
        return "http://127.0.0.1:${running.address.port}"
    }

    /**
     * The deck tab's page, on the same server under its own prefix.
     *
     * A second port would mean a second thing that can fail to bind. One server with two
     * contexts costs nothing, and the longest matching prefix wins, so `/deck` takes
     * everything under it and the reel keeps the rest.
     */
    fun deckUrl(): String = baseUrl() + "/" + DECK_PREFIX + "/index.html"

    fun reelUrl(): String = baseUrl() + "/" + REEL_PREFIX + "/index.html"

    /** The port actually bound, which is [PREFERRED_PORT] unless something already had it. */
    @Synchronized
    fun port(): Int {
        val running = server ?: start().also { server = it }
        return running.address.port
    }

    /**
     * Serves the built visualizer-ui on the port its own tool window already asks for.
     *
     * MyToolWindowFactory hard-codes http://localhost:5173, so the Map tab is blank
     * unless someone remembers to run `npm run dev`. A shipped plugin would always show
     * an empty panel. Rather than edit that file, which the rest of the team owns, we
     * answer on the port it is already calling.
     *
     * If Vite really is running it holds the port, bind fails, and we quietly do nothing:
     * the developer keeps hot reload and we never fight them for it.
     */
    @Synchronized
    fun serveMapOnVitePort() {
        if (mapServer != null) return
        val started = runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), VITE_PORT), 0).also {
                it.createContext("/") { exchange -> serve(exchange, MAP_ROOT) }
                // The film and the deck answer here too, so the page on this port can hold
                // them in an iframe with no cross origin anything and no port to discover.
                mount(it)
                it.executor = null
                it.start()
            }
        }.onFailure {
            logger.info("Nexus is leaving port $VITE_PORT alone, something else has it (probably the dev server)")
        }.getOrNull()
        if (started != null) {
            mapServer = started
            logger.info("Nexus is serving the bundled Map on 127.0.0.1:$VITE_PORT")
        }
    }

    /**
     * Tries one known port before falling back to whatever the OS has spare.
     *
     * The port used to be ephemeral on purpose, and that was right while the only thing
     * loading these pages was a tool window that had just been told the URL. Now the Map
     * page embeds the film and the deck, and when a developer is running `npm run dev`
     * that page is served by Vite, which has to proxy to us and therefore has to be
     * configured with a number in advance. A fixed first choice makes that configuration
     * possible; the fallback makes a second IDE window still work, at the cost of the
     * dev-server proxy, which is the right thing to lose of the two.
     */
    private fun start(): HttpServer {
        val http = bind(PREFERRED_PORT) ?: bind(0) ?: error("Nexus could not open a loopback port")
        http.createContext("/") { exchange -> serve(exchange, RESOURCE_ROOT) }
        mount(http)
        http.executor = null
        http.start()
        logger.info("Nexus Reel server listening on 127.0.0.1:${http.address.port}")
        return http
    }

    private fun bind(port: Int): HttpServer? = runCatching {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
    }.onFailure {
        if (port != 0) logger.info("Nexus could not take port $port, asking the OS for a spare one")
    }.getOrNull()

    /**
     * The routes both servers answer, so a page is served the same whichever port it
     * came from. Only `/` differs: the main server roots on the film, the Vite-port
     * server roots on the Map.
     */
    private fun mount(http: HttpServer) {
        http.createContext("/" + REEL_PREFIX) { exchange -> serve(exchange, RESOURCE_ROOT, REEL_PREFIX + "/") }
        http.createContext("/" + DECK_PREFIX) { exchange -> serve(exchange, DECK_ROOT, DECK_PREFIX + "/") }
        // One copy of the code both tabs share, reachable from both by absolute path.
        http.createContext("/" + SHARED_PREFIX) { exchange -> serve(exchange, SHARED_ROOT, SHARED_PREFIX + "/") }
        // How a page that was served by Vite finds the plugin. Same origin when we serve
        // the Map ourselves, and proxied by vite.config.js when the dev server is running.
        http.createContext("/nexus.json") { exchange -> describe(exchange) }
    }

    /** Announces where the plugin is answering, so an embedded page never guesses a port. */
    private fun describe(exchange: HttpExchange) {
        try {
            val body = """{"base":"${baseUrl()}","reel":"${reelUrl()}","deck":"${deckUrl()}"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } catch (e: IOException) {
            logger.warn("Nexus could not answer ${exchange.requestURI}", e)
        } finally {
            exchange.close()
        }
    }

    private fun serve(exchange: HttpExchange, root: String, strip: String = "") {
        try {
            val requested = exchange.requestURI.path.trimStart('/')
                .removePrefix(strip)
                .ifEmpty { "index.html" }

            val clip = if (requested.startsWith("$TTS_DIR/")) readClip(requested.removePrefix("$TTS_DIR/")) else null
            // A single page app asks for paths that are not files, so the Map root
            // falls back to its index rather than 404ing on a route.
            val bytes = clip ?: readResource(root, requested)
                ?: if (root == MAP_ROOT) readResource(root, "index.html") else null
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            exchange.responseHeaders.add("Content-Type", contentType(requested))
            // The player is regenerated per run; never let the browser hold a stale copy.
            exchange.responseHeaders.add("Cache-Control", "no-store")
            // Chromium range requests audio. A plain 200 plays, but without this the
            // element cannot seek, so scrubbing the reel would stall the narration.
            if (clip != null) exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: IOException) {
            logger.warn("Nexus Reel failed to serve ${exchange.requestURI}", e)
        } finally {
            exchange.close()
        }
    }

    /**
     * Reads from `resources/yasin-reel/`. Rejects anything that climbs out of that
     * prefix, so a crafted URL cannot reach the rest of the classpath.
     */
    private fun readResource(root: String, path: String): ByteArray? {
        if (path.contains("..") || path.startsWith("/")) return null
        val stream = javaClass.classLoader.getResourceAsStream("$root/$path") ?: return null
        return stream.use { it.readBytes() }
    }

    /**
     * Reads one narration clip from the project work directory.
     *
     * The name has to be exactly the content hash [com.example.yasinreel.tts.TtsClient]
     * produced, which is what stops this route from being walked into the rest of the
     * user's disk: no separator, no dot segment and no other extension can match.
     */
    private fun readClip(name: String): ByteArray? {
        val root = ttsRoot ?: return null
        if (!CLIP_NAME.matches(name)) return null
        val file = root.resolve(name)
        return runCatching {
            if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
        }.onFailure { logger.warn("Nexus Reel could not read the narration clip $name", it) }.getOrNull()
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "mp3" -> "audio/mpeg"
        "woff2" -> "font/woff2"
        "svg" -> "image/svg+xml"
        else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
    }

    override fun dispose() {
        server?.stop(0)
        server = null
        mapServer?.stop(0)
        mapServer = null
    }

    companion object {
        private const val RESOURCE_ROOT = "yasin-reel"

        /** The built visualizer-ui, copied here from visualizer-ui/dist by Gradle. */
        private const val MAP_ROOT = "nexus-map"

        /** The deck tab, served under [DECK_PREFIX] on the same port as the reel. */
        private const val DECK_ROOT = "yasin-deck"
        private const val DECK_PREFIX = "deck"

        /**
         * The film also answers under a prefix, not only at the root.
         *
         * The root still works and is what the standalone Reel tab loads. The prefix is
         * what an embedded page asks for, so the film and the deck are addressed the same
         * way from the Map page rather than one of them being "whatever is left over".
         */
        private const val REEL_PREFIX = "reel"

        /**
         * Asked for first, so `vite.config.js` has something to proxy to. Not reserved by
         * IANA and one past the dev server's own, which keeps the pair obvious.
         */
        private const val PREFERRED_PORT = 5174

        /**
         * Code both tabs load, and the reason the date range means one thing in this plugin.
         * It has its own root because it belongs to neither tab, and its own context because
         * the two tabs are served from two different roots on this one port.
         */
        private const val SHARED_ROOT = "yasin-shared"
        private const val SHARED_PREFIX = "shared"

        /** The port MyToolWindowFactory already asks for. */
        private const val VITE_PORT = 5173

        /** Mirrors TtsClient's own layout: `<project>/.idea/yasin-reel/tts/<sha256>.mp3`. */
        private const val IDEA_DIR = ".idea"
        private const val WORK_DIR = "yasin-reel"
        private const val TTS_DIR = "tts"

        private val CLIP_NAME = Regex("[0-9a-f]{64}\\.mp3")

        fun getInstance(): ReelServer = service()
    }
}
