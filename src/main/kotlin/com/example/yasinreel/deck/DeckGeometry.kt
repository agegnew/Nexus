package com.example.yasinreel.deck

import com.example.yasinreel.deck.DeckTheme.CONTENT_W
import com.example.yasinreel.deck.DeckTheme.H
import com.example.yasinreel.deck.DeckTheme.MARGIN
import com.example.yasinreel.deck.DeckTheme.W
import com.google.gson.JsonObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a [Deck] into positioned [Shape]s. The only place in the deck that decides
 * where anything goes.
 *
 * Every layout fills the frame edge to edge, for the reason the film learned the hard
 * way: a slide whose content sits in the middle third reads as a template that was
 * handed some text, and one composed for its frame reads as something that was made.
 */
object DeckGeometry {

    /** The band under the header that a content layout may use. */
    private const val BODY_Y = 184
    private const val BODY_H = 452
    private const val BODY_B = BODY_Y + BODY_H

    fun render(deck: Deck): List<SlideArt> {
        val total = deck.slides.size
        return deck.slides.mapIndexed { index, slide ->
            val shapes = mutableListOf<Shape>()
            val taken = mutableSetOf<String>()
            val slots = slide.slots
            val accent = index

            val title = when (slide.layout) {
                SlideLayout.TITLE -> titleSlide(shapes, deck, slots, taken)
                SlideLayout.AGENDA -> agenda(shapes, slots, accent, taken)
                SlideLayout.PROBLEM -> problem(shapes, slots, accent, taken)
                SlideLayout.STATEMENT -> statement(shapes, slots, accent, taken)
                SlideLayout.CAPABILITY_GRID -> capabilities(shapes, slots, accent, taken)
                SlideLayout.ARCH_LAYERS -> archLayers(shapes, slots, accent, taken)
                SlideLayout.FLOW -> flow(shapes, slots, accent, taken)
                SlideLayout.STATS -> stats(shapes, slots, accent, taken)
                SlideLayout.STACK -> stack(shapes, slots, accent, taken)
                SlideLayout.JOURNEY -> journey(shapes, slots, accent, taken)
                SlideLayout.PRODUCT_UI -> productUi(shapes, slots, accent)
                SlideLayout.BEFORE_AFTER -> beforeAfter(shapes, slots, accent)
                SlideLayout.GAPS -> gaps(shapes, slots, accent, taken)
                SlideLayout.CLOSING -> closing(shapes, deck, slots, taken)
                // An unknown layout must never produce a blank slide, so it degrades to
                // the one that can carry any text at all.
                else -> statement(shapes, slots, accent, taken)
            }

            if (slide.layout != SlideLayout.TITLE && slide.layout != SlideLayout.CLOSING) {
                footer(shapes, deck.projectName, index + 1, total)
            }
            SlideArt(shapes, slide.notes.orEmpty(), title)
        }
    }

    // ------------------------------------------------------------------ chrome

    /**
     * The bar every content slide opens with: a small accent label, a heading and a rule
     * running out to the right margin. It costs one line and it is what stops the top of
     * the frame being empty whatever the layout underneath does.
     *
     * @return the heading, which becomes the slide's name in the deck outline
     */
    private fun header(out: MutableList<Shape>, eyebrow: String?, heading: String?, accent: Int): String {
        val colour = DeckTheme.accent(accent)
        val label = eyebrow?.let { DeckTheme.clip(it.uppercase(), Caps.EYEBROW) }
        if (label != null) {
            out += Label(MARGIN, 54, CONTENT_W, 22, listOf(label), 15, colour, bold = true, spacing = 280)
        }
        val text = heading?.let { DeckTheme.clip(it, Caps.HEADING) }.orEmpty()
        if (text.isNotEmpty()) {
            val size = DeckTheme.fit(text, CONTENT_W, 58, listOf(40, 35, 30, 26), bold = true, linePct = 112)
            out += Label(MARGIN, 80, CONTENT_W, 58, listOf(text), size, DeckTheme.INK, bold = true, linePct = 112)
        }
        out += Box(MARGIN, 150, CONTENT_W, 3, colour)
        return text.ifEmpty { label.orEmpty() }
    }

    private fun footer(out: MutableList<Shape>, project: String, page: Int, total: Int) {
        out += Label(MARGIN, 662, CONTENT_W / 2, 18, listOf(DeckTheme.clip(project, 40)), 13, DeckTheme.MUTE)
        out += Label(
            MARGIN + CONTENT_W / 2, 662, CONTENT_W / 2, 18, listOf("$page / $total"), 13,
            DeckTheme.MUTE, align = Align.RIGHT
        )
    }

    /** A tinted disc with an icon centred on it, the deck's echo of the film's chip. */
    private fun icon(out: MutableList<Shape>, x: Int, y: Int, size: Int, name: String, tint: String) {
        out += Box(x, y, size, size, tint, roundPct = 28)
        val inset = (size * 0.17).toInt()
        out += Pic(x + inset, y + inset, size - inset * 2, size - inset * 2, name)
    }

    /** A pale wash of an accent, used behind an icon so the disc is not a solid slab. */
    private fun wash(hex: String): String {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        fun mix(c: Int) = (c * 0.14 + 255 * 0.86).toInt().coerceIn(0, 255)
        return "%02X%02X%02X".format(mix(r), mix(g), mix(b))
    }

    // ------------------------------------------------------------------ layouts

