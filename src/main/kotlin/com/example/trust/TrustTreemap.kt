package com.example.trust

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The whole project as one picture: a rectangle per file, grouped by folder.
 *
 * Area is how much code, colour is how much of it has never run. Both channels carry meaning,
 * which is what the list could not do: in a list a five line helper and a three hundred line
 * service occupy exactly one row each, so the eye has no way to know which one matters. Here
 * the dangerous file is simply the biggest red block, and finding it takes no reading at all.
 *
 * Painted rather than rendered in a browser on purpose. The other tabs earn their web views by
 * drawing things Swing cannot; nested rectangles are not one of those things, and a component
 * costs no browser process, no dev server and no start-up wait.
 */
class TrustTreemap : JComponent() {

    /** Double click opens the file, so the picture is a way in and not just a report. */
    var onFileActivated: ((FileTrust) -> Unit)? = null

    /** Single click filters the list below to that folder. */
    var onLayerSelected: ((LayerSummary?) -> Unit)? = null

    /**
     * Folders whose box came out too small to see, after each layout.
     *
     * Area is proportional and stays that way: a five line folder in a two thousand line
     * project is a sliver, and inflating it would make the picture lie about size. What the
     * picture cannot show, the caption says instead, so the bars and the boxes never disagree.
     */
    var onUndrawn: ((List<LayerSummary>) -> Unit)? = null

    var selectedLayer: String? = null
        set(value) {
            field = value
            repaint()
        }

