package com.example.yasinreel.render

import com.example.MyToolWindowFactory
import com.example.trust.TrustToolWindowFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * The single Nexus tool window. One button on the sidebar, one page inside it.
 *
 * This has now collapsed twice. First there were two registrations, "Code Visualizer"
 * and "Reel", so one product put two buttons on the stripe. That became one window with
 * three tabs, Map, Reel and Deck, which was better and still wrong: the Map tab held a
 * page with its own Code tree, Architecture and Activity switcher inside it, so choosing
 * a view meant first knowing which of two rows of tabs the view you wanted lived in, and
 * there was no answer to why Activity was in one row and Deck in the other.
 *
 * So there is one row now. The film and the deck are panels in that page, attached by
 * [com.example.yasinreel.render.NexusEmbed], and this factory has nothing left to do but
 * hand the window to the page that holds everything.
 *
 * It still delegates to [MyToolWindowFactory] rather than copying it, because that file
 * belongs to the rest of the team.
 */
class NexusToolWindowFactory : ToolWindowFactory, DumbAware {

    private val logger = Logger.getInstance(NexusToolWindowFactory::class.java)

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        /*
         * Claim port 5173 before the delegate loads, so the page finds the bundled build
         * there when no dev server is running. A no-op when Vite already has it.
         *
         * It matters more than it used to. That server now answers for the film and the
         * deck as well, so when it is ours the whole product is one origin and the panels
         * are plain relative iframes. When Vite has the port, vite.config.js proxies those
         * paths to us instead, which is why the plugin asks for a known port first.
         */
        ReelServer.getInstance().serveMapOnVitePort()

        runCatching { MyToolWindowFactory().createToolWindowContent(project, toolWindow) }
            .onSuccess { nameLastTab(toolWindow, VISUALIZER_TAB) }
            .onFailure { logger.warn("Nexus could not build the $VISUALIZER_TAB view", it) }

        // Trust is the one view that is not a web page. It reads a coverage report and paints
        // the project as a treemap in Swing, so it cannot be a panel in the page above and has
        // to stay a tab of its own. Guarded like the delegate, so a fault here costs this tab
        // and not the whole window.
        runCatching { TrustToolWindowFactory().createToolWindowContent(project, toolWindow) }
            .onSuccess { nameLastTab(toolWindow, TRUST_TAB) }
            .onFailure { logger.warn("Nexus could not build the $TRUST_TAB tab", it) }
    }

    /**
     * Both factories create their content with an empty display name, which is correct
     * when a window holds one thing and useless when it holds two. The delegate has just
     * appended its own content, so the last one is the one to label.
     */
    private fun nameLastTab(toolWindow: ToolWindow, title: String) {
        toolWindow.contentManager.contents.lastOrNull()?.displayName = title
    }

    private companion object {
        const val VISUALIZER_TAB = "Map"
        const val TRUST_TAB = "Trust"
    }
}
