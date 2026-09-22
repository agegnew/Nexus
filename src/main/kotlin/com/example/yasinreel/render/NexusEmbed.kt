package com.example.yasinreel.render

import com.example.yasinreel.deck.DeckToolWindowFactory
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * Puts the film and the deck inside the Map page, so the product has one row of tabs.
 *
 * It used to have two. The tool window carried Map, Reel and Deck, and the Map tab carried
 * a page with its own Code tree, Architecture and Activity switcher inside it, so choosing
 * a view meant knowing which of the two rows the view you wanted happened to live in.
 * There was never a good answer to why Activity was in one and Deck in the other.
 *
 * The film and the deck are now panels in that page, in iframes. Two things had to be true
 * for that to work, and both are, which is why this file is short:
 *
 *  - Downwards, `CefBrowser.getFrameByName` reaches a frame inside the page and runs script
 *    in it, so a reply lands in the panel without the page having to relay anything.
 *  - Upwards, a [JBCefJSQuery] is registered against the browser rather than against one
 *    frame, so a bridge injected into a panel reaches Kotlin directly. One query serves
 *    both panels, which is why every message carries the channel it came from.
 *
 * The routers themselves are not duplicated. [ReelToolWindowFactory] and
 * [DeckToolWindowFactory] already did all of this work against a [NexusPage]; this hands
 * them a page that happens to be a frame rather than a window of their own.
 */
object NexusEmbed {

    private val logger = Logger.getInstance(NexusEmbed::class.java)

    /** Matches the `name` attribute the Map page puts on each iframe. */
    const val REEL_FRAME = "nexus-reel"
    const val DECK_FRAME = "nexus-deck"

    private const val REEL = "reel"
    private const val DECK = "deck"

    /**
     * Attaches the two panels to a browser that is about to load the Map page.
     *
     * Must be called before the page loads: CEF installs the message router with the
     * browser, so a query created afterwards would never reach the running page. Safe to
     * call on a machine with no JCEF, and safe to fail, because the Map is the tab that
     * matters and a panel that could not attach is worth less than the window it is in.
     */
    fun attach(project: Project, browser: JBCefBrowser) {
        runCatching { wire(project, browser) }
            .onFailure { logger.warn("Nexus could not attach the film and deck panels to the Map page", it) }
    }

    private fun wire(project: Project, browser: JBCefBrowser) {
        val reel = ReelToolWindowFactory()
        val deck = DeckToolWindowFactory()
        val reelPage = NexusPage.inFrame(browser, REEL_FRAME)
        val deckPage = NexusPage.inFrame(browser, DECK_FRAME)

        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        query.addHandler { payload ->
            route(project, reel, deck, reelPage, deckPage, payload)
            null
        }
        // Tied to the browser's lifetime, which is the tool window content's, so the
        // native handler goes when the page does rather than outliving it.
        Disposer.register(browser, query)

        // Narration is served out of the project, and in this arrangement nothing else
        // tells the server where that is: the standalone tab used to, and it is gone.
        ReelServer.getInstance().serveTtsFrom(project.basePath)

        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    // The Map page itself is the main frame and has its own bridge, which
                    // belongs to the rest of the team. Only the panels are ours.
                    if (frame.isMain) return
                    when (channelOf(frame.url)) {
                        REEL -> inject(frame, REEL, "window.__reelCallback = function (payload) { %s };", query)
                        DECK -> {
                            inject(frame, DECK, "window.NexusDeckBridge = { post: function (payload) { %s } };", query)
                            deck.ready(project, deckPage)
                        }
                        else -> Unit
                    }
                }
            },
            browser.cefBrowser
        )
    }

    /**
     * Which panel a frame is, read off its URL.
     *
     * By path rather than by frame name, because a frame's name is set by the page and the
     * page is reloaded by a dev server on every edit, whereas the path is the contract this
     * plugin serves.
     */
    private fun channelOf(url: String?): String? {
        val path = url.orEmpty().substringAfter("://").substringAfter('/').substringBefore('?')
        return when {
            path.startsWith("$DECK/") -> DECK
            path.startsWith("$REEL/") -> REEL
            else -> null
        }
    }

    /**
     * Writes the bridge the panel already expects to find.
     *
     * [template] carries a single `%s` where the query's own call goes. The call has to
     * come from [JBCefJSQuery.inject] because it carries the query id and port CEF routes
     * on, and the payload is re-wrapped with its channel so one query can serve both
     * panels without either page knowing the other exists.
     */
    private fun inject(frame: CefFrame, channel: String, template: String, query: JBCefJSQuery) {
        val tagged = query.inject("JSON.stringify({ channel: '$channel', body: payload })")
        frame.executeJavaScript(template.format(tagged), frame.url ?: "", 0)
        logger.info("Nexus attached the $channel panel to the Map page")
    }

    private fun route(
        project: Project,
        reel: ReelToolWindowFactory,
        deck: DeckToolWindowFactory,
        reelPage: NexusPage,
        deckPage: NexusPage,
        payload: String?
    ) {
        val message = runCatching { JsonParser.parseString(payload.orEmpty()) as? JsonObject }
            .onFailure { logger.warn("Nexus could not read an embedded panel message: $payload", it) }
            .getOrNull() ?: return
        val body = message.get("body")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        when (message.get("channel")?.asString) {
            REEL -> reel.handleBridgeMessage(project, reelPage, body)
            DECK -> deck.handle(project, deckPage, body)
            else -> logger.warn("Nexus ignored a panel message with no channel on it")
        }
    }
}
