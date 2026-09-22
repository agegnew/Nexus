package com.example.yasinreel.render

import com.example.MyToolWindowFactory
import com.example.yasinreel.deck.DeckToolWindowFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * The single Nexus tool window. One button on the sidebar, every view inside it.
 *
 * There used to be two registrations, "Code Visualizer" and "Reel", which put two
 * buttons on the stripe for what is one product. This composes all three views into one
 * window with a tab each: the Map, the Reel and the Deck.
 *
 * It deliberately delegates to [MyToolWindowFactory] rather than copying it, because
 * that file belongs to the rest of the team. Their factory adds its own content to the
 * window it is handed, so calling it here is enough, and they keep owning it outright
 * with no merge conflict against this work.
 */
class NexusToolWindowFactory : ToolWindowFactory, DumbAware {

    private val logger = Logger.getInstance(NexusToolWindowFactory::class.java)

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Claim port 5173 before the delegate loads, so the Map tab finds the bundled
        // build there when no dev server is running. A no-op when Vite already has it.
        ReelServer.getInstance().serveMapOnVitePort()

        // The map first: it is what the product is mainly for, and the reel is the
        // thing you reach for once you already know what you are looking at.
        runCatching { MyToolWindowFactory().createToolWindowContent(project, toolWindow) }
            .onSuccess { nameLastTab(toolWindow, VISUALIZER_TAB) }
            .onFailure {
                // A failure here must not cost the Reel tab as well, so it is caught and
                // logged rather than allowed to abort the whole window.
                logger.warn("Nexus could not build the $VISUALIZER_TAB tab", it)
            }

        runCatching { ReelToolWindowFactory().createToolWindowContent(project, toolWindow) }
            .onSuccess { nameLastTab(toolWindow, REEL_TAB) }
            .onFailure { logger.warn("Nexus could not build the $REEL_TAB tab", it) }

        // Last, because it is the output you reach for once you have watched the film and
        // want to take it into a room. Wrapped like the others so one broken tab never
        // costs the window the other two.
        runCatching { DeckToolWindowFactory().createToolWindowContent(project, toolWindow) }
            .onSuccess { nameLastTab(toolWindow, DECK_TAB) }
            .onFailure { logger.warn("Nexus could not build the $DECK_TAB tab", it) }
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
        const val REEL_TAB = "Reel"
        const val DECK_TAB = "Deck"
    }
}
