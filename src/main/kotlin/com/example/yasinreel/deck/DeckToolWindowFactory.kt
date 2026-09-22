package com.example.yasinreel.deck

import com.example.yasinreel.harvest.ChangedFiles
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.ReelScope
import com.example.yasinreel.render.ReelServer
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel

/**
 * The Deck tab: two buttons, and the slides that came out of them.
 *
 * Built the same way as the Reel tab, a JCEF panel over a page served from the plugin
 * jar, because the two tabs have to look like one product and because the preview needs
 * to draw a hundred positioned shapes, which is a thing a browser does well and Swing
 * does not.
 *
 * `DumbAware` on purpose: a tool window that refuses to draw until indexing finishes is
 * a tool window that looks broken on the first open of a large project, and that is
 * exactly what the Reel tab did until it was fixed. The pipeline underneath still waits
 * for smart mode before it reads anything.
 */
class DeckToolWindowFactory : ToolWindowFactory, DumbAware {

    private val logger = Logger.getInstance(DeckToolWindowFactory::class.java)

    /**
     * The last deck built per project, so Open and Show in Finder have something to act
     * on. One factory instance serves every open project, so the project is the key.
     */
    private val delivered = ConcurrentHashMap<String, File>()

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        var disposer: Disposable? = null

        if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()

            // Created before the page loads: the message router is installed with the
            // browser, so a query made afterwards would never reach the running page.
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            query.addHandler { payload ->
                handle(project, browser, payload)
                null
            }

            browser.jbCefClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                        if (!frame.isMain) return
                        injectBridge(cefBrowser, query)
                        call(
                            browser, "ready",
                            JsonObject().apply {
                                addProperty("projectName", project.name)
                                addProperty("hint", "Ready. The first deck also reads the project; the second is quick.")
                            }
                        )
                    }
                },
                browser.cefBrowser
            )

            val url = ReelServer.getInstance().deckUrl()
            logger.info("Nexus Deck loading the deck tab from $url")
            browser.loadURL(url)
            panel.add(browser.component, BorderLayout.CENTER)

            disposer = Disposable {
                delivered.remove(project.locationHash)
                // The query holds a native handler registered against the browser, so it
                // goes first or disposal leaves a dangling callback.
                Disposer.dispose(query)
                Disposer.dispose(browser)
            }
        } else {
            panel.add(
                JBLabel("JCEF is not supported in this IDE, so the Nexus deck preview cannot be shown here."),
                BorderLayout.CENTER
            )
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        disposer?.let(content::setDisposer)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Defines the one function the page calls to reach Kotlin.
     *
     * The body has to come from [JBCefJSQuery.inject] because it carries the query id and
     * port CEF routes on, neither of which exists until the query is made.
     */
    private fun injectBridge(cefBrowser: CefBrowser, query: JBCefJSQuery) {
        val js = """
            window.NexusDeckBridge = {
                post: function (payload) {
                    ${query.inject("payload")}
                }
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
        logger.info("Nexus Deck bridge injected into the deck page")
    }

    private fun handle(project: Project, browser: JBCefBrowser, payload: String?) {
        val message = runCatching { JsonParser.parseString(payload.orEmpty()) as? JsonObject }
            .onFailure { logger.warn("Nexus Deck could not parse a bridge message: $payload", it) }
            .getOrNull() ?: return

        when (val type = message.get("type")?.asString) {
            "build" -> build(
                project,
                browser,
                message.get("audience")?.asString ?: Audience.TECHNICAL,
                // Read by ReelScope itself, so the Deck tab and the Reel tab cannot end up
                // with two readings of the same panel.
                ReelScope.from(message)
            )
            "scopes" -> sendScopes(project, browser)
            "open" -> withDeck(project) { file ->
                runCatching { BrowserUtil.browse(file) }
                    .onFailure { logger.warn("Nexus Deck could not open $file", it) }
            }
            "reveal" -> withDeck(project) { file ->
                if (RevealFileAction.isSupported()) RevealFileAction.openFile(file)
            }
            else -> logger.warn("Nexus Deck ignored an unknown bridge message of type $type")
        }
    }

    private fun withDeck(project: Project, action: (File) -> Unit) {
        val file = delivered[project.locationHash]?.takeIf { it.isFile } ?: run {
            logger.warn("Nexus Deck was asked to act on a deck that is not on disk")
            return
        }
        ApplicationManager.getApplication().invokeLater({ action(file) }, ModalityState.any())
    }

    /** The area list is the project's own modules, so the tab asks rather than guessing. */
    private fun sendScopes(project: Project, browser: JBCefBrowser) {
        val areas = runCatching { ChangedFiles.areas(project) }.getOrDefault(emptyList())
        call(browser, "scopes", JsonParser.parseString(Gson().toJson(mapOf("areas" to areas))).asJsonObject)
    }

    private fun build(project: Project, browser: JBCefBrowser, audience: String, scope: ReelScope) {
        logger.info("Nexus Deck building the $audience deck (${scope.kind}) on request from the tab")
        DeckPipeline.getInstance(project).generate(
            audience = audience,
            scope = scope,
            onProgress = { message -> call(browser, "progress", message) },
            onDone = { built ->
                delivered[project.locationHash] = built.file
                call(browser, "done", payloadOf(built, audience))
            },
            onError = { message -> call(browser, "failed", message) }
        )
    }

    private fun payloadOf(built: DeckPipeline.Built, audience: String): JsonObject =
        DeckPreviewJson.of(
            deck = built.deck,
            art = DeckGeometry.render(built.deck),
            fileName = built.file.name,
            slides = built.slides,
            byAi = built.byAi,
            cacheHit = built.cacheHit,
            issues = built.issues
        ).also {
            it.addProperty("audience", audience)
            // Shown in the result bar, so the reader can see which period is on screen.
            if (built.scope.isRecap) it.addProperty("period", "${built.scope.since} to ${built.scope.until}")
        }

    private fun call(browser: JBCefBrowser, method: String, argument: Any) {
        val json = when (argument) {
            is JsonObject -> argument.toString()
            is String -> com.google.gson.JsonPrimitive(argument).toString()
            else -> com.google.gson.Gson().toJson(argument)
        }
        ApplicationManager.getApplication().invokeLater(
            Runnable {
                val cefBrowser = browser.cefBrowser
                // Guarded in the page as well as here: a message can arrive between the
                // browser being created and deck.js having run.
                cefBrowser.executeJavaScript(
                    "if (window.NexusDeck && window.NexusDeck.$method) window.NexusDeck.$method($json);",
                    cefBrowser.url,
                    0
                )
            },
            ModalityState.any()
        )
    }
}
