package com.example.yasinreel

import com.example.yasinreel.harvest.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversions the recreated interface is drawn from.
 *
 * These matter more than they look. A .pptx can only hold opaque sRGB, so every colour a
 * project declares has to survive one conversion before a slide can print it, and a
 * conversion that is a little bit wrong does not fail: it quietly draws somebody's brand
 * in the wrong colour and nothing anywhere says so. The expected values below are the
 * ones a browser resolves the same declarations to.
 */
class ColorMathTest {

    private fun hex(raw: String) = ColorMath.parse(raw)?.hex()

    @Test
    fun `hex in every length a stylesheet may write`() {
        assertEquals("FFFFFF", hex("#fff"))
        assertEquals("06B6A2", hex("#06B6A2"))
        assertEquals("0F1011", hex("0f1011"))
        // Eight digits carry alpha, and a slide has none, so it flattens over white.
        assertEquals("FFFFFF", hex("#00000000"))
        assertEquals("000000", hex("#000000FF"))
    }

    @Test
    fun `oklch resolves to what the browser shows`() {
        // This repo's own visualizer declares every colour this way, so getting it wrong
        // would mean the film of Nexus was drawn in colours Nexus does not use.
        assertEquals("18181B", hex("oklch(21% 0.006 285.885)"))
        assertEquals("E4E4E7", hex("oklch(92% 0.004 286.32)"))
        assertEquals("155DFC", hex("oklch(54.6% 0.245 262.881)"))
        // A bare fraction means the same as the percentage.
        assertEquals(hex("oklch(0.546 0.245 262.881)"), hex("oklch(54.6% 0.245 262.881)"))
    }

    @Test
    fun `alpha is flattened rather than dropped`() {
        // 42-studio's real accent wash: the teal at 8 percent over white.
        val wash = ColorMath.parse("rgba(6,182,162,0.08)")!!
        assertEquals("EBF9F8", wash.hex())
        // Over a different ground it is a different colour, which is why the ground is an
        // argument and not an assumption.
        val onDark = ColorMath.parse("rgba(6,182,162,0.08)", over = ColorMath.BLACK)!!
        assertTrue("a wash over black must be dark", onDark.luminance() < 0.05)
    }

    @Test
    fun `hsl and the shadcn triple agree`() {
        assertEquals("FFFFFF", hex("hsl(0 0% 100%)"))
        assertEquals("FFFFFF", ColorMath.fromHsl(0.0, 0.0, 1.0).hex())
        assertEquals(hex("hsl(217, 91%, 60%)"), ColorMath.fromHsl(217.0, 0.91, 0.60).hex())
    }

    @Test
    fun `an unreadable colour is null rather than a guess`() {
        // A guess here would be an invented brand colour, which is the one thing this
        // feature is not allowed to do.
        assertNull(hex("var(--brand)"))
        assertNull(hex("rgb(var(--brand))"))
        assertNull(hex("linear-gradient(red, blue)"))
        assertNull(hex(""))
        assertNull(hex("chartreuse"))
    }

    @Test
    fun `contrast and chroma say what the roles need them to say`() {
        val white = ColorMath.parse("#FFFFFF")!!
        val ink = ColorMath.parse("#0F1011")!!
        val line = ColorMath.parse("#E5E5E7")!!
        val dim = ColorMath.parse("#71717A")!!
        val accent = ColorMath.parse("#06B6A2")!!

        // 42-studio's real values, which is the shape every role rule is written against.
        assertTrue("ink on page must be strong", ColorMath.contrast(ink, white) > 18.0)
        assertTrue("a hairline is deliberately faint", ColorMath.contrast(line, white) in 1.1..1.4)
        assertTrue("secondary text still has to be readable", ColorMath.contrast(dim, white) >= 4.5)

        // The accent test needs no name list: every neutral scores near zero.
        assertTrue("the brand colour is the colourful one", accent.chroma() > 100)
        listOf(white, ink, line, dim).forEach {
            assertTrue("a neutral must not look like a brand colour", it.chroma() < 20)
        }
    }
}
