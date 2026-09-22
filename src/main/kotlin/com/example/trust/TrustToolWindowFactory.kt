package com.example.trust

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * The Trust tab: the whole project's unproven code in one list.
 *
 * The wash in the editor answers "can I trust the line I am looking at". This answers the
 * question you ask before you open anything: "where in this project am I building on code
 * that has never run". Clicking a row lands on the first such line, because a list you
 * cannot act from is a report, and nobody reads reports.
 *
 * Plain Swing on purpose. The Map, Reel and Deck tabs earn their web views by drawing things
 * Swing cannot; a list of files does not, and a Swing list costs no browser, no dev server
 * and no start-up time.
 */
class TrustToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TrustPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class TrustPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<FileTrust>()
    private val summary = JBLabel()
    private val paintButton = JButton()

    private val list = JBList(model).apply {
        emptyText.text = "No execution data yet"
        emptyText.appendLine("Add .nexus/trust.json, or run your tests with coverage once that source lands.")
        cellRenderer = object : ColoredListCellRenderer<FileTrust>() {
            override fun customizeCellRenderer(
                list: JList<out FileTrust>,
                value: FileTrust,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                val name = value.path.substringAfterLast('/')
                val folder = value.path.substringBeforeLast('/', "")

                append(name)
                if (folder.isNotEmpty()) {
                    append("  $folder", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                append(
                    "   ${value.unprovenLines} never run",
                    SimpleTextAttributes.ERROR_ATTRIBUTES,
                )
                if (value.staleLines > 0) {
                    append(
                        "   ${value.staleLines} stale",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
            }
        }
    }

    init {
        border = JBUI.Borders.empty(8)

        val header = JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    add(paintButton)
                    add(JButton("Refresh").apply { addActionListener { reload() } })
                },
                BorderLayout.EAST,
            )
            border = JBUI.Borders.emptyBottom(8)
        }

        paintButton.addActionListener {
            TrustWidget.toggle(project)
            updatePaintButton()
        }

        // One click is a selection, two is an intent: only the second one moves the editor.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openSelected()
            }
        })

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)

        reload()
    }

    private fun reload() {
        val service = TrustService.getInstance(project)
        val known = service.allKnown().values

        model.clear()
        known.filterNot { it.isClean }
            .sortedByDescending { it.unprovenLines }
            .forEach(model::addElement)

        val files = model.size()
        val lines = (0 until files).sumOf { model.getElementAt(it).unprovenLines }

        summary.text = when {
            known.isEmpty() -> "No execution data for this project"
            files == 0 -> "Everything known here has run at least once"
            else -> "$lines line(s) across $files file(s) have never been executed"
        }
        summary.toolTipText = "Source: ${service.sourceLabel}"
        updatePaintButton()
    }

    private fun updatePaintButton() {
        paintButton.text =
            if (TrustService.getInstance(project).enabled) "Hide paint" else "Paint in editor"
    }

    /** Opens the file at the first line nothing has ever run, which is the line worth seeing. */
    private fun openSelected() {
        val trust = list.selectedValue ?: return
        val base = project.basePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath("$base/${trust.path}") ?: return
        val line = trust.unproven.firstOrNull()?.start ?: 1

        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }
}
