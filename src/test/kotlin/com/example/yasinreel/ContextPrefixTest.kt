package com.example.yasinreel

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * What `com.sun.net.httpserver` actually does when two contexts overlap.
 *
 * ### Why a running server and not a reading of the source
 *
 * [ServerRoutesTest] reads ReelServer and checks that no handler route is a prefix of a file the
 * server also serves. That check is only worth anything if the platform behaves the way it
 * claims, so this pins the behaviour itself: the longest matching prefix wins, and it is a
 * plain string prefix, with no regard for path segments.
 *
 * It is pinned because that detail cost the Trust panel everything it does. Its action route was
 * `/trust/run` and its files came from `/trust`, and `/trust/run` is a prefix of
 * `/trust/runtime/trust.js`. The panel's only script was therefore answered by the
 * run-coverage handler: every control on the page was dead, and opening the panel started a
 * coverage build on whatever project was open.
 *
 * Both halves are asserted, the trap and the way out of it, because a test that only shows the
 * fix works does not explain to the next reader why the routes are laid out as they are.
 */
class ContextPrefixTest {

    private fun serving(vararg contexts: String, ask: String): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        contexts.forEach { path ->
            server.createContext(path) { exchange ->
                try {
                    val body = path.toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (e: IOException) {
                    // Nothing to recover: the assertion below is what reports the failure.
                } finally {
                    exchange.close()
                }
            }
        }
        server.executor = null
        server.start()
        return try {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}$ask")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a handler nested under a served prefix swallows files beneath it`() {
        // The shape that broke: the script is never reached, the action handler answers instead.
        assertEquals(
            "/trust/run",
            serving("/trust", "/trust/run", ask = "/trust/runtime/trust.js"),
        )
    }

    @Test
    fun `a separate prefix cannot swallow anything under the served one`() {
        // The shape that ships. The same request now reaches the file tree.
        assertEquals(
            "/trust",
            serving("/trust", "/trust-api/run", ask = "/trust/runtime/trust.js"),
        )
        // And the action route still answers for itself.
        assertEquals(
            "/trust-api/run",
            serving("/trust", "/trust-api/run", ask = "/trust-api/run"),
        )
    }

    @Test
    fun `matching is by string, not by path segment`() {
        // The reason the trap is easy to fall into: `/trust/run` looks like a sibling of
        // `/trust/runtime`, and to this server it is a prefix of it.
        assertEquals(
            "/trust/run",
            serving("/trust", "/trust/run", ask = "/trust/runaway"),
        )
    }
}