    private fun titleSlide(out: MutableList<Shape>, deck: Deck, slots: JsonObject, taken: MutableSet<String>): String {
        val name = slots.str("productName") ?: deck.title
        val tagline = slots.str("tagline") ?: deck.subtitle
        out += Box(0, 0, 14, H, DeckTheme.accent(0))

        val pick = DeckIcons.pick(name, tagline, 0, taken)
        icon(out, MARGIN, 160, 96, pick, wash(DeckTheme.accent(0)))

        val project = slots.str("project") ?: deck.projectName
        out += Label(
            MARGIN, 300, CONTENT_W, 22, listOf(DeckTheme.clip(project.uppercase(), Caps.EYEBROW)),
            15, DeckTheme.accent(0), bold = true, spacing = 280
        )

        val clipped = DeckTheme.clip(name, Caps.DECK_TITLE)
        val size = DeckTheme.fit(clipped, CONTENT_W, 148, listOf(78, 66, 56, 46, 38), bold = true, linePct = 106)
        out += Label(MARGIN, 328, CONTENT_W, 148, listOf(clipped), size, DeckTheme.INK, bold = true, linePct = 106)

        out += Box(MARGIN, 494, 240, 5, DeckTheme.accent(1))

        val sub = DeckTheme.clip(tagline, Caps.DECK_SUBTITLE)
        if (sub.isNotEmpty()) {
            val subSize = DeckTheme.fit(sub, 940, 92, listOf(26, 23, 20, 18), linePct = 132)
            out += Label(MARGIN, 522, 940, 92, listOf(sub), subSize, DeckTheme.DIM, linePct = 132)
        }
        out += Label(
            MARGIN, 648, CONTENT_W, 20, listOf(slots.str("stamp") ?: ""), 14, DeckTheme.MUTE
        )
        return clipped
    }

