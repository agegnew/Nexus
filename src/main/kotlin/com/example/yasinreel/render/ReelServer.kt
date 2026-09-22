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

    private fun start(): HttpServer {
        // Port 0 asks the OS for a free ephemeral port, so we never collide with
        // another IDE window or with Vite on 5173.
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange -> serve(exchange, RESOURCE_ROOT) }
        http.executor = null
        http.start()
        logger.info("Nexus Reel server listening on 127.0.0.1:${http.address.port}")
        return http
    }

    private fun serve(exchange: HttpExchange, root: String) {
        try {
            val requested = exchange.requestURI.path.trimStart('/').ifEmpty { "index.html" }

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
