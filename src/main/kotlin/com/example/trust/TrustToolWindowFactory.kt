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
import com.intellij.ui.components.ActionLink
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
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

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
    private val undrawn = JBLabel()

    private val paintButton = JButton()
    private val deadButton = JButton()
    private val groupButton = JButton()

    /**
     * The active filters, in words, with a way out. Shown only while something is filtered.
     *
     * A toggle button's pressed state is a few shades of grey apart from its resting state
     * and nobody can read that across a room. A sentence saying "showing dead code only" can
     * be read from anywhere, and it also tells you which of three things you are looking at
     * when the picture, the bars and the list are all narrowed differently.
     */
    private val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
    private val filterText = JBLabel()

    /** Everything known, dead marks included once the background pass has finished. */
    private var known: List<FileTrust> = emptyList()
    private var selected: LayerSummary? = null
    private var deadOnly = false

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
        treemap.onUndrawn = ::showUndrawn
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
            updateButtons()
        }
        deadButton.addActionListener {
            deadOnly = !deadOnly
            render(animate = false)
        }
        groupButton.addActionListener {
            treemap.grouped = !treemap.grouped
            updateButtons()
        }

        // Every button names the thing pressing it will do, never the state it is in. The
        // state is written out in the filter line where it can actually be read.
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(paintButton)
            add(JButton("Refresh").apply {
                // Goes through the service so the report, the dead marks and every open editor
                // all move together. A local reload would redraw this tab around stale numbers.
                addActionListener { TrustService.getInstance(project).refresh() }
            })
            add(deadButton)
            add(groupButton)
        }

        filterText.foreground = UIUtil.getLabelForeground()
        filterBar.add(filterText)
        filterBar.add(ActionLink("show everything") { clearFilters() })
        filterBar.isVisible = false

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(buttons)
            add(filterBar.apply { border = JBUI.Borders.emptyTop(6) })
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

    private fun buildLegend(): JPanel {
        fun caption(text: String) = JBLabel(text).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = JBUI.Fonts.smallFont()
        }

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4)).apply {
            isOpaque = false
            add(caption("area = size of file"))
            add(caption("proven"))
            add(RampSwatch())
            add(caption("never run"))
            add(caption("hatched = dead, nothing imports it"))
        }

        undrawn.foreground = UIUtil.getInactiveTextColor()
        undrawn.font = JBUI.Fonts.smallFont()
        undrawn.isVisible = false

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(row)
            add(undrawn.apply { border = JBUI.Borders.emptyLeft(10) })
        }
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
            val service = TrustService.getInstance(project)
            val detailed = runCatching { service.allKnownDetailed() }.getOrDefault(emptyMap())
            val described = runCatching { service.describeSource() }.getOrNull()
            val placeholder = runCatching { service.isPlaceholder() }.getOrDefault(false)

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
        val visible = if (deadOnly) known.filter { it.isDead } else known
        // Named against everything known, so a folder is called the same thing in every mode.
        val layers = TrustLayers.of(visible, namingBasis = known)

        treemap.setData(layers, animate)
        strip.setData(layers)

        val never = visible.sumOf { it.unprovenLines }
        val total = visible.sumOf { it.totalLines }
        val percent = if (total > 0) never * 100 / total else 0
        val dead = known.count { it.isDead }
        val unproven = known.count { !it.isClean }

        when {
            known.isEmpty() -> {
                heroNumber.text = "—"
                heroNumber.foreground = UIUtil.getInactiveTextColor()
                heroSaid.text = "No execution data for this project"
                heroSub.text = "Run the tests with coverage once and this fills in"
            }

            deadOnly -> {
                // Dead code is never executed by definition, so a percentage here would be
                // 100% every time and say nothing. The size of the pile is the information.
                heroNumber.text = "%,d".format(never)
                heroNumber.foreground = DEAD_TEXT
                heroSaid.text = if (never == 1) "line of dead code" else "lines of dead code"
                heroSub.text = "$dead ${plural(dead, "file")} · nothing imports them and nothing has ever run them"
            }

            else -> {
                heroNumber.text = "$percent%"
                heroNumber.foreground = TrustColors.ramp(percent.toDouble())
                heroSaid.text = when {
                    percent > 60 -> "of this codebase has never executed"
                    percent > 25 -> "of this codebase still has never executed"
                    percent > 0 -> "left, and it is mostly edge cases"
                    else -> "everything known here has run at least once"
                }
                // One sentence that ties the three counts together, because "48 files" up
                // here and "34 files" in the list with nothing between them read as a bug.
                heroSub.text = buildString {
                    append("%,d of %,d lines never run".format(never, total))
                    append(" · $unproven of ${known.size} files affected")
                    if (dead > 0) append(" · $dead dead")
                }
            }
        }

        renderList()
        updateButtons()
    }

    private fun renderList() {
        val base = if (deadOnly) known.filter { it.isDead } else known
        val folder = selected?.folder
        val rows = base
            .filter { folder == null || it.folder == folder }
            .filterNot { it.isClean }
            .sortedWith(compareByDescending<FileTrust> { it.isDead }.thenByDescending { it.unprovenLines })

        model.clear()
        rows.forEach(model::addElement)

        listHeading.text = when {
            deadOnly && folder != null -> "Dead files in $folder (${rows.size})"
            deadOnly -> "Dead files (${rows.size})"
            folder != null -> "Files with never-run code in $folder (${rows.size})"
            else -> "Files with never-run code (${rows.size})"
        }

        val parts = buildList {
            if (deadOnly) add("dead code only")
            if (folder != null) add("list narrowed to $folder")
        }
        filterText.text = if (parts.isEmpty()) "" else "Showing ${parts.joinToString(", ")}."
        filterBar.isVisible = parts.isNotEmpty()
        filterBar.revalidate()
    }

    private fun selectLayer(layer: LayerSummary?) {
        selected = layer
        treemap.selectedLayer = layer?.folder
        strip.selectedLayer = layer?.folder
        renderList()
    }

    private fun clearFilters() {
        deadOnly = false
        selectLayer(null)
        render(animate = false)
    }

    private fun showUndrawn(layers: List<LayerSummary>) {
        undrawn.isVisible = layers.isNotEmpty()
        if (layers.isEmpty()) return
        val names = layers.joinToString(", ") { "${it.name} (${it.totalLines} ${plural(it.totalLines, "line")})" }
        undrawn.text = "Too small to draw to scale: $names. The bars above still count them."
        undrawn.revalidate()
    }

    private fun updateButtons() {
        paintButton.text = if (TrustService.getInstance(project).enabled) "Hide paint" else "Paint in editor"
        deadButton.text = if (deadOnly) "Show everything" else "Dead code only"
        groupButton.text = if (treemap.grouped) "Flat view" else "Group by folder"
    }

    /** Opens the file at the first line nothing has ever run, which is the line worth seeing. */
    private fun openFile(trust: FileTrust) {
        val base = project.basePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath("$base/${trust.path}") ?: return
        val line = trust.unproven.firstOrNull()?.start ?: 1

        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"

    /** The colour scale as a strip, so the legend shows the thing rather than describing it. */
    private class RampSwatch : JComponent() {
        init {
            preferredSize = JBUI.size(72, 9)
        }

        override fun paintComponent(g: Graphics) {
            for (x in 0 until width) {
                g.color = TrustColors.ramp(x * 100.0 / width)
                g.fillRect(x, 0, 1, height)
            }
        }
    }

    private companion object {
        val DEAD_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD,
            JBColor(java.awt.Color(0x6A6A76), java.awt.Color(0xB9, 0xB9, 0xC6)),
        )
        val DEAD_TEXT = JBColor(java.awt.Color(0x6A6A76), java.awt.Color(0xB9, 0xB9, 0xC6))
    }
}
