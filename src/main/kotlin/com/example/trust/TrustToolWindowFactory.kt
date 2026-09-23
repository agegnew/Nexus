package com.example.trust

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
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
import javax.swing.SwingConstants

/**
 * The Trust tab: the whole project's unproven code as one picture, then as a list to act on.
 *
 * The wash in the editor answers "can I trust the line I am looking at". This answers the
 * question you ask before opening anything: "where in this project am I building on code that
 * has never run". The treemap answers it without reading, the folder bars turn it into a
 * sentence, and the list is what you click to get your cursor onto the problem.
 *
 * One button. Everything on this tab comes from a coverage report, and the button is how
 * you get one. The first version had four buttons whose labels changed as you pressed them,
 * and the people it was shown to could not say what any of them did. Now the only thing
 * that looks like a button does the one thing there is to do, and the two switches are
 * checkboxes, whose state can be read without pressing them.
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

    private val runButton = JButton("Run with coverage", AllIcons.Actions.Execute)
    private val paintBox = JBCheckBox("Paint in editor")
    private val deadBox = JBCheckBox("Only dead code")

    /** Says which folder the list is narrowed to, with a way out. Hidden when it is not. */
    private val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
    private val filterText = JBLabel()

    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val emptyMessage = JBLabel()

    /** Everything known, dead marks included once the background pass has finished. */
    private var known: List<FileTrust> = emptyList()
    private var selected: LayerSummary? = null
    private var running = false

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
        // than making the user press anything to find out something already happened.
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

        runButton.addActionListener { runCoverage() }
        paintBox.addActionListener {
            // The checkbox is the truth; the service follows it.
            if (paintBox.isSelected != TrustService.getInstance(project).enabled) TrustWidget.toggle(project)
        }
        deadBox.addActionListener { render(animate = false) }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            add(runButton)
            add(paintBox)
            add(deadBox)
        }

        filterText.foreground = UIUtil.getLabelForeground()
        filterBar.add(filterText)
        filterBar.add(ActionLink("show all folders") { selectLayer(null) })
        filterBar.isVisible = false

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(controls)
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

    private fun buildBody(): JPanel {
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
        val data = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = picture
            secondComponent = below
        }

        // Shown instead of an empty picture, because an empty picture explains nothing and a
        // sentence with the next step in it does.
        emptyMessage.horizontalAlignment = SwingConstants.CENTER
        emptyMessage.foreground = UIUtil.getInactiveTextColor()

        body.isOpaque = false
        body.add(data, CARD_DATA)
        body.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(emptyMessage, BorderLayout.CENTER)
        }, CARD_EMPTY)
        return body
    }

    private fun buildLegend(): JPanel {
        fun caption(text: String) = JBLabel(text).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = JBUI.Fonts.smallFont()
        }

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4)).apply {
            isOpaque = false
            add(caption("one box per file, area = lines of code"))
            add(caption("ran"))
            add(RampSwatch())
            add(caption("never ran"))
            add(caption("hatched = dead: nothing imports it, nothing ran it"))
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
            val command = runCatching { TrustRunner.commandFor(project) }.getOrNull()

            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                known = detailed.values.toList()
                provenance.text = when (described) {
                    null -> "No coverage report found in this project yet."
                    else -> if (placeholder) "$described  (not a real run yet)" else described
                }
                describeRunButton(command)
                render(animate)
            }, ModalityState.any(), project.disposed)
        }
    }

    private fun describeRunButton(command: CoverageCommand?) {
        if (running) return
        runButton.isEnabled = true
        if (command == null) {
            runButton.text = "Set up coverage…"
            runButton.toolTipText = "No coverage command is known for this project. Click to see how to set one."
        } else {
            runButton.text = "Run with coverage"
            runButton.toolTipText = "<html>Runs in the Run tool window, then redraws this tab:<br>" +
                "<code>${command.command}</code><br><i>${command.origin}</i></html>"
        }
        emptyMessage.text = if (command == null) {
            "<html><div style='text-align:center'><b>Nothing measured yet.</b><br><br>" +
                "This tab paints the code that has never been executed.<br>" +
                "It needs one run of the project with coverage on, and it does not know the command for this project.<br>" +
                "Press <b>Set up coverage…</b> above to see how to tell it.</div></html>"
        } else {
            "<html><div style='text-align:center'><b>Nothing measured yet.</b><br><br>" +
                "This tab paints the code that has never been executed.<br>" +
                "Press <b>Run with coverage</b> above. It runs<br><code>${command.command}</code><br>" +
                "in the Run tool window, and this fills in when it finishes.</div></html>"
        }
    }

    private fun runCoverage() {
        val command = TrustRunner.commandFor(project)
        if (command == null) {
            Messages.showInfoMessage(
                project,
                "Nexus could not guess how to run this project with coverage.\n\n" +
                    "Tell it, in ${FixtureExecutionSource.FIXTURE_PATH} at the project root:\n\n" +
                    "{ \"command\": \"python -m coverage run -m pytest && python -m coverage xml\" }\n\n" +
                    "Any command that writes coverage.xml (Cobertura) or lcov.info will do. " +
                    "The tab also redraws on its own whenever that file changes, so running it in a terminal works too.",
                "Set Up Coverage",
            )
            return
        }
        running = true
        runButton.isEnabled = false
        runButton.text = "Running…"
        TrustRunner.run(project, command) {
            running = false
            describeRunButton(command)
        }
    }

    private fun render(animate: Boolean) {
        paintBox.isSelected = TrustService.getInstance(project).enabled

        if (known.isEmpty()) {
            heroNumber.text = "-"
            heroNumber.foreground = UIUtil.getInactiveTextColor()
            heroSaid.text = "No execution data for this project"
            heroSub.text = "Nothing here has been measured yet"
            strip.setData(emptyList())
            cards.show(body, CARD_EMPTY)
            return
        }
        cards.show(body, CARD_DATA)

        val deadOnly = deadBox.isSelected
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

        if (deadOnly) {
            // Dead code is never executed by definition, so a percentage here would be 100%
            // every time and say nothing. The size of the pile is the information.
            heroNumber.text = "%,d".format(never)
            heroNumber.foreground = DEAD_TEXT
            heroSaid.text = if (never == 1) "line of dead code" else "lines of dead code"
            heroSub.text = "$dead ${plural(dead, "file")} · nothing imports them and nothing has ever run them"
        } else {
            heroNumber.text = "$percent%"
            heroNumber.foreground = TrustColors.ramp(percent.toDouble())
            heroSaid.text = when {
                percent > 60 -> "of this codebase has never executed"
                percent > 25 -> "of this codebase still has never executed"
                percent > 0 -> "left, and it is mostly edge cases"
                else -> "everything known here has run at least once"
            }
            // One sentence that ties the three counts together, because "40 files" up here
            // and "34 files" in the list with nothing between them read as a bug.
            heroSub.text = buildString {
                append("%,d of %,d lines never run".format(never, total))
                append(" · $unproven of ${known.size} files affected")
                if (dead > 0) append(" · $dead dead")
            }
        }

        renderList()
    }

    private fun renderList() {
        val deadOnly = deadBox.isSelected
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

        filterText.text = if (folder == null) "" else "Narrowed to $folder."
        filterBar.isVisible = folder != null
        filterBar.revalidate()
    }

    private fun selectLayer(layer: LayerSummary?) {
        selected = layer
        treemap.selectedLayer = layer?.folder
        strip.selectedLayer = layer?.folder
        renderList()
    }

    private fun showUndrawn(layers: List<LayerSummary>) {
        undrawn.isVisible = layers.isNotEmpty()
        if (layers.isEmpty()) return
        val names = layers.joinToString(", ") { "${it.name} (${it.totalLines} ${plural(it.totalLines, "line")})" }
        undrawn.text = "Not drawn, too small: $names"
        undrawn.revalidate()
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
        const val CARD_DATA = "data"
        const val CARD_EMPTY = "empty"

        val DEAD_TAG = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD,
            JBColor(java.awt.Color(0x6A6A76), java.awt.Color(0xB9, 0xB9, 0xC6)),
        )
        val DEAD_TEXT = JBColor(java.awt.Color(0x6A6A76), java.awt.Color(0xB9, 0xB9, 0xC6))
    }
}
