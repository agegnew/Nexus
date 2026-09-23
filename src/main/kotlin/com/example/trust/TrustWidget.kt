package com.example.trust

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * The light itself: a small reading in the status bar for the file in front of you.
 *
 * It says one thing and says it in numbers, because "41% of this file has never run" is an
 * argument and a coloured dot is only a mood. Clicking it turns the paint on and off.
 */
class TrustWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        val service = TrustService.getInstance(project)
        if (!service.enabled) return "Trust: off"

        val trust = currentTrust() ?: return "Trust: no data"
        if (trust.isClean) return "Trust: all run"
        return "Trust: ${trust.percentUnproven()}% never run"
    }

    override fun getTooltipText(): String {
        val service = TrustService.getInstance(project)
        if (!service.enabled) return "Nexus Trust is off. Click to paint code that has never been executed."

        val trust = currentTrust()
            ?: return "No execution data for this file yet. Source: ${service.sourceLabel}. Click to turn off."

        return buildString {
            append("${trust.unprovenLines} line(s) in this file have never been executed")
            if (trust.staleLines > 0) append(", ${trust.staleLines} changed since they last ran")
            append(". Source: ${service.sourceLabel}. Click to turn off.")
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        toggle(project)
    }

    private fun currentTrust(): FileTrust? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        return TrustService.getInstance(project).trustFor(file)
    }

    companion object {
        const val WIDGET_ID: String = "com.example.trust.widget"

        /** One switch used by the widget, the action and anything added later. */
        fun toggle(project: Project) {
            val now = TrustService.getInstance(project).toggle()
            if (now) TrustPainter.refresh(project) else TrustPainter.clearAll(project)
            update(project)
        }

        fun update(project: Project) {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(WIDGET_ID)
        }
    }
}

class TrustWidgetFactory : StatusBarWidgetFactory, DumbAware {

    override fun getId(): String = TrustWidget.WIDGET_ID

    override fun getDisplayName(): String = "Nexus Trust"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = TrustWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
