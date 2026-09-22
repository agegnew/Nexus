package com.example.trust

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Paints unproven code in the editor, and wipes the paint when it is turned off.
 *
 * The wash is deliberately faint. A marker that makes code harder to read gets switched off
 * within a day, and then it stops being a signal at all; this one has to be visible out of the
 * corner of the eye and invisible while actually reading the line.
 *
 * Every highlighter it creates is remembered on the editor itself, so the paint can be removed
 * exactly, without touching highlighters that belong to inspections or to anybody else.
 */
object TrustPainter {

    private val HIGHLIGHTERS = Key.create<MutableList<RangeHighlighter>>("nexus.trust.highlighters")

    /** Faint red wash. The stripe colour beside the scrollbar is stronger, because it is 2px wide. */
    private val UNPROVEN_BACKGROUND = JBColor(Color(0xFD, 0xEE, 0xEC), Color(0x3A, 0x25, 0x23))
    private val UNPROVEN_STRIPE = JBColor(Color(0xD9, 0x4A, 0x3D), Color(0xC0, 0x5B, 0x50))

    /** Amber: it ran once, then the code changed underneath the proof. */
    private val STALE_BACKGROUND = JBColor(Color(0xFB, 0xF3, 0xE6), Color(0x38, 0x30, 0x22))
    private val STALE_STRIPE = JBColor(Color(0xC9, 0x8A, 0x2B), Color(0xB8, 0x8B, 0x3C))

    /**
     * Below the caret row and the selection, so the editor still looks normal where the user is
     * working. Anything higher and the wash fights with what the IDE draws for its own reasons.
     */
    private const val LAYER = HighlighterLayer.CARET_ROW - 1

    /** Repaints every open editor of the project. Cheap: the work is one pass over open files. */
    fun refresh(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            for (editor in editorsOf(project)) refresh(project, editor)
        }, project.disposed)
    }

    fun refresh(project: Project, editor: Editor) {
        clear(editor)

        val service = TrustService.getInstance(project)
        if (!service.enabled) return

        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val trust = service.trustFor(file) ?: return
        if (trust.isClean) return

        paint(editor, trust)
    }

    private fun paint(editor: Editor, trust: FileTrust) {
        val kept = editor.getUserData(HIGHLIGHTERS) ?: ArrayList<RangeHighlighter>().also {
            editor.putUserData(HIGHLIGHTERS, it)
        }
        val lastLine = editor.document.lineCount

        fun band(ranges: List<LineRange>, background: Color, stripe: Color, tooltip: String) {
            // The stripe beside the scrollbar comes from the attributes, not from a setter on the
            // highlighter: that is the only route that survives a theme change.
            val attributes = TextAttributes().apply {
                backgroundColor = background
                errorStripeColor = stripe
            }
            for (range in ranges) {
                // A fixture can outlive the code it describes, so clamp instead of throwing.
                val from = (range.start - 1).coerceIn(0, maxOf(lastLine - 1, 0))
                val to = (range.end - 1).coerceIn(from, maxOf(lastLine - 1, 0))
                for (line in from..to) {
                    val highlighter = editor.markupModel.addLineHighlighter(line, LAYER, attributes)
                    highlighter.errorStripeTooltip = tooltip
                    kept.add(highlighter)
                }
            }
        }

        band(trust.unproven, UNPROVEN_BACKGROUND, UNPROVEN_STRIPE, "Never executed")
        band(trust.stale, STALE_BACKGROUND, STALE_STRIPE, "Proven before this changed")
    }

    /** Removes only our own highlighters, by identity, never a blanket clear of the markup model. */
    fun clear(editor: Editor) {
        val kept = editor.getUserData(HIGHLIGHTERS) ?: return
        for (highlighter in kept) {
            runCatching { editor.markupModel.removeHighlighter(highlighter) }
        }
        kept.clear()
    }

    fun clearAll(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            for (editor in editorsOf(project)) clear(editor)
        }, project.disposed)
    }

    private fun editorsOf(project: Project): List<Editor> =
        EditorFactory.getInstance().allEditors.filter { it.project == project }

    /** True when the currently focused editor has something painted, for the status bar text. */
    fun selectedFile(project: Project): VirtualFile? =
        FileEditorManager.getInstance(project).selectedEditor?.file
}

/**
 * Paints a file as it is opened or brought to the front.
 *
 * Registered declaratively, so it is created by the platform and must stay stateless: everything
 * it needs lives in [TrustService] and on the editors themselves.
 */
class TrustEditorListener : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        TrustPainter.refresh(source.project)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        TrustPainter.refresh(event.manager.project)
    }
}
