package com.example.trust

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * The Trust tab: the whole project's unproven code as one picture, then as a list to act on.
 *
 * The wash in the editor answers "can I trust the line I am looking at". This answers the
 * question you ask before opening anything: "where in this project am I building on code that
 * has never run". The treemap answers it without reading, the folder bars turn it into a
 * sentence, and the list is what you click to get your cursor onto the problem.
 */
class TrustToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TrustPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // Tied to the window, so the message bus subscription dies with the tab.
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class TrustPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val model = DefaultListModel<FileTrust>()
    private val treemap = TrustTreemap()
    private val strip = TrustLayerStrip()

    private val heroNumber = JBLabel()
    private val heroSaid = JBLabel()
    private val heroSub = JBLabel()
    private val provenance = JBLabel()
    private val listHeading = JBLabel()

    private val paintButton = JButton()
    private val deadOnly = JToggleButton("Dead code only")

    /** Everything known, dead marks included once the background pass has finished. */
    private var known: List<FileTrust> = emptyList()
    private var selected: LayerSummary? = null

    private val list = JBList(model).apply {
        emptyText.text = "Nothing unproven here"
        cellRenderer = object : ColoredListCellRenderer<FileTrust>() {
            override fun customizeCellRenderer(
                list: JList<out FileTrust>,
                value: FileTrust,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.fileName)
                if (value.folder.isNotEmpty()) {
                    append("  ${value.folder}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                if (value.isDead) {
                    append("  DEAD  ", DEAD_TAG)
                }
                append(
                    "   ${value.unprovenLines} never run",
                    SimpleTextAttributes.ERROR_ATTRIBUTES,
                )
                if (value.totalLines > 0) {
                    append(
                        " of ${value.totalLines}  (${value.percentUnproven()}%)",
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                    )
                }
                if (value.changedSinceRun) {
                    append("   changed since the run", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }

    init {
        border = JBUI.Borders.empty(10)

        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        add(provenance.apply {
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyTop(6)
        }, BorderLayout.SOUTH)

        treemap.onFileActivated = ::openFile
        treemap.onLayerSelected = ::selectLayer
        strip.onLayerSelected = ::selectLayer

        // One click is a selection, two is an intent: only the second one moves the editor.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) list.selectedValue?.let(::openFile)
            }
        })

        // A finished run is the moment the answer can change, so the tab listens for it rather
        // than making the user press Refresh to find out something already happened.
        project.messageBus.connect(this).subscribe(
            TrustListener.TOPIC,
            object : TrustListener {
                override fun trustChanged() = load(animate = true)
            },
        )

        load(animate = false)
    }

    override fun dispose() = Unit

    private fun buildHeader(): JPanel {
        heroNumber.font = JBFont.label().asBold().deriveFont(JBUI.scaleFontSize(34f).toFloat())
        heroSaid.font = JBFont.label().asBold()
        heroSub.foreground = UIUtil.getInactiveTextColor()

        val words = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(heroSaid)
            add(heroSub)
            border = JBUI.Borders.emptyLeft(10)
        }

        val hero = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(heroNumber, BorderLayout.WEST)
            add(words, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(8)
        }

        paintButton.addActionListener {
            TrustWidget.toggle(project)
            updatePaintButton()
        }
        deadOnly.addActionListener { render(animate = false) }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(paintButton)
            add(JButton("Refresh").apply {
                // Goes through the service so the report, the dead marks and every open editor
                // all move together. A local reload would redraw this tab around stale numbers.
                addActionListener { TrustService.getInstance(project).refresh() }
            })
            add(deadOnly)
        }

        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(buttons, BorderLayout.WEST)
            border = JBUI.Borders.emptyBottom(10)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(hero, BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
            add(strip, BorderLayout.SOUTH)
        }
    }

    private fun buildBody(): OnePixelSplitter {
        val picture = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(10)
            add(treemap, BorderLayout.CENTER)
            add(buildLegend(), BorderLayout.SOUTH)
        }

        val below = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(listHeading.apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.emptyBottom(4)
            }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

        // Adjustable, because how much of each you want depends on whether you are showing
        // the project to somebody or working through the list yourself.
        return OnePixelSplitter(true, 0.6f).apply {
            firstComponent = picture
            secondComponent = below
        }
    }

    private fun buildLegend(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 4)).apply {
        isOpaque = false
        add(JBLabel("area = size of file").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = JBUI.Fonts.smallFont()
        })
        add(JBLabel("green = proven, red = never run").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = JBUI.Fonts.smallFont()
        })
        add(JBLabel("hatched grey = dead, nothing imports it").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = JBUI.Fonts.smallFont()
        })
    }

    /**
     * Reads the verdict off the event thread, then draws it.
     *
     * The dead code pass searches the project index once per file that never ran, which is
     * fast but not instant and absolutely not something to do on the UI thread. Everything
     * below only touches Swing after it is back on it.
     */
    private fun load(animate: Boolean) {
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed) return@execute
            val detailed = runCatching { TrustService.getInstance(project).allKnownDetailed() }
                .getOrDefault(emptyMap())
            val described = runCatching { TrustService.getInstance(project).describeSource() }.getOrNull()
            val placeholder = runCatching { TrustService.getInstance(project).isPlaceholder() }
                .getOrDefault(false)

            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                known = detailed.values.toList()
                provenance.text = when (described) {
                    null -> "No coverage report found yet. Run your tests with coverage and this fills in."
                    else -> if (placeholder) "$described  (not a real run yet)" else described
                }
                render(animate)
            }, ModalityState.any(), project.disposed)
        }
    }

    private fun render(animate: Boolean) {
        val visible = if (deadOnly.isSelected) known.filter { it.isDead } else known
        val layers = TrustLayers.of(visible)

        treemap.setData(layers, animate)
        strip.setData(layers)

        val never = visible.sumOf { it.unprovenLines }
        val total = visible.sumOf { it.totalLines }
        val percent = if (total > 0) never * 100 / total else 0
        val dead = visible.count { it.isDead }

        heroNumber.text = "$percent%"
        heroNumber.foreground = TrustColors.ramp(percent.toDouble())
        heroSaid.text = when {
            known.isEmpty() -> "No execution data for this project"
            deadOnly.isSelected -> "of the dead code has never executed"
            percent > 60 -> "of this codebase has never executed"
            percent > 25 -> "of this codebase still has never executed"
            percent > 0 -> "left, and it is mostly edge cases"
            else -> "everything known here has run at least once"
        }
        heroSub.text = buildString {
            append("%,d of %,d executable lines".format(never, total))
            append(" · ${visible.size} files")
            if (dead > 0) append(" · $dead dead")
        }

        renderList(visible)
    }

    private fun renderList(visible: List<FileTrust>) {
        val folder = selected?.folder
        val rows = visible
            .filter { folder == null || it.folder == folder }
            .filterNot { it.isClean }
            .sortedWith(compareByDescending<FileTrust> { it.isDead }.thenByDescending { it.unprovenLines })

        model.clear()
        rows.forEach(model::addElement)

        listHeading.text = when {
            folder != null -> "$folder — ${rows.size} file(s)"
            deadOnly.isSelected -> "Dead files (${rows.size})"
            else -> "All unproven files (${rows.size})"
        }
        updatePaintButton()
    }

    private fun selectLayer(layer: LayerSummary?) {
        selected = layer
        treemap.selectedLayer = layer?.folder
        strip.selectedLayer = layer?.folder
        renderList(if (deadOnly.isSelected) known.filter { it.isDead } else known)
    }

    private fun updatePaintButton() {
        paintButton.text =
            if (TrustService.getInstance(project).enabled) "Hide paint" else "Paint in editor"
    }

    /** Opens the file at the first line nothing has ever run, which is the line worth seeing. */
    private fun openFile(trust: FileTrust) {
        val base = project.basePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath("$base/${trust.path}") ?: return
        val line = trust.unproven.firstOrNull()?.start ?: 1

        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private companion object {
        val DEAD_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD,
            JBColor(java.awt.Color(0x6A6A76), java.awt.Color(0xB9, 0xB9, 0xC6)),
        )
    }
}
