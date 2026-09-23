package com.example.trust

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

/**
 * Turns the paint on and off from the Tools menu, for people who never look at the status bar.
 *
 * [DumbAwareAction] on purpose: nothing here reads an index, so there is no reason to go grey
 * while the IDE is busy indexing, which is exactly when somebody is most likely to try it.
 */
class TrustToggleAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        event.presentation.isEnabled = project != null
        event.presentation.text = when {
            project == null -> "Show Code That Has Never Run"
            TrustService.getInstance(project).enabled -> "Hide Code That Has Never Run"
            else -> "Show Code That Has Never Run"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        TrustWidget.toggle(project)
    }
}
