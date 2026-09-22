package com.example.yasinreel.render

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.jcef.JBCefBrowser

/**
 * The page a bridge is talking to.
 *
 * It exists because the film and the deck are now shown in two different places and have
 * to behave identically in both. They used to each own a browser, so every reply was
 * simply "run this in my browser". Since the Map page embeds them as panels, a reply has
 * to reach a frame inside somebody else's browser instead. Only this one line differs, so
 * only this one line is abstracted: everything above it, the routing, the progress, the
 * storyboard delivery, the export, is written once and runs unchanged either way.
 *
 * Implementations are responsible for getting onto the EDT. The routers are called from
 * pipeline callbacks on background threads and must not have to remember that.
 */
fun interface NexusPage {

    /** Runs [js] in the page, or does nothing if the page is gone. */
    fun run(js: String)

    companion object {

        /** A page that owns its browser outright, which is the standalone tab. */
        fun of(browser: JBCefBrowser): NexusPage = NexusPage { js ->
            onEdt {
                val cef = browser.cefBrowser
                cef.executeJavaScript(js, cef.url, 0)
            }
        }

        /**
         * A page living in a named iframe of [browser], which is the embedded panel.
         *
         * `getFrameByName` is asked for the frame every time rather than once, because the
         * Map page mounts and unmounts these panels as the reader switches view, so a
         * frame captured at attach time would be a frame that no longer exists. A missing
         * frame is not an error: it means nobody is looking at that panel, and the reply
         * is simply dropped.
         */
        fun inFrame(browser: JBCefBrowser, frameName: String): NexusPage = NexusPage { js ->
            onEdt {
                val frame = runCatching { browser.cefBrowser.getFrameByName(frameName) }.getOrNull()
                frame?.executeJavaScript(js, frame.url ?: "", 0)
            }
        }

        private fun onEdt(block: () -> Unit) =
            ApplicationManager.getApplication().invokeLater(Runnable { runCatching(block) }, ModalityState.any())
    }
}
