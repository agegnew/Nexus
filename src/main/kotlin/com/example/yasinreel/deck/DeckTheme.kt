package com.example.yasinreel.deck

/**
 * Colours, type and the one piece of typography a .pptx writer cannot get for free:
 * how wide a string is going to be.
 *
 * The palette is the reel's light theme, so a deck and a film generated from the same
 * project look like two views of one product rather than two tools that happened to run.
 */
object DeckTheme {

    /** The slide, in pixels. 1280 x 720 is exactly 12192000 x 6858000 EMU. */
    const val W = 1280
    const val H = 720

    /** The left and right margin every layout works inside. */
    const val MARGIN = 84
    const val CONTENT_W = W - MARGIN * 2

    const val INK = "0D1526"
    const val DIM = "46536A"
    const val MUTE = "8492A6"
    const val PAPER = "FFFFFF"
    const val WASH = "F2F5FA"
    const val LINE = "E3E9F2"

    /**
     * Four accents that cycle across a set rather than one repeated.
     *
     * The film learned this: every card in a row wearing the same stripe is the visual
     * signature of a template, and four hues in sequence is the signature of something
     * that was designed.
     */
    val ACCENTS = listOf("2F6BFF", "F0790B", "12B76A", "9B51E0")

    fun accent(i: Int): String = ACCENTS[((i % ACCENTS.size) + ACCENTS.size) % ACCENTS.size]

    /**
     * Arial, and the reason is dull but decisive: a .pptx names fonts, it does not
     * carry them. A deck set in something fashionable is re-set in a substitute on the
     * machine it is emailed to, and every measurement below becomes a lie. Arial is on
     * Windows, macOS, Office, LibreOffice and Google Slides with identical metrics, so
     * the slide that was checked here is the slide that arrives there.
     */
    const val FONT = "Arial"

    /** Only for a file path or a route, where a proportional font reads as prose. */
    const val MONO = "Consolas"

    /**
     * Arial advance widths per 1000 units of em, for the printable ASCII range.
     *
     * A browser can be asked how wide a string is. A .pptx writer has nobody to ask, so
     * it has to know. Without this the only options are to guess a character count, which
     * is what put a sentence into a slot sized for a number in the film, or to let the
     * text spill off the slide.
     */
    private val WIDTHS: IntArray = IntArray(128) { 556 }.also { w ->
        fun set(chars: String, value: Int) = chars.forEach { w[it.code] = value }
        set(" !", 278); w['"'.code] = 355; set("#$", 556); w['%'.code] = 889; w['&'.code] = 667
        w['\''.code] = 191; set("()", 333); w['*'.code] = 389; w['+'.code] = 584
        set(",.", 278); w['-'.code] = 333; w['/'.code] = 278
        set("0123456789", 556); set(":;", 278); set("<=>", 584); w['?'.code] = 556; w['@'.code] = 1015
        set("AE", 667); set("BF", 667); w['F'.code] = 611; set("CDHKNRUXY", 722)
        set("C", 722); set("G", 778); w['I'.code] = 278; w['J'.code] = 500; w['K'.code] = 667
        w['L'.code] = 556; w['M'.code] = 833; set("OQ", 778); w['P'.code] = 667; w['S'.code] = 667
        w['T'.code] = 611; w['V'.code] = 667; w['W'.code] = 944; set("XY", 667); w['Z'.code] = 611
        set("[]", 278); w['\\'.code] = 278; w['^'.code] = 469; w['_'.code] = 556; w['`'.code] = 333
        set("abdeghnopqu", 556); set("cx", 500); set("fjt", 278); w['i'.code] = 222; w['l'.code] = 222
        w['j'.code] = 222; w['k'.code] = 500; w['m'.code] = 833; w['r'.code] = 333
        set("svyz", 500); w['w'.code] = 722
        set("{}", 334); w['|'.code] = 260; w['~'.code] = 584
    }

    /** Width of [text] at [sizePx], in pixels. Bold Arial runs a little wider. */
    fun widthOf(text: String, sizePx: Int, bold: Boolean = false, spacingPx: Int = 0): Double {
        var units = 0
        for (ch in text) {
            units += if (ch.code < 128) WIDTHS[ch.code] else 600
        }
        val base = units / 1000.0 * sizePx * (if (bold) 1.06 else 1.0)
        return base + text.length * spacingPx
    }

    /**
     * How many lines [text] needs inside [boxW], wrapping on spaces the way a renderer
     * will. A word longer than the box gets a line to itself rather than an infinite
     * loop, which is what a package path in a slot does.
     */
    fun linesNeeded(text: String, boxW: Int, sizePx: Int, bold: Boolean = false): Int {
        if (text.isBlank() || boxW <= 0) return 1
        var lines = 1
        var used = 0.0
        val space = widthOf(" ", sizePx, bold)
        for (word in text.trim().split(' ')) {
            if (word.isEmpty()) continue
            val width = widthOf(word, sizePx, bold)
            when {
                used == 0.0 -> used = width
                used + space + width <= boxW -> used += space + width
                else -> { lines++; used = width }
            }
        }
        return lines
    }

    /**
     * The largest size from [steps] at which [text] fits in [boxW] x [boxH].
     *
     * The film's equivalent measured a real box in a browser and stepped down until
     * nothing spilled. Nothing here can measure, so it predicts instead, which is only
     * honest because [widthOf] is a real metric table rather than a character count.
     */
    fun fit(text: String, boxW: Int, boxH: Int, steps: List<Int>, bold: Boolean = false, linePct: Int = 118): Int {
        for (size in steps) {
            val lines = linesNeeded(text, boxW, size, bold)
            if (lines * size * linePct / 100 <= boxH) return size
        }
        return steps.last()
    }

    /** Trims to [max] characters on a word boundary, with an ellipsis if anything went. */
    fun clip(text: String, max: Int): String {
        val tidy = text.replace(Regex("\\s+"), " ").trim()
        if (tidy.length <= max) return tidy
        val cut = tidy.take(max).substringBeforeLast(' ', tidy.take(max))
        return cut.trimEnd(',', '.', ';', ':', ' ') + "…"
    }
}
