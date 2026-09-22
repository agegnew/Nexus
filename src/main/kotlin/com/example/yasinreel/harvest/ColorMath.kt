package com.example.yasinreel.harvest

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Colour, reduced to one opaque sRGB number.
 *
 * [BrandPalette] keeps colours exactly as written, and for its job that is right: the
 * film drops them into CSS custom properties, and any conversion we did would be a
 * chance to be subtly wrong about somebody's brand.
 *
 * Recreating a product's interface cannot work that way. A .pptx has one colour format,
 * `<a:srgbClr val="RRGGBB"/>`, with no alpha and no colour space, so a slide literally
 * cannot draw `oklch(0.46 0.19 264)` or `rgba(6,182,162,0.08)`. Those are not edge cases:
 * every colour in this repo's own visualizer is oklch. So the conversion has to happen
 * once, here, before either renderer sees it, or the film and the deck would draw the
 * same product in two different colours.
 *
 * Alpha is flattened rather than dropped, over a background the caller names, because a
 * wash is a real part of a design system and 8% of teal over white is a colour a slide
 * can print.
 */
object ColorMath {

    /** An opaque sRGB colour, 0..255 per channel. */
    data class Rgb(val r: Int, val g: Int, val b: Int) {

        /** `RRGGBB`, no hash, which is the form both renderers take. */
        fun hex(): String = "%02X%02X%02X".format(r.clamp(), g.clamp(), b.clamp())