    /** Folder boxes with headers, or every file in one flat field. */
    var grouped: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            laidOutFor = Dimension(0, 0)
            repaint()
        }

    private var layers: List<LayerSummary> = emptyList()
    private var groups: List<Tile<LayerSummary>> = emptyList()
    private var cells: List<Cell> = emptyList()
    private var laidOutFor = Dimension(0, 0)

    /** Animated line counts, keyed by path, so a finished run drains instead of jumping. */
    private val displayed = HashMap<String, Double>()
    private var animation: Timer? = null
    private var hovered: Cell? = null

    private class Cell(val file: FileTrust, val rect: TreemapRect, val layer: LayerSummary) {
        val bounds: Rectangle
            get() = Rectangle(
                rect.x.roundToInt(), rect.y.roundToInt(),
                max(rect.w.roundToInt(), 1), max(rect.h.roundToInt(), 1),
            )
    }

    init {
        isOpaque = true
        preferredSize = JBUI.size(400, 260)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        ToolTipManager.sharedInstance().registerComponent(this)

        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val found = cellAt(e.x, e.y)
                if (found !== hovered) {
                    hovered = found
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = null
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                val cell = cellAt(e.x, e.y) ?: return
                if (e.clickCount >= 2) {
                    onFileActivated?.invoke(cell.file)
                } else {
                    // Clicking the same folder twice clears it, so the picture is its own
                    // "back" button and no separate control is needed.
                    val next = if (selectedLayer == cell.layer.folder) null else cell.layer
                    selectedLayer = next?.folder
                    onLayerSelected?.invoke(next)
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    /**
     * Hands over a new verdict.
     *
     * [animate] is what makes a finished test run readable as an event rather than a redraw.
     * The geometry does not move, because file sizes did not change; only the colour travels,
     * which is exactly the claim being made: the same code, newly proven.
     */
    fun setData(next: List<LayerSummary>, animate: Boolean) {
        val files = next.flatMap { it.files }
        val targets = files.associate { it.path to it.unprovenLines.toDouble() }
        val sizes = files.associate { it.path to it.totalLines }

        layers = next
        laidOutFor = Dimension(0, 0)

        if (!animate || displayed.isEmpty()) {
            animation?.stop()
            displayed.clear()
            displayed.putAll(targets)
            revalidate()
            repaint()
            return
        }

        val from = targets.keys.associateWith { displayed[it] ?: targets.getValue(it) }
        // Small files first, so the colour sweeps across the picture instead of switching.
        val order = targets.keys.sortedBy { sizes[it] ?: 0 }
        val slot = order.withIndex().associate { (i, path) ->
            path to (i.toDouble() / max(order.size, 1)) * STAGGER
        }

        animation?.stop()
        val started = System.currentTimeMillis()
        animation = Timer(16) { event ->
            val elapsed = (System.currentTimeMillis() - started).toDouble() / DURATION_MS
            targets.forEach { (path, target) ->
                val local = ((elapsed - (slot[path] ?: 0.0)) / (1.0 - STAGGER)).coerceIn(0.0, 1.0)
                val eased = 1.0 - (1.0 - local).pow(3)
                displayed[path] = from.getValue(path) + (target - from.getValue(path)) * eased
            }
            repaint()
            if (elapsed >= 1.0) {
                displayed.putAll(targets)
                (event.source as Timer).stop()
                repaint()
            }
        }.apply { start() }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val cell = cellAt(event.x, event.y) ?: return null
        val file = cell.file
        val verdict = when {
            file.isDead -> "dead: nothing imports it and nothing ever ran it"
            file.changedSinceRun -> "changed since the run, so this answer is out of date"
            else -> "never run"
        }
        return "<html><b>${file.path}</b><br>" +
            "${file.unprovenLines} of ${file.totalLines} lines $verdict</html>"
    }

    private fun cellAt(x: Int, y: Int): Cell? = cells.lastOrNull { it.bounds.contains(x, y) }

    private fun relayout() {
        val bounds = TreemapRect(0.0, 0.0, width.toDouble(), height.toDouble())

        if (grouped) {
            groups = Squarify.layout(layers, { it.totalLines.toDouble() }, bounds)
            cells = groups.flatMap { group ->
                // Room for the folder name across the top of its box.
                val inner = TreemapRect(
                    group.rect.x + 1, group.rect.y + HEADER, group.rect.w - 2,
                    (group.rect.h - HEADER - 1).coerceAtLeast(1.0),
                )
                Squarify.layout(group.value.files, { it.totalLines.toDouble() }, inner)
                    .map { Cell(it.value, it.rect, group.value) }
            }
        } else {
            groups = emptyList()
            val layerOf = layers.flatMap { layer -> layer.files.map { it.path to layer } }.toMap()
            val files = layers.flatMap { it.files }
            cells = Squarify.layout(files, { it.totalLines.toDouble() }, bounds)
                .map { Cell(it.value, it.rect, layerOf.getValue(it.value.path)) }
        }
        laidOutFor = Dimension(width, height)

        val drawn = if (grouped) {
            groups.filter { it.rect.w >= MIN_VISIBLE && it.rect.h >= MIN_VISIBLE }.map { it.value.folder }.toSet()
        } else {
            cells.filter { it.rect.w >= MIN_VISIBLE && it.rect.h >= MIN_VISIBLE }.map { it.layer.folder }.toSet()
        }
        val undrawn = layers.filter { it.folder !in drawn }
        // Deferred: this runs inside paint, and a listener that revalidates a label mid-paint
        // would be asking Swing to lay out while it is drawing.
        onUndrawn?.let { SwingUtilities.invokeLater { it(undrawn) } }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            g2.color = BACKGROUND
            g2.fillRect(0, 0, width, height)

            if (layers.isEmpty()) {
                paintEmpty(g2)
                return
            }
            if (laidOutFor.width != width || laidOutFor.height != height) relayout()

            cells.forEach { paintCell(g2, it) }
            groups.forEach { paintGroupLabel(g2, it) }
        } finally {
            g2.dispose()
        }
    }

    private fun paintEmpty(g2: Graphics2D) {
        g2.color = UIUtil.getInactiveTextColor()
        g2.font = UIUtil.getLabelFont()
        val text = "No execution data yet"
        val metrics = g2.fontMetrics
        g2.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
    }

    private fun paintCell(g2: Graphics2D, cell: Cell) {
        val file = cell.file
        val box = cell.bounds
        if (box.width < 2 || box.height < 2) return

        val dimmed = selectedLayer != null && selectedLayer != cell.layer.folder
        val shown = displayed[file.path] ?: file.unprovenLines.toDouble()
        val percent = if (file.totalLines > 0) (shown * 100 / file.totalLines) else 0.0

        g2.color = when {
            file.isDead -> if (dimmed) fade(DEAD) else DEAD
            dimmed -> fade(TrustColors.ramp(percent))
            else -> TrustColors.ramp(percent)
        }
        g2.fillRect(box.x, box.y, box.width, box.height)

        if (file.isDead) {
            // Hatching, so "delete this" never depends on telling one grey from another.
            // Clipped to the cell: a diagonal line runs past both ends of its box, and left
            // unclipped a tall dead cell smears stripes over every healthy file beside it,
            // which is the picture claiming things that are not true.
            val saved = g2.clip
            g2.clipRect(box.x, box.y, box.width, box.height)
            g2.color = Color(255, 255, 255, if (dimmed) 20 else 46)
            var offset = -box.height
            while (offset < box.width) {
                g2.drawLine(box.x + offset, box.y + box.height, box.x + offset + box.height, box.y)
                offset += 7
            }
            g2.clip = saved
        }

        g2.color = if (file.isDead) DEAD_EDGE else EDGE
        g2.stroke = if (file.isDead) DASHED else SOLID
        g2.drawRect(box.x, box.y, box.width - 1, box.height - 1)
        g2.stroke = SOLID

        if (cell === hovered) {
            g2.color = JBColor.WHITE
            g2.stroke = BasicStroke(2f)
            g2.drawRect(box.x + 1, box.y + 1, box.width - 3, box.height - 3)
            g2.stroke = SOLID
        }

        if (dimmed) return
        paintCellText(g2, box, file, percent)
    }

    private fun paintCellText(g2: Graphics2D, box: Rectangle, file: FileTrust, percent: Double) {
        if (box.width < 56 || box.height < 24) return

        g2.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
        val name = file.fileName.substringBeforeLast('.')
        val metrics = g2.fontMetrics
        val label = shorten(name, metrics, box.width - 8)

        // A shadow rather than a chosen text colour: the fill runs from dark green to bright
        // red, and no single foreground stays legible across all of it.
        g2.color = SHADOW
        g2.drawString(label, box.x + 5, box.y + metrics.ascent + 4)
        g2.color = Color.WHITE
        g2.drawString(label, box.x + 4, box.y + metrics.ascent + 3)

        if (box.height >= 42) {
            // "100%" on a dead cell is true and says nothing the hatching did not; the word
            // is the information.
            val corner = if (file.isDead) "dead" else "${percent.roundToInt()}%"
            g2.color = SHADOW
            g2.drawString(corner, box.x + 5, box.y + box.height - 5)
            g2.color = Color(255, 255, 255, 220)
            g2.drawString(corner, box.x + 4, box.y + box.height - 6)
        }
    }

    private fun paintGroupLabel(g2: Graphics2D, group: Tile<LayerSummary>) {
        val rect = group.rect
        if (rect.h < HEADER || rect.w < 30) return

        val dimmed = selectedLayer != null && selectedLayer != group.value.folder
        g2.color = GROUP_EDGE
        g2.drawRect(rect.x.roundToInt(), rect.y.roundToInt(), rect.w.roundToInt() - 1, rect.h.roundToInt() - 1)

        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(10f).toFloat())
        val metrics = g2.fontMetrics
        val text = shorten(group.value.name.uppercase(), metrics, rect.w.roundToInt() - 10)
        if (text.isEmpty()) return
        val x = rect.x.roundToInt() + 5
        val y = rect.y.roundToInt() + metrics.ascent + 2

        g2.color = SHADOW
        g2.drawString(text, x + 1, y + 1)
        g2.color = if (dimmed) JBColor.GRAY else GROUP_TEXT
        g2.drawString(text, x, y)
    }

    private fun shorten(text: String, metrics: FontMetrics, available: Int): String {
        if (available <= 0) return ""
        if (metrics.stringWidth(text) <= available) return text
        var cut = text
        while (cut.isNotEmpty() && metrics.stringWidth("$cut…") > available) cut = cut.dropLast(1)
        // Two letters and an ellipsis reads as a glitch, not a name; the tooltip has it.
        return if (cut.length < 3) "" else "$cut…"
    }

    private companion object {
        const val HEADER = 15.0
        const val DURATION_MS = 1150.0
        const val STAGGER = 0.4

        /** Below this a box has no room for a name or a click, so the caption names it instead. */
        const val MIN_VISIBLE = 14.0

        val SOLID = BasicStroke(1f)
        val DASHED = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, floatArrayOf(3f, 3f), 0f)

        val BACKGROUND = JBColor(Color(0xF2F2F4), Color(0x21, 0x22, 0x26))
        val EDGE = Color(0, 0, 0, 115)
        val GROUP_EDGE = JBColor(Color(0xD8D8DC), Color(0x1A, 0x1B, 0x1E))
        val GROUP_TEXT = JBColor(Color(0x3C3F43), Color(0xCF, 0xD2, 0xD8))
        val SHADOW = Color(0, 0, 0, 170)
        val DEAD = JBColor(Color(0x9A9AA4), Color(0x4A, 0x4A, 0x52))
        val DEAD_EDGE = JBColor(Color(0x6A6A76), Color(0x8A, 0x8A, 0x96))

        /** Pushed towards the background, so a filtered folder recedes without vanishing. */
        fun fade(color: Color) = Color(
            (color.red * 0.38 + 33 * 0.62).roundToInt(),
            (color.green * 0.38 + 34 * 0.62).roundToInt(),
            (color.blue * 0.38 + 38 * 0.62).roundToInt(),
        )
    }
}
