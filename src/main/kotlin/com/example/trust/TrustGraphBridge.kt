package com.example.trust

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Sends the trust verdict to the Map, so the architecture diagram can be coloured by it.
 *
 * The Map already knows what the system is made of. It has no idea which of it is real. A box
 * labelled "Service layer, 12 handlers" looks identical whether every one of those handlers is
 * covered or none of them has ever executed, and the second case is the one worth knowing
 * before you touch anything.
 *
 * Deliberately additive on both sides. The IDE pushes an event nobody is obliged to listen
 * for, and a page that has never heard of it renders exactly as it did before. If this whole
 * file were deleted the Map would keep working, which is the property that makes it safe to
 * add to a file the rest of the team owns.
 */
object TrustGraphBridge {

    private val logger = Logger.getInstance(TrustGraphBridge::class.java)
    private val gson = Gson()

    const val EVENT = "code-visualizer:trust"

    /**
     * Pushes the verdict now, and again whenever it changes.
     *
     * Tied to the browser's lifetime rather than the project's: when the tab goes away so does
     * the subscription, and nothing is left holding a disposed CEF instance.
     */
    fun attach(project: Project, browser: JBCefBrowser) {
        push(project, browser)

        runCatching {
            project.messageBus.connect(browser).subscribe(
                TrustListener.TOPIC,
                object : TrustListener {
                    override fun trustChanged() = push(project, browser)
                },
            )
        }.onFailure { logger.warn("Nexus Trust could not follow changes for the map", it) }
    }

    /**
     * Reads off the event thread, because it parses the coverage report, then hands the result
     * to the page as one small object keyed by the same project-relative path the graph uses.
     */
    private fun push(project: Project, browser: JBCefBrowser) {
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed) return@execute

            val payload = runCatching {
                val service = TrustService.getInstance(project)
                mapOf(
                    "source" to service.describeSource(),
                    // Two numbers per file is all the page needs to colour anything: any set
                    // of files sums into a percentage without another round trip.
                    "files" to service.allKnown().values.associate {
                        it.path to listOf(it.unprovenLines, it.totalLines)
                    },
                )
            }.getOrElse {
                logger.warn("Nexus Trust could not build the map payload", it)
                return@execute
            }

            val json = runCatching { gson.toJson(payload) }.getOrElse { return@execute }

            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                runCatching {
                    browser.cefBrowser.executeJavaScript(
                        """
                        window.__CODE_VISUALIZER_TRUST__ = $json;
                        window.dispatchEvent(new CustomEvent('$EVENT', { detail: $json }));
                        """.trimIndent(),
                        browser.cefBrowser.url,
                        0,
                    )
                }.onFailure { logger.warn("Nexus Trust could not reach the map", it) }
            }, ModalityState.any(), project.disposed)
        }
    }
}