    private fun agenda(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "Agenda", slots.str("heading") ?: "What this covers", accent)
        val items = slots.list("items").take(Caps.MAX_AGENDA)
        if (items.isEmpty()) return title
        val gap = 12
        val rowH = min(84, (BODY_H - gap * (items.size - 1)) / items.size)
        var y = BODY_Y + max(0, (BODY_H - (rowH * items.size + gap * (items.size - 1))) / 2)
        items.forEachIndexed { i, item ->
            val label = DeckTheme.clip(item.str("label").orEmpty(), Caps.AGENDA_ITEM)
            val colour = DeckTheme.accent(i)
            val size = min(56, rowH - 14)
            icon(out, MARGIN, y + (rowH - size) / 2, size, item.str("icon") ?: DeckIcons.pick(label, null, i, taken), wash(colour))
            out += Label(
                MARGIN + size + 26, y, CONTENT_W - size - 90, rowH,
                listOf(label), DeckTheme.fit(label, CONTENT_W - size - 90, rowH, listOf(26, 23, 20), bold = true),
                DeckTheme.INK, bold = true, anchor = Anchor.MIDDLE
            )
            out += Label(
                MARGIN + CONTENT_W - 60, y, 60, rowH, listOf("%02d".format(i + 1)), 18,
                colour, bold = true, align = Align.RIGHT, anchor = Anchor.MIDDLE
            )
            y += rowH + gap
        }
        return title
    }

    private fun problem(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "The problem", slots.str("heading"), accent)
        val body = DeckTheme.clip(slots.str("body").orEmpty(), Caps.BODY)
        val textW = 800
        if (body.isNotEmpty()) {
            val size = DeckTheme.fit(body, textW, 250, listOf(38, 33, 28, 24, 21), bold = true, linePct = 126)
            out += Label(MARGIN, BODY_Y + 14, textW, 250, listOf(body), size, DeckTheme.INK, bold = true, linePct = 126)
        }
        val context = DeckTheme.clip(slots.str("context").orEmpty(), Caps.BODY)
        if (context.isNotEmpty()) {
            out += Box(MARGIN, BODY_B - 156, 5, 140, DeckTheme.accent(accent + 1))
            val size = DeckTheme.fit(context, textW - 30, 140, listOf(22, 20, 18, 16), linePct = 138)
            out += Label(MARGIN + 28, BODY_B - 156, textW - 30, 140, listOf(context), size, DeckTheme.DIM, linePct = 138)
        }
        icon(out, W - MARGIN - 200, BODY_Y + 60, 200, DeckIcons.pick(title, body, accent, taken), wash(DeckTheme.accent(accent)))
        return title
    }

    private fun statement(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val text = DeckTheme.clip(
            slots.str("statement") ?: slots.str("body") ?: slots.str("heading").orEmpty(), Caps.STATEMENT
        )
        val colour = DeckTheme.accent(accent)
        out += Box(MARGIN, 140, 7, 420, colour)
        val textW = CONTENT_W - 60 - 200
        val size = DeckTheme.fit(text, textW, 420, listOf(56, 48, 41, 35, 30, 26), bold = true, linePct = 118)
        out += Label(
            MARGIN + 44, 140, textW, 420, listOf(text), size, DeckTheme.INK,
            bold = true, anchor = Anchor.MIDDLE, linePct = 118
        )
        icon(out, W - MARGIN - 168, 268, 168, DeckIcons.pick(text, null, accent, taken), wash(colour))
        val attribution = slots.str("attribution") ?: slots.str("context")
        if (attribution != null) {
            out += Label(
                MARGIN + 44, 586, CONTENT_W - 44, 34, listOf(DeckTheme.clip(attribution, Caps.BULLET)),
                19, DeckTheme.MUTE
            )
        }
        return DeckTheme.clip(text, 48)
    }

    private fun capabilities(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "What it does", slots.str("heading"), accent)
        val cards = slots.list("cards").take(Caps.MAX_CARDS)
        if (cards.isEmpty()) return title
        val cols = if (cards.size <= 2) max(cards.size, 1) else if (cards.size <= 4) 2 else 3
        val rows = ceil(cards.size / cols.toDouble()).toInt()
        val gap = 20
        val cw = (CONTENT_W - gap * (cols - 1)) / cols
        val ch = (BODY_H - gap * (rows - 1)) / rows

        cards.forEachIndexed { i, card ->
            val cx = MARGIN + (i % cols) * (cw + gap)
            val cy = BODY_Y + (i / cols) * (ch + gap)
            val colour = DeckTheme.accent(i)
            out += Box(cx, cy, cw, ch, DeckTheme.WASH, roundPct = 4)
            out += Box(cx, cy, cw, 5, colour)

            val body = DeckTheme.clip(card.str("body").orEmpty(), Caps.CARD_BODY)
            /*
             * A card with nothing under its title is all title, so it gets the room and
             * the length that implies. The progress deck's "what was done" grid is made
             * of commit subjects, which are whole sentences: cut to the length a card
             * with a body below it can take, half of them ended on an ellipsis.
             */
            val alone = body.isEmpty()
            val name = DeckTheme.clip(
                card.str("title").orEmpty(),
                if (alone) Caps.CARD_TITLE_ALONE else Caps.CARD_TITLE
            )
            val pad = 24
            val iconSize = if (ch >= 210) 54 else 42
            icon(out, cx + pad, cy + pad + 8, iconSize, card.str("icon") ?: DeckIcons.pick(name, body, i, taken), wash(colour))

            val innerW = cw - pad * 2
            val titleY = cy + pad + iconSize + 20
            val titleBox = if (alone) max(cy + ch - pad - titleY, 1) else 74
            val titleSize = DeckTheme.fit(
                name, innerW, titleBox,
                if (alone) listOf(24, 21, 19, 17, 15) else listOf(26, 23, 20, 18),
                bold = true, linePct = 116
            )
            // Measured, not assumed. A fixed box here took the whole card and the body
            // below it was dropped for want of twenty pixels on every two row grid.
            // Clamped, because the smallest size on offer may still not be enough, and a
            // box that outgrows its card is a box that draws over the one beneath it.
            val titleH = min(DeckTheme.linesNeeded(name, innerW, titleSize, true) * titleSize * 116 / 100, titleBox)
            out += Label(
                cx + pad, titleY, innerW,
                // A title with a body under it sits directly above it. A title on its own
                // owns the rest of the card, and sitting it at the top leaves a hole under
                // every short one, so it is centred in the space it actually has.
                if (alone) titleBox else titleH,
                listOf(name), titleSize, DeckTheme.INK,
                bold = true, linePct = 116,
                anchor = if (alone) Anchor.MIDDLE else Anchor.TOP
            )

            if (!alone) {
                val bodyY = titleY + titleH + 14
                val bodyH = cy + ch - pad - bodyY
                if (bodyH >= 26) {
                    val size = DeckTheme.fit(body, innerW, bodyH, listOf(19, 17, 15, 14), linePct = 132)
                    out += Label(cx + pad, bodyY, innerW, bodyH, listOf(body), size, DeckTheme.DIM, linePct = 132)
                }
            }
        }
        return title
    }

    private fun archLayers(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "Architecture", slots.str("heading"), accent)
        val layers = slots.list("layers").take(Caps.MAX_LAYERS)
        if (layers.isEmpty()) return title
        val gap = 12
        val bandH = (BODY_H - gap * (layers.size - 1)) / layers.size

        layers.forEachIndexed { i, layer ->
            val y = BODY_Y + i * (bandH + gap)
            val colour = DeckTheme.accent(i)
            out += Box(MARGIN, y, CONTENT_W, bandH, DeckTheme.WASH, roundPct = 3)
            out += Box(MARGIN, y, 5, bandH, colour)

            val name = DeckTheme.clip(layer.str("name").orEmpty(), Caps.LAYER_NAME)
            val components = layer.strings("components").take(Caps.MAX_COMPONENTS)
            val iconSize = min(44, bandH - 26)
            icon(out, MARGIN + 22, y + (bandH - iconSize) / 2, iconSize, DeckIcons.pick(name, components.joinToString(" "), i, taken), wash(colour))

            val nameX = MARGIN + 22 + iconSize + 18
            val nameW = 210
            val nameSize = DeckTheme.fit(name, nameW, bandH - 16, listOf(23, 20, 18, 16), bold = true, linePct = 116)
            out += Label(nameX, y, nameW, bandH, listOf(name), nameSize, DeckTheme.INK, bold = true, anchor = Anchor.MIDDLE, linePct = 116)

            // Component pills, wrapped into as many rows as the band can hold.
            val pillsX = nameX + nameW + 24
            val pillsW = MARGIN + CONTENT_W - 22 - pillsX
            val pillH = 30
            val pillSize = 14
            val rowsAvailable = max(1, (bandH - 16) / (pillH + 8))
            var px = pillsX
            var row = 0
            val rowsUsed = mutableListOf<MutableList<Pair<Int, String>>>(mutableListOf())
            for (component in components) {
                val text = DeckTheme.clip(component, Caps.COMPONENT)
                val pw = (DeckTheme.widthOf(text, pillSize) + 28).toInt()
                if (px + pw > pillsX + pillsW && rowsUsed.last().isNotEmpty()) {
                    if (row + 1 >= rowsAvailable) break
                    row++
                    rowsUsed.add(mutableListOf())
                    px = pillsX
                }
                rowsUsed[row].add(pw to text)
                px += pw + 8
            }
            val stackH = rowsUsed.size * pillH + (rowsUsed.size - 1) * 8
            var py = y + (bandH - stackH) / 2
            for (line in rowsUsed) {
                var x = pillsX
                for ((pw, text) in line) {
                    out += Box(x, py, pw, pillH, DeckTheme.PAPER, roundPct = 48, stroke = DeckTheme.LINE)
                    out += Label(x, py, pw, pillH, listOf(text), pillSize, DeckTheme.DIM, align = Align.CENTER, anchor = Anchor.MIDDLE)
                    x += pw + 8
                }
                py += pillH + 8
            }
        }
        return title
    }

    private fun flow(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "How it works", slots.str("heading") ?: slots.str("name"), accent)
        val steps = slots.list("steps").take(Caps.MAX_STEPS)
        if (steps.isEmpty()) return title
        val colW = CONTENT_W / steps.size
        val iconSize = if (steps.size >= 5) 64 else 76
        val labelBox = 76
        val detailBox = 96
        val textW = colW - 22

        /*
         * Measured before anything is placed, because centring against the boxes this
         * could have needed rather than the ones it does need leaves the block floating
         * in the top half of the slide. Two passes, and the first one draws nothing.
         */
        val sized = steps.map { step ->
            val label = DeckTheme.clip(step.str("label").orEmpty(), Caps.STEP_LABEL)
            val detail = DeckTheme.clip(step.str("detail").orEmpty(), Caps.STEP_DETAIL)
            val labelSize = DeckTheme.fit(label, textW, labelBox, listOf(21, 19, 17, 15), bold = true, linePct = 120)
            val detailSize = DeckTheme.fit(detail, textW, detailBox, listOf(16, 15, 14, 13), linePct = 132)
            Quad(
                label, detail, labelSize to detailSize,
                DeckTheme.linesNeeded(label, textW, labelSize, true) * labelSize * 120 / 100 to
                    if (detail.isEmpty()) 0 else DeckTheme.linesNeeded(detail, textW, detailSize) * detailSize * 132 / 100
            )
        }
        val labelH = sized.maxOf { it.heights.first }
        val detailH = sized.maxOf { it.heights.second }
        val blockH = iconSize + 54 + labelH + (if (detailH > 0) 10 + detailH else 0)
        val iconY = BODY_Y + max(0, (BODY_H - blockH) / 2)

        // The rail first, so every node sits on top of it.
        if (steps.size > 1) {
            val first = MARGIN + colW / 2
            val last = MARGIN + colW * (steps.size - 1) + colW / 2
            out += Box(first, iconY + iconSize / 2 - 2, last - first, 3, DeckTheme.LINE)
        }

        steps.forEachIndexed { i, step ->
            val cx = MARGIN + i * colW + colW / 2
            val colour = DeckTheme.accent(i)
            val measured = sized[i]
            val label = measured.label
            val detail = measured.detail

            // An opaque plate under the disc, so the rail does not show through it.
            out += Box(cx - iconSize / 2 - 8, iconY - 8, iconSize + 16, iconSize + 16, DeckTheme.PAPER, roundPct = 50)
            icon(out, cx - iconSize / 2, iconY, iconSize, step.str("icon") ?: DeckIcons.pick(label, detail, i, taken), wash(colour))
            out += Box(cx - 13, iconY + iconSize + 16, 26, 26, colour, roundPct = 50)
            out += Label(cx - 13, iconY + iconSize + 16, 26, 26, listOf("${i + 1}"), 13, DeckTheme.PAPER, bold = true, align = Align.CENTER, anchor = Anchor.MIDDLE)

            // One shared baseline across the row, so four steps read as one rank rather
            // than as four columns that each start wherever their own label ended.
            val labelY = iconY + iconSize + 54
            out += Label(cx - textW / 2, labelY, textW, labelH, listOf(label), measured.sizes.first, DeckTheme.INK, bold = true, align = Align.CENTER, linePct = 120)
            if (detail.isNotEmpty()) {
                out += Label(cx - textW / 2, labelY + labelH + 10, textW, detailH, listOf(detail), measured.sizes.second, DeckTheme.DIM, align = Align.CENTER, linePct = 132)
            }
        }
        return title
    }

    /** What the flow's measuring pass carries over to its drawing pass. */
    private data class Quad(
        val label: String,
        val detail: String,
        val sizes: Pair<Int, Int>,
        val heights: Pair<Int, Int>
    )

    private fun stats(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "By the numbers", slots.str("heading"), accent)
        val items = slots.list("stats").take(Caps.MAX_STATS)
        if (items.isEmpty()) return title
        val gap = 20
        val cw = (CONTENT_W - gap * (items.size - 1)) / items.size
        val ch = min(330, BODY_H)
        val cy = BODY_Y + (BODY_H - ch) / 2

        items.forEachIndexed { i, stat ->
            val cx = MARGIN + i * (cw + gap)
            val colour = DeckTheme.accent(i)
            val value = DeckTheme.clip(stat.str("value").orEmpty(), Caps.STAT_VALUE)
            val label = DeckTheme.clip(stat.str("label").orEmpty(), Caps.STAT_LABEL)

            out += Box(cx, cy, cw, ch, DeckTheme.WASH, roundPct = 4)
            out += Box(cx, cy, cw, 5, colour)
            val iconSize = 52
            icon(out, cx + 26, cy + 30, iconSize, stat.str("icon") ?: DeckIcons.pick(label, value, i, taken), wash(colour))

            val innerW = cw - 52
            // The measurement is the subject of the slide, so it gets whatever room the
            // column leaves rather than a size fixed for the shortest case.
            val size = DeckTheme.fit(value, innerW, 104, listOf(84, 70, 58, 46, 36), bold = true, linePct = 110)
            out += Label(cx + 26, cy + 30 + iconSize + 22, innerW, 104, listOf(value), size, DeckTheme.INK, bold = true, linePct = 110)
            val labelSize = DeckTheme.fit(label, innerW, 60, listOf(20, 18, 16), linePct = 128)
            out += Label(cx + 26, cy + ch - 84, innerW, 60, listOf(label), labelSize, DeckTheme.DIM, linePct = 128)
        }
        return title
    }

    private fun stack(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "The stack", slots.str("heading"), accent)
        val groups = slots.list("groups").take(Caps.MAX_GROUPS)
        if (groups.isEmpty()) return title
        val gap = 20
        val cw = (CONTENT_W - gap * (groups.size - 1)) / groups.size
        val most = groups.maxOf { it.strings("items").take(Caps.MAX_GROUP_ITEMS).size }
        // Tall enough for the longest list and no taller, then centred. Always filling
        // the body made four categories of two items each read as four empty panels.
        val ch = min(BODY_H, 158 + most * 34)
        val top = BODY_Y + max(0, (BODY_H - ch) / 2)

        groups.forEachIndexed { i, group ->
            val cx = MARGIN + i * (cw + gap)
            val colour = DeckTheme.accent(i)
            val name = DeckTheme.clip(group.str("category").orEmpty(), Caps.GROUP_NAME)
            val items = group.strings("items").take(Caps.MAX_GROUP_ITEMS).map { DeckTheme.clip(it, Caps.GROUP_ITEM) }

            out += Box(cx, top, cw, ch, DeckTheme.WASH, roundPct = 4)
            out += Box(cx, top, cw, 5, colour)
            icon(out, cx + 24, top + 28, 46, DeckIcons.pick(name, items.joinToString(" "), i, taken), wash(colour))
            out += Label(
                cx + 24, top + 92, cw - 48, 34, listOf(name.uppercase()), 15, colour,
                bold = true, spacing = 200
            )
            if (items.isNotEmpty()) {
                val listY = top + 130
                val listH = ch - 130 - 20
                val size = DeckTheme.fit(items.maxByOrNull { it.length } ?: "", cw - 48, listH / max(items.size, 1), listOf(19, 17, 15, 14))
                out += Label(cx + 24, listY, cw - 48, listH, items, size, DeckTheme.DIM, linePct = 126, gapPx = 10)
            }
        }
        return title
    }

    private fun journey(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "What happens", slots.str("heading") ?: slots.str("name"), accent)
        val steps = slots.list("steps").take(Caps.MAX_STEPS)
        if (steps.isEmpty()) return title
        val gap = 10
        val rowH = (BODY_H - gap * (steps.size - 1)) / steps.size
        val iconSize = min(62, rowH - 12)

        // One spine behind every step, drawn before them so the discs sit on it.
        if (steps.size > 1) {
            val top = BODY_Y + rowH / 2
            val bottom = BODY_Y + (steps.size - 1) * (rowH + gap) + rowH / 2
            out += Box(MARGIN + iconSize / 2 - 1, top, 3, bottom - top, DeckTheme.LINE)
        }

        steps.forEachIndexed { i, step ->
            val y = BODY_Y + i * (rowH + gap)
            val colour = DeckTheme.accent(i)
            val actor = DeckTheme.clip(step.str("actor").orEmpty(), Caps.STEP_LABEL)
            val action = DeckTheme.clip(step.str("action") ?: step.str("label").orEmpty(), Caps.BULLET)

            out += Box(MARGIN - 4, y + (rowH - iconSize) / 2 - 4, iconSize + 8, iconSize + 8, DeckTheme.PAPER, roundPct = 50)
            icon(out, MARGIN, y + (rowH - iconSize) / 2, iconSize, step.str("icon") ?: DeckIcons.pick(action, actor, i, taken), wash(colour))

            val textX = MARGIN + iconSize + 28
            val textW = CONTENT_W - iconSize - 28
            if (actor.isNotEmpty()) {
                out += Label(textX, y + (rowH - iconSize) / 2, textW, 22, listOf(actor.uppercase()), 14, colour, bold = true, spacing = 180)
            }
            val actionY = y + (rowH - iconSize) / 2 + (if (actor.isEmpty()) 0 else 26)
            val actionH = rowH - (actionY - y) - 4
            val size = DeckTheme.fit(action, textW, actionH, listOf(27, 24, 21, 18, 16), bold = true, linePct = 122)
            out += Label(textX, actionY, textW, actionH, listOf(action), size, DeckTheme.INK, bold = true, linePct = 122)
        }
        return title
    }

    /**
     * The product's own interface, redrawn at slide scale in the product's own colours.
     *
     * This is the one layout that does not use [DeckTheme]'s palette at all. Every fill,
     * every rule and every piece of type on the frame below the header comes out of
     * `slots.tokens`, which the harvester measured off the project's stylesheets. Our blue
     * appearing anywhere inside the frame would make it a picture of our template holding
     * their words, which is the opposite of the claim the slide is there to make.
     *
     * Nothing here is an icon, a screenshot or an image. It is boxes and text, which is
     * all a .pptx holds natively, so the slide stays a slide: a person can open it in
     * PowerPoint and move the sidebar, and every label in it is still selectable text.
     */
    private fun productUi(out: MutableList<Shape>, slots: JsonObject, accent: Int): String {
        val title = header(out, slots.str("eyebrow") ?: "The product", slots.str("heading") ?: "What it looks like", accent)
        val ui = slots.get("tokens") as? JsonObject ?: return title
        fun token(key: String, fallback: String) = ui.str(key) ?: fallback

        val page = token("page", DeckTheme.PAPER)
        val surface = token("surface", DeckTheme.WASH)
        val line = token("line", DeckTheme.LINE)
        val ink = token("ink", DeckTheme.INK)
        val dim = token("dim", DeckTheme.MUTE)
        val brandColour = token("accent", DeckTheme.accent(accent))
        val brandInk = token("accentInk", DeckTheme.PAPER)
        val wash = token("accentWash", DeckTheme.WASH)
        val radius = (ui.get("radius")?.takeIf { it.isJsonPrimitive }?.asInt ?: 8).coerceIn(0, 28)

        val rows = slots.list("nav").take(MAX_UI_ROWS)
        if (rows.isEmpty()) return title

        // The frame: a browser-shaped rectangle filling the body band, with the product's
        // page colour inside it and its own hairline around it rather than ours.
        val frameX = MARGIN
        val frameY = BODY_Y
        val frameW = CONTENT_W
        val frameH = BODY_H
        val corner = pctOf(radius, min(frameW, frameH))
        out += Box(frameX, frameY, frameW, frameH, page, roundPct = corner, stroke = line)

        // The title bar, and its three dots, because a rectangle with three dots in the
        // corner reads as a screen and a plain rectangle reads as a box.
        val barH = 38
        out += Box(frameX, frameY, frameW, barH, surface)
        out += Box(frameX, frameY + barH - 1, frameW, 1, line)
        repeat(3) { i -> out += Box(frameX + 18 + i * 16, frameY + barH / 2 - 4, 8, 8, line, roundPct = 50) }

        val railW = 268
        val railY = frameY + barH
        val railH = frameH - barH
        out += Box(frameX, railY, railW, railH, surface)
        out += Box(frameX + railW - 1, railY, 1, railH, line)

        // The lockup.
        val padX = 22
        val brand = DeckTheme.clip(slots.str("brand").orEmpty(), Caps.UI_BRAND)
        val markSize = 30
        out += Box(frameX + padX, railY + 22, markSize, markSize, brandColour, roundPct = pctOf(radius, markSize))
        out += Label(
            frameX + padX, railY + 22, markSize, markSize, listOf(brand.take(1).uppercase()),
            17, brandInk, bold = true, align = Align.CENTER, anchor = Anchor.MIDDLE
        )
        if (brand.isNotEmpty()) {
            val nameX = frameX + padX + markSize + 12
            val nameW = railW - (nameX - frameX) - padX
            val size = DeckTheme.fit(brand, nameW, 20, listOf(17, 15, 13, 12), bold = true, linePct = 110)
            out += Label(nameX, railY + 23, nameW, 20, listOf(brand), size, ink, bold = true, linePct = 110)
            val sub = slots.str("brandSub")?.let { DeckTheme.clip(it, Caps.UI_BRAND_SUB) }
            if (sub != null) {
                out += Label(nameX, railY + 43, nameW, 16, listOf(sub), 11, dim, linePct = 110)
            }
        }

        // The rows. Their words, their spacing, and the selected one wearing their accent
        // only when the project said which one it is.
        /*
         * Sized so every row fits, rather than sized to a constant and then truncated.
         *
         * A recreated sidebar that is one row shorter than the real one is a worse
         * failure than it looks: the viewer knows their own app has eight, and the
         * missing one reads as the tool having quietly decided something. The flagship
         * project this was checked against has exactly eight.
         */
        val rowsTop = railY + 84
        val rowGap = 4
        // Reserved before the rows are sized, not checked after they are placed. Sized
        // first, the rows fill the rail exactly and the line saying what was left off is
        // itself left off, which is the failure it exists to prevent, one level down.
        val more = slots.get("more")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val space = railY + railH - 12 - rowsTop - (if (more > 0) MORE_ROW_H else 0)
        val rowH = min(38, (space - rowGap * (rows.size - 1)) / rows.size)
        var y = rowsTop
        rows.forEach { row ->
            val label = DeckTheme.clip(row.str("label").orEmpty(), Caps.UI_ROW)
            if (label.isEmpty()) return@forEach
            val on = row.get("active")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            val rowX = frameX + 12
            val rowW = railW - 24
            if (on) out += Box(rowX, y, rowW, rowH, wash, roundPct = pctOf(radius, rowH))

            // A dot rather than an icon: we do not know which glyph they use, and drawing
            // a guessed icon next to a real word makes the real word look guessed too.
            out += Box(rowX + 14, y + rowH / 2 - 3, 6, 6, if (on) brandColour else dim, roundPct = 50)

            val badge = row.str("badge")?.let { DeckTheme.clip(it, Caps.UI_BADGE) }
            val badgeW = badge?.let { (DeckTheme.widthOf(it, 10, bold = true) + 16).toInt() } ?: 0
            val textX = rowX + 32
            val textW = rowW - (textX - rowX) - 12 - (if (badge != null) badgeW + 8 else 0)
            val size = DeckTheme.fit(label, textW, rowH - 8, listOf(15, 14, 13, 12), linePct = 112)
            out += Label(
                textX, y, textW, rowH, listOf(label), size, if (on) ink else dim,
                bold = on, anchor = Anchor.MIDDLE, linePct = 112
            )
            if (badge != null) {
                val bx = rowX + rowW - 12 - badgeW
                out += Box(bx, y + rowH / 2 - 9, badgeW, 18, wash, roundPct = 50)
                out += Label(bx, y + rowH / 2 - 9, badgeW, 18, listOf(badge), 10, brandColour, bold = true, align = Align.CENTER, anchor = Anchor.MIDDLE)
            }
            y += rowH + rowGap
        }

        // What did not fit, named rather than left as a silent shortening.
        if (more > 0) {
            out += Label(frameX + 44, y, railW - 56, MORE_ROW_H, listOf("+$more more"), 13, dim, anchor = Anchor.MIDDLE)
        }

        // The pane: whatever the product calls the steps of its own pipeline, in order.
        val paneX = frameX + railW
        val paneW = frameW - railW
        val stages = slots.strings("stages").take(MAX_UI_STAGES)

        val chipY = railY + 46
        val chipH = 40
        val chipGap = 12
        val innerX = paneX + 32
        val innerW = paneW - 64
        val chipW = if (stages.isEmpty()) 0 else (innerW - chipGap * (stages.size - 1)) / stages.size
        stages.forEachIndexed { i, stage ->
            val x = innerX + i * (chipW + chipGap)
            val text = DeckTheme.clip(stage, Caps.UI_STAGE)
            // The first stage is where the product opens, which is a fact about it rather
            // than a choice of ours, so it is the one that wears the accent.
            val lead = i == 0
            out += Box(x, chipY, chipW, chipH, if (lead) brandColour else surface, roundPct = pctOf(radius, chipH),
                stroke = if (lead) null else line)
            val size = DeckTheme.fit(text, chipW - 16, chipH - 10, listOf(14, 13, 12, 11), bold = true, linePct = 112)
            out += Label(x, chipY, chipW, chipH, listOf(text), size, if (lead) brandInk else dim,
                bold = true, align = Align.CENTER, anchor = Anchor.MIDDLE, linePct = 112)
            if (i < stages.size - 1) {
                out += Box(x + chipW + 2, chipY + chipH / 2, chipGap - 4, 1, line)
            }
        }

        /*
         * Two empty cards, whether or not the product declared a pipeline.
         *
         * They were once drawn only under the chips, which meant a project with no
         * pipeline got a correct sidebar beside a large empty rectangle, and an empty
         * rectangle reads as a rendering failure rather than as an honest blank. They are
         * deliberately blank inside: filling them with plausible rows would be inventing a
         * product, which is the one thing this slide exists not to do.
         */
        val cardY = if (stages.isEmpty()) chipY else chipY + chipH + 26
        val cardH = frameY + frameH - cardY - 28
        if (cardH > 40) {
            val cardW = (innerW - 20) / 2
            repeat(2) { i ->
                val x = innerX + i * (cardW + 20)
                out += Box(x, cardY, cardW, cardH, surface, roundPct = pctOf(radius, min(cardW, cardH)), stroke = line)
                out += Box(x + 20, cardY + 22, (cardW * 0.44).toInt(), 8, line, roundPct = 50)
                out += Box(x + 20, cardY + 44, (cardW * 0.72).toInt(), 6, line, roundPct = 50)
                out += Box(x + 20, cardY + 60, (cardW * 0.58).toInt(), 6, line, roundPct = 50)
            }
        }
        return title
    }

    /**
     * Where the period left the project, against where it found it.
     *
     * Two columns and an arrow, then the places the work landed. The arrow matters more
     * than it looks: two numbers side by side are a comparison only if something says
     * which way to read them, and without it a reader has to work out from the values
     * alone which column is the past.
     */
    private fun beforeAfter(out: MutableList<Shape>, slots: JsonObject, accent: Int): String {
        val title = header(out, slots.str("eyebrow") ?: "Before and after", slots.str("heading") ?: "What moved", accent)
        val rows = slots.list("rows").take(Caps.MAX_DELTA_ROWS)
        if (rows.isEmpty()) return title

        val colour = DeckTheme.accent(accent)
        val areas = slots.list("areas").take(Caps.MAX_GROUPS)
        val panelH = if (areas.isEmpty()) BODY_H else BODY_H - 148

        // The two columns, with the arrow in the gutter between them.
        val gap = 88
        val colW = (CONTENT_W - gap) / 2
        listOf(0, 1).forEach { side ->
            val x = MARGIN + side * (colW + gap)
            val before = side == 0
            out += Box(x, BODY_Y, colW, panelH, if (before) DeckTheme.WASH else wash(colour), roundPct = 3)
            if (!before) out += Box(x, BODY_Y, 5, panelH, colour)

            out += Label(
                x + 26, BODY_Y + 22, colW - 52, 20,
                listOf(if (before) "BEFORE" else "AFTER"), 14,
                if (before) DeckTheme.MUTE else colour, bold = true, spacing = 240
            )
            val when_ = slots.str(if (before) "beforeWhen" else "afterWhen")
            if (when_ != null) {
                out += Label(x + 26, BODY_Y + 46, colW - 52, 18, listOf(DeckTheme.clip(when_, 34)), 13, DeckTheme.MUTE)
            }

            // The note's band is taken out before the rows are sized, not drawn over them
            // afterwards: laid on top, it landed exactly on the first row's caption.
            val noteH = if (slots.str("afterNote") != null) 46 else 0
            val top = BODY_Y + 80
            val rowH = (panelH - 80 - 20 - noteH) / rows.size
            rows.forEachIndexed { i, row ->
                val y = top + i * rowH
                val value = DeckTheme.clip(row.str(if (before) "before" else "after").orEmpty(), Caps.DELTA_VALUE)
                val label = DeckTheme.clip(row.str("label").orEmpty(), Caps.DELTA_LABEL)
                val size = DeckTheme.fit(value, colW - 52, rowH - 26, listOf(40, 34, 28, 24), bold = true, linePct = 108)
                out += Label(x + 26, y, colW - 52, rowH - 24, listOf(value), size,
                    if (before) DeckTheme.DIM else DeckTheme.INK, bold = true, anchor = Anchor.BOTTOM, linePct = 108)
                out += Label(x + 26, y + rowH - 22, colW - 52, 18, listOf(label), 13, DeckTheme.MUTE)
            }

            // What the period did, under the after figure only. It describes the change
            // rather than being half of a comparison, so it belongs on one side.
            val note = slots.str("afterNote")
            if (!before && note != null) {
                out += Label(x + 26, BODY_Y + panelH - noteH - 4, colW - 52, noteH,
                    listOf(DeckTheme.clip(note, 76)), 13, colour, bold = true, linePct = 126,
                    anchor = Anchor.MIDDLE)
            }
        }

        // The arrow: a rule through the gutter with a disc on it, so the direction reads.
        val midY = BODY_Y + panelH / 2
        val gutter = MARGIN + colW
        out += Box(gutter + 10, midY - 1, gap - 20, 2, DeckTheme.LINE)
        out += Box(gutter + gap / 2 - 17, midY - 17, 34, 34, DeckTheme.PAPER, roundPct = 50, stroke = colour)
        out += Label(gutter + gap / 2 - 17, midY - 17, 34, 34, listOf("\u203A"), 22, colour,
            bold = true, align = Align.CENTER, anchor = Anchor.MIDDLE)

        if (areas.isEmpty()) return title

        // Where it landed. Counted from the changed paths, never described.
        val areasY = BODY_Y + panelH + 30
        out += Label(MARGIN, areasY, CONTENT_W, 18, listOf(slots.str("areasLabel") ?: "WHERE THE WORK LANDED"),
            13, colour, bold = true, spacing = 240)

        val cardY = areasY + 26
        val cardH = BODY_B - cardY
        val cardGap = 14
        val cardW = (CONTENT_W - cardGap * (areas.size - 1)) / areas.size
        areas.forEachIndexed { i, area ->
            val x = MARGIN + i * (cardW + cardGap)
            out += Box(x, cardY, cardW, cardH, DeckTheme.PAPER, roundPct = 6, stroke = DeckTheme.LINE)
            val name = DeckTheme.clip(area.str("name").orEmpty(), Caps.AREA_NAME)
            val count = area.str("detail").orEmpty()
            val size = DeckTheme.fit(name, cardW - 28, 24, listOf(17, 15, 13, 12), bold = true, linePct = 112)
            out += Label(x + 14, cardY + 14, cardW - 28, 24, listOf(name), size, DeckTheme.INK, bold = true, linePct = 112)
            out += Label(x + 14, cardY + 40, cardW - 28, 18, listOf(count), 12, DeckTheme.MUTE)
        }
        return title
    }

    /** A pixel radius expressed the way OOXML wants it: a percentage of the short side. */
    private fun pctOf(radiusPx: Int, shortSide: Int): Int =
        if (shortSide <= 0) 0 else (radiusPx * 100 / shortSide).coerceIn(0, 50)

    private const val MAX_UI_ROWS = 8
    private const val MORE_ROW_H = 24
    private const val MAX_UI_STAGES = 5

    private fun gaps(out: MutableList<Shape>, slots: JsonObject, accent: Int, taken: MutableSet<String>): String {
        val title = header(out, slots.str("eyebrow") ?: "Known gaps", slots.str("heading"), accent)
        val items = slots.strings("items").take(Caps.MAX_BULLETS).map { DeckTheme.clip(it, Caps.BULLET) }
        val framing = slots.str("framing")
        val top = if (framing != null) BODY_Y + 62 else BODY_Y
        if (framing != null) {
            val size = DeckTheme.fit(DeckTheme.clip(framing, Caps.BODY), CONTENT_W, 50, listOf(22, 20, 18), linePct = 130)
            out += Label(MARGIN, BODY_Y, CONTENT_W, 50, listOf(DeckTheme.clip(framing, Caps.BODY)), size, DeckTheme.DIM, linePct = 130)
        }
        if (items.isEmpty()) return title
        val space = BODY_B - top
        val gap = 10
        val rowH = min(88, (space - gap * (items.size - 1)) / items.size)
        var y = top
        items.forEachIndexed { i, item ->
            val colour = DeckTheme.accent(i)
            val iconSize = min(44, rowH - 12)
            out += Box(MARGIN, y, CONTENT_W, rowH, DeckTheme.WASH, roundPct = 4)
            icon(out, MARGIN + 18, y + (rowH - iconSize) / 2, iconSize, DeckIcons.pick(item, null, i, taken), wash(colour))
            val textX = MARGIN + 18 + iconSize + 20
            val textW = CONTENT_W - (textX - MARGIN) - 24
            val size = DeckTheme.fit(item, textW, rowH - 12, listOf(21, 19, 17, 15), linePct = 126)
            out += Label(textX, y, textW, rowH, listOf(item), size, DeckTheme.INK, anchor = Anchor.MIDDLE, linePct = 126)
            y += rowH + gap
        }
        return title
    }

    private fun closing(out: MutableList<Shape>, deck: Deck, slots: JsonObject, taken: MutableSet<String>): String {
        out += Box(0, 0, W, 10, DeckTheme.accent(0))
        val headline = DeckTheme.clip(slots.str("headline") ?: slots.str("cta") ?: "Thank you", Caps.STATEMENT)
        icon(out, W / 2 - 52, 168, 104, DeckIcons.pick(headline, deck.subtitle, 0, taken), wash(DeckTheme.accent(0)))

        val size = DeckTheme.fit(headline, 940, 170, listOf(58, 50, 42, 36, 30), bold = true, linePct = 114)
        out += Label(
            W / 2 - 470, 320, 940, 170, listOf(headline), size, DeckTheme.INK,
            bold = true, align = Align.CENTER, linePct = 114
        )
        out += Box(W / 2 - 110, 508, 220, 4, DeckTheme.accent(1))

        val repo = slots.str("repoUrl")
        if (repo != null) {
            out += Label(W / 2 - 470, 540, 940, 30, listOf(DeckTheme.clip(repo, 72)), 19, DeckTheme.accent(0), align = Align.CENTER, mono = true)
        }
        out += Label(
            W / 2 - 470, 596, 940, 26, listOf(slots.str("stamp") ?: deck.projectName), 15,
            DeckTheme.MUTE, align = Align.CENTER
        )
        return headline
    }

    // ------------------------------------------------------------------- slots

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.list(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> element.asString
                    // The director is asked for plain strings here but reaches for
                    // {name, tech} often enough that reading both beats dropping a layer.
                    element is JsonObject -> element.str("name") ?: element.str("label") ?: element.str("text")
                    else -> null
                }
            }
            ?.map { it.replace(Regex("\\s+"), " ").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}
