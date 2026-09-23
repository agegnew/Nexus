package com.example.trust

import java.awt.Color
import kotlin.math.roundToInt

/**
 * The one colour scale, shared by the treemap, the layer bars and the hero number.
 *
 * Shared rather than repeated because the three of them are one claim shown three ways. The
 * moment the treemap's red and the bar's red drift apart, the eye stops believing they are
 * measuring the same thing.
 *
 * Fixed RGB rather than theme colours: these are data, not chrome. A value that means
 * "nothing here has ever run" has to look the same to everyone in the room, and both stops
 * are mid-saturation enough to hold up on a light and a dark background alike.
 */
object TrustColors {

    private val PROVEN_GREEN = Color(62, 122, 78)
    private val HALFWAY_AMBER = Color(184, 134, 59)
    private val UNPROVEN_RED = Color(196, 68, 58)

    /** Green to amber to red. Two stops, because a single one washes the middle out. */
    fun ramp(percent: Double): Color {
        val t = (percent / 100.0).coerceIn(0.0, 1.0)
        return if (t < 0.5) blend(PROVEN_GREEN, HALFWAY_AMBER, t / 0.5)
        else blend(HALFWAY_AMBER, UNPROVEN_RED, (t - 0.5) / 0.5)
    }

    private fun blend(from: Color, to: Color, t: Double) = Color(
        (from.red + (to.red - from.red) * t).roundToInt(),
        (from.green + (to.green - from.green) * t).roundToInt(),
        (from.blue + (to.blue - from.blue) * t).roundToInt(),
    )
}
