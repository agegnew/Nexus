package com.example.trust

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One bar per folder, worst first.
 *
 * This is the part that turns the data into a sentence. The file list already contained the
 * fact that no agent and no tool had ever executed, spread thin across eleven rows where
 * nobody would ever assemble it. Rolled up to the folder it reads as one line, and one line
 * is the most anybody carries out of a demo.
 */
class TrustLayerStrip : JComponent() {

    var onLayerSelected: ((LayerSummary?) -> Unit)? = null

    var selectedLayer: String? = null
        set(value) {
            field = value
            repaint()
        }

    private var layers: List<LayerSummary> = emptyList()
    private var hovered: Int = -1

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        ToolTipManager.sharedInstance().registerComponent(this)

        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = rowAt(e.y)
                if (row != hovered) {
                    hovered = row
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = -1
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                val layer = layers.getOrNull(rowAt(e.y)) ?: return
                val next = if (selectedLayer == layer.folder) null else layer
                selectedLayer = next?.folder
                onLayerSelected?.invoke(next)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun setData(next: List<LayerSummary>) {
        // Worst first, and only folders that still have something to answer for.
        layers = next.filter { it.totalLines > 0 }.sortedByDescending { it.unprovenLines }.take(MAX_ROWS)
        preferredSize = Dimension(10, layers.size * ROW)
        revalidate()
        repaint()
    }

    private fun rowAt(y: Int): Int = (y / ROW).coerceIn(0, max(layers.size - 1, 0))

    override fun getToolTipText(event: MouseEvent): String? {
        val layer = layers.getOrNull(rowAt(event.y)) ?: return null
        return "${layer.folder}: ${layer.files.size} file(s)" +
            if (layer.deadFiles > 0) ", ${layer.deadFiles} dead" else ""
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
            val metrics = g2.fontMetrics

            val nameWidth = JBUI.scale(NAME_WIDTH)
            val numberWidth = JBUI.scale(NUMBER_WIDTH)
            val gap = JBUI.scale(8)
            val trackX = nameWidth + gap
            val trackWidth = (width - nameWidth - numberWidth - gap * 2).coerceAtLeast(10)

            layers.forEachIndexed { index, layer ->
                val top = index * ROW
                val percent = layer.percentUnproven()
                val everything = percent >= 95

                if (index == hovered || selectedLayer == layer.folder) {
                    g2.color = HOVER
                    g2.fillRoundRect(0, top, width, ROW - 2, 4, 4)
                }

                // The folders that are entirely unproven are the headline, so they are the
                // only ones allowed to be the full-strength label colour.
                g2.color = if (everything) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
                val name = layer.name
                g2.drawString(
                    name,
                    (nameWidth - metrics.stringWidth(name)).coerceAtLeast(0),
                    top + (ROW + metrics.ascent) / 2 - 2,
                )

                val barTop = top + (ROW - BAR) / 2 - 1
                g2.color = TRACK
                g2.fillRoundRect(trackX, barTop, trackWidth, BAR, 3, 3)

                val filled = (trackWidth * percent / 100.0).roundToInt().coerceAtLeast(if (percent > 0) 2 else 0)
                if (filled > 0) {
                    g2.color = TrustColors.ramp(percent.toDouble())
                    g2.fillRoundRect(trackX, barTop, filled, BAR, 3, 3)
                }

                g2.color = UIUtil.getInactiveTextColor()
                val numbers = "${layer.unprovenLines} / ${layer.totalLines}  (${percent}%)"
                g2.drawString(numbers, trackX + trackWidth + gap, top + (ROW + metrics.ascent) / 2 - 2)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val ROW = 21
        const val BAR = 13
        const val NAME_WIDTH = 86
        const val NUMBER_WIDTH = 116
        const val MAX_ROWS = 12

        val TRACK = JBColor(Color(0xE2E2E6), Color(0x33, 0x35, 0x3A))
        val HOVER = JBColor(Color(0x00000010, true), Color(0x18FFFFFF, true))
    }
}