        /** WCAG relative luminance, 0 for black and 1 for white. */
        fun luminance(): Double {
            fun channel(value: Int): Double {
                val c = value.clamp() / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        /**
         * How colourful this is, as the sRGB range over the channels.
         *
         * Crude next to a perceptual chroma and entirely sufficient for the one question
         * it is asked: which of these is the brand colour and which are the greys. Every
         * neutral scores near zero however dark or light it is.
         */
        fun chroma(): Int = max(max(r, g), b) - min(min(r, g), b)

        /** [other] laid over this at [alpha], which is what a wash actually is. */
        fun over(other: Rgb, alpha: Double): Rgb {
            val a = alpha.coerceIn(0.0, 1.0)
            fun mix(top: Int, bottom: Int) = (top * a + bottom * (1 - a)).roundToInt()
            return Rgb(mix(other.r, r), mix(other.g, g), mix(other.b, b))
        }

        private fun Int.clamp() = coerceIn(0, 255)
    }

    /** WCAG contrast between two opaque colours, 1.0 for identical and 21.0 for black on white. */
    fun contrast(a: Rgb, b: Rgb): Double {
        val high = max(a.luminance(), b.luminance())
        val low = min(a.luminance(), b.luminance())
        return (high + 0.05) / (low + 0.05)
    }

    private val HEX = Regex("""^#?([0-9a-fA-F]{3,8})$""")
    private val FUNCTIONAL = Regex("""^([a-z]+)\(([^)]*)\)$""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""-?\d*\.?\d+""")

    /**
     * Reads any colour a stylesheet can hold, or null.
     *
     * Null is a real answer and the caller must treat it as one: an unreadable colour
     * means we do not know that part of their design system, and drawing a guess in its
     * place is exactly the invention this whole feature is not allowed to commit.
     */
    fun parse(raw: String?, over: Rgb = WHITE): Rgb? {
        val value = raw?.trim()?.removeSuffix(";")?.trim().orEmpty()
        if (value.isEmpty() || value.contains("var(")) return null

        HEX.matchEntire(value)?.let { return hex(it.groupValues[1], over) }

        val call = FUNCTIONAL.matchEntire(value) ?: return NAMED[value.lowercase()]
        // `rgb(0 0 0 / 50%)` and `rgb(0, 0, 0, 0.5)` are the same colour written two ways.
        val parts = NUMBER.findAll(call.groupValues[2].replace("%", " ")).map { it.value.toDouble() }.toList()
        val percent = call.groupValues[2].contains('%')
        return when (call.groupValues[1].lowercase()) {
            "rgb", "rgba" -> rgb(parts, call.groupValues[2], over)
            "hsl", "hsla" -> hsl(parts, call.groupValues[2], over)
            "oklch" -> oklch(parts, percent, call.groupValues[2], over)
            "oklab" -> oklab(parts, percent, call.groupValues[2], over)
            else -> null
        }
    }

    private fun hex(digits: String, over: Rgb): Rgb? {
        fun pair(at: Int) = digits.substring(at, at + 2).toInt(16)
        fun single(at: Int) = digits[at].toString().repeat(2).toInt(16)
        return when (digits.length) {
            3 -> Rgb(single(0), single(1), single(2))
            4 -> over.over(Rgb(single(0), single(1), single(2)), single(3) / 255.0)
            6 -> Rgb(pair(0), pair(2), pair(4))
            8 -> over.over(Rgb(pair(0), pair(2), pair(4)), pair(6) / 255.0)
            else -> null
        }
    }

    private fun rgb(parts: List<Double>, raw: String, over: Rgb): Rgb? {
        if (parts.size < 3) return null
        // A percentage triple is 0..100 per channel, everything else is 0..255.
        val scale = if (raw.trimStart().startsWith("rgb") || !raw.contains('%')) 1.0 else 2.55
        val solid = Rgb((parts[0] * scale).roundToInt(), (parts[1] * scale).roundToInt(), (parts[2] * scale).roundToInt())
        return flatten(solid, parts.getOrNull(3), raw, over)
    }

    private fun hsl(parts: List<Double>, raw: String, over: Rgb): Rgb? {
        if (parts.size < 3) return null
        val solid = fromHsl(parts[0], parts[1] / 100.0, parts[2] / 100.0)
        return flatten(solid, parts.getOrNull(3), raw, over)
    }

    /** shadcn/ui writes its tokens as a bare `0 0% 100%` triple, so this is public. */
    fun fromHsl(hueDeg: Double, saturation: Double, lightness: Double): Rgb {
        val s = saturation.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        val c = (1 - abs(2 * l - 1)) * s
        val h = ((hueDeg % 360) + 360) % 360 / 60.0
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        return Rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    private fun oklch(parts: List<Double>, percent: Boolean, raw: String, over: Rgb): Rgb? {
        if (parts.size < 3) return null
        // `oklch(14% ...)` and `oklch(0.14 ...)` are the same lightness written two ways.
        val l = if (percent && parts[0] > 1.0) parts[0] / 100.0 else parts[0]
        val hue = Math.toRadians(parts[2])
        return flatten(fromOklab(l, parts[1] * cos(hue), parts[1] * sin(hue)), parts.getOrNull(3), raw, over)
    }

    private fun oklab(parts: List<Double>, percent: Boolean, raw: String, over: Rgb): Rgb? {
        if (parts.size < 3) return null
        val l = if (percent && parts[0] > 1.0) parts[0] / 100.0 else parts[0]
        return flatten(fromOklab(l, parts[1], parts[2]), parts.getOrNull(3), raw, over)
    }

    /**
     * Oklab to sRGB, by Bjorn Ottosson's matrices and the sRGB transfer function.
     *
     * Written out rather than approximated because the numbers have to match what the
     * developer sees in their own browser. This repo's own visualizer declares every
     * colour in oklch, so an approximation here would mean the film of Nexus itself was
     * drawn in colours Nexus does not use.
     */
    private fun fromOklab(lightness: Double, a: Double, b: Double): Rgb {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }

        fun gamma(linear: Double): Int {
            val c = linear.coerceIn(0.0, 1.0)
            val encoded = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
            return (encoded * 255).roundToInt().coerceIn(0, 255)
        }
        return Rgb(
            gamma(+4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
        )
    }

    /** sRGB to Oklab lightness, used to compare two colours perceptually rather than by luminance. */
    fun lightness(colour: Rgb): Double {
        fun linear(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(colour.r)
        val g = linear(colour.g)
        val b = linear(colour.b)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    }

    private fun flatten(solid: Rgb, alpha: Double?, raw: String, over: Rgb): Rgb {
        if (alpha == null) return solid
        // `/ 50%` is a half, `/ 0.5` is the same half, `rgba(0,0,0,50)` is not a thing.
        val value = if (alpha > 1.0 && raw.contains('%')) alpha / 100.0 else alpha
        return over.over(solid, value)
    }

    val WHITE = Rgb(255, 255, 255)
    val BLACK = Rgb(0, 0, 0)

    /**
     * The handful of CSS keywords a real stylesheet uses for a surface or a rule.
     *
     * Not the full 148. The long tail is decorative names nobody writes into a design
     * token, and a wrong guess here is a wrong colour on somebody's product.
     */
    private val NAMED = mapOf(
        "white" to WHITE, "black" to BLACK,
        "transparent" to null, "currentcolor" to null, "inherit" to null, "none" to null
    )
}
