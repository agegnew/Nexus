package com.example.yasinreel.deck

/**
 * One drawable on a slide, positioned absolutely, measured in pixels.
 *
 * This is the whole reason the deck has a middle layer at all. Two renderers read it,
 * [PptxWriter] and the preview in the tool window, and neither of them decides anything
 * about layout, so neither can disagree with the other about what is on a slide. The
 * film learned that lesson the hard way: the outro wrote `{headline, sub}` while the
 * player read `{cta, repoUrl}`, and the last scene of every film quietly showed a
 * hardcoded string instead of what the director had written.
 *
 * Pixels rather than EMU because a 16:9 slide is 12192000 x 6858000 EMU and there are
 * exactly 9525 EMU in a pixel at 96 dpi, so 1280 x 720 px lands on a slide with no
 * rounding whatsoever. It is also the same coordinate space the reel stage uses, which
 * means the instincts built there transfer without conversion.
 */
sealed class Shape {
    abstract val x: Int
    abstract val y: Int
    abstract val w: Int
    abstract val h: Int
}

/** A filled rectangle: a card, a rule, an accent bar, a tinted disc behind an icon. */
data class Box(
    override val x: Int,
    override val y: Int,
    override val w: Int,
    override val h: Int,
    val fill: String,
    /** Corner rounding as a percentage of the short side, the way OOXML's roundRect wants it. */
    val roundPct: Int = 0,
    val stroke: String? = null,
    val strokeWidth: Int = 1
) : Shape()

enum class Align { LEFT, CENTER, RIGHT }

/** Vertical placement of the text inside its own frame. */
enum class Anchor { TOP, MIDDLE, BOTTOM }

/**
 * A run of text.
 *
 * [lines] are paragraphs, not wrapped lines: the renderer wraps. One entry is the
 * common case, several is a list. Bullets are drawn by the geometry as real shapes
 * rather than asked for from the paragraph properties, because a bullet glyph
 * inherited from a layout is one more thing that can differ between two readers.
 */
data class Label(
    override val x: Int,
    override val y: Int,
    override val w: Int,
    override val h: Int,
    val lines: List<String>,
    /** Font size in pixels. OOXML wants hundredths of a point, which is px * 75. */
    val sizePx: Int,
    val color: String,
    val bold: Boolean = false,
    val align: Align = Align.LEFT,
    val anchor: Anchor = Anchor.TOP,
    /** Letter spacing in hundredths of a point, for the small uppercase eyebrows. */
    val spacing: Int = 0,
    /** Line height as a percentage. 100 is single spaced. */
    val linePct: Int = 118,
    /** Space above each paragraph after the first, in pixels. */
    val gapPx: Int = 0,
    val mono: Boolean = false
) : Shape()

/** One of the sixteen icons the film uses, drawn from `resources/yasin-deck/icons`. */
data class Pic(
    override val x: Int,
    override val y: Int,
    override val w: Int,
    override val h: Int,
    val icon: String
) : Shape()

/**
 * A finished slide: its shapes, plus what the presenter says while it is up.
 *
 * The notes are the one thing a deck has that the film does not, and they are what make
 * it usable by someone who did not write the code.
 */
data class SlideArt(
    val shapes: List<Shape>,
    val notes: String,
    val title: String
)
