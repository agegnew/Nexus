package com.example.yasinreel.render

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Menu and search entry point for Nexus Reel.
 *
 * It only raises the tool window. Generation is started from the player's own two
 * buttons, so there is exactly one place that decides which cut is being made and
 * the action can never start a run the page is not showing progress for.
 */
class ReelAction : AnAction(
    "Generate Product Reel",
    "Open the Nexus Reel tool window and turn this codebase into a narrated product video",
    null
) {

    private val logger = Logger.getInstance(ReelAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            logger.warn("Nexus Reel tool window '$TOOL_WINDOW_ID' is not registered, so it cannot be activated")
            return
        }
        toolWindow.activate(null)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Reel"
    }
}
