package com.example.yasinreel.model

/**
 * The design system a project actually wears, with roles rather than a bag of colours.
 *
 * [Palette] already collects the colours a project declares, and for the film's accent
 * rotation that is enough: any four brand colours in any order look like the brand.
 * Recreating an interface is a different job. To draw a sidebar you have to know which
 * colour is the page, which is the panel on top of it, which is the hairline between
 * them, which is the text and which is the one accent. A list cannot say that, so this
 * says it, and every field is either something we read or null.
 *
 * [confidence] is what the copy is allowed to claim. At "tokens" the project has a design
 * system and the deck may say so; at "derived" we found one accent and nothing else, so
 * the frame is ours and the copy must not pretend otherwise.
 */
data class DesignSystem(
    /** Null means no usable set was found, and the recreated interface is not drawn at all. */
    val roles: Roles? = null,
    /** First real family name, with the fallback stack dropped. Null when none was declared. */
    val displayFont: String? = null,
    val bodyFont: String? = null,
    val monoFont: String? = null,
    /** The corner the product actually uses most, not its largest or its smallest. */
    val radiusPx: Int? = null,
    /** "light" or "dark", as the product ships, not as a preference. */
    val scheme: String = LIGHT,
    /** The project-relative file the roles came from, so the claim can be checked. */
    val source: String? = null,
    /** "tokens" | "derived" | "none". */
    val confidence: String = NONE
) {
    val usable: Boolean get() = roles != null

    companion object {
        const val LIGHT = "light"
        const val DARK = "dark"
        const val TOKENS = "tokens"
        const val DERIVED = "derived"
        const val NONE = "none"
    }
}

/**
 * Every value is `RRGGBB`, opaque, sRGB, with no leading hash.
 *
 * That is the only form a .pptx can hold, so the conversion happens once on the way in
 * rather than twice on the way out. Both renderers paint these literally, which is what
 * stops the film and the deck drawing the same product in two different colours.
 */
data class Roles(
    val page: String,
    val surface: String,
    val line: String,
    val ink: String,
    val dim: String,
    val accent: String,
    /** A label colour that is legible on [accent]. */
    val accentInk: String,
    /** [accent] at about 8 percent, already flattened over [surface], because a slide has no alpha. */
    val accentWash: String
)
