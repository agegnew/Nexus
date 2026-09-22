package com.example.yasinreel.harvest

import com.example.yasinreel.harvest.ColorMath.Rgb
import com.example.yasinreel.model.DesignSystem
import com.example.yasinreel.model.Roles
import com.intellij.openapi.diagnostic.Logger
import kotlin.math.abs

/**
 * Reads a project's design system: which colour is the page, the panel, the hairline, the
 * text and the one accent, plus its fonts and its corner radius.
 *
 * ### Why this does not work by name
 *
 * The obvious approach is a list of token names, and it is wrong. Across four unrelated
 * real projects the ground is called `bg.DEFAULT`, `--color-bg`, `--paper` and
 * `--surface-base`, and one of them numbers its ramp `--color-ink-0` upward with no role
 * words at all. A name list is always one project behind.
 *
 * So the roles are **measured**, in three passes:
 *
 *  1. **The body rule.** Every real app answers "what colour is the page and what colour
 *     is the text on it" in one CSS declaration: `body { background: X; color: Y }`. That
 *     is immune to naming and it pins the two roles everything else is measured against.
 *  2. **Lightness and contrast.** With the page and the ink known, the rest are
 *     measurements. The panel is the colour nearest the page that is not the page. The
 *     hairline is the one whose contrast against the panel lands near 1.3:1, which is the
 *     one role where a *low* contrast target is the right target and is exactly why a
 *     "most distinct colours" extractor can never find it. Secondary text is the darkest
 *     thing that is still lighter than the ink and still legible.
 *  3. **Chroma for the accent**, because every neutral scores near zero however light or
 *     dark it is. Names are used only to veto: a colour called danger or warning is a
 *     status hue and is never the brand, however colourful it is.
 *
 * ### What it refuses to do
 *
 * A half-read design system is worse than none, because a sidebar drawn in their accent
 * on our background reads as a bug rather than as their product. So the result is either
 * a complete, legible, self-consistent set or it is null, and null means the recreated
 * interface is not drawn anywhere.
 */
object DesignSystemHarvester {

    private val logger = Logger.getInstance(DesignSystemHarvester::class.java)

    /** One file, already read. Kept separate from the IDE so this can be tested on real repos. */
    data class Source(val path: String, val text: String)

    // ---------------------------------------------------------------- patterns

    /** `body { ... }`, `html, body { ... }`, and the app-shell class that paints instead. */
    private val BODY_RULE = Regex(
        """(?m)^[^{}\n]{0,120}\bbody\b[^{}\n]{0,120}\{([^{}]{0,1200})}"""
    )
    private val SHELL_RULE = Regex("""(?m)^\s*(\.[A-Za-z][\w-]{0,40})\s*\{([^{}]{0,1200})}""")

    private val DECLARATION = Regex("""(?m)^\s*([a-zA-Z-]{2,40})\s*:\s*([^;\n}]{1,200})""")
    private val CUSTOM_PROPERTY = Regex("""--([A-Za-z0-9_-]{1,60})\s*:\s*([^;\n}]{1,200})""")
    private val VAR_REFERENCE = Regex("""var\(\s*--([A-Za-z0-9_-]{1,60})""")
    private val APPLY_RULE = Regex("""@apply\s+([^;\n}]{1,160})""")
    private val APPLY_TOKEN = Regex("""\b(bg|text|border)-([a-z][\w-]{0,30})""")

    /** shadcn writes its colours as a bare `0 0% 100%` triple meant for `hsl()`. */
    private val HSL_TRIPLE = Regex("""^\s*([\d.]{1,8})\s+([\d.]{1,7})%\s+([\d.]{1,7})%\s*$""")

    private val COLOR_SCHEME = Regex("""color-scheme\s*:\s*(light|dark)\b""", RegexOption.IGNORE_CASE)
    private val RADIUS = Regex("""border-radius\s*:\s*([\d.]{1,6})(px|rem|em)""", RegexOption.IGNORE_CASE)
    private val FONT_FAMILY = Regex("""font-family\s*:\s*([^;\n}]{1,200})""", RegexOption.IGNORE_CASE)
    private val QUOTED_OR_WORD = Regex("""'([^']{1,40})'|"([^"]{1,40})"|([A-Za-z][\w -]{1,40})""")

    /** A status hue is never a brand colour, however colourful it is. */
    private val STATUS = Regex(
        """(?i)(success|ok|warn|warning|danger|error|err|destructive|info|critical|risk|unmatched|invalid)"""
    )
    private val BRANDY = Regex("""(?i)(brand|accent|primary|signature|theme)""")

    private val GENERIC_FAMILIES = setOf(
        "sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui", "ui-sans-serif",
        "ui-serif", "ui-monospace", "ui-rounded", "-apple-system", "blinkmacsystemfont",
        "inherit", "initial", "unset", "emoji", "math", "fangsong"
    )

    /** Below this the design system has not been themed and there is no brand colour to find. */
    private const val MIN_ACCENT_CHROMA = 24

    /**
     * Above this a colour is a colour, and secondary text is not.
     *
     * Set by measurement rather than by taste. The muted greys real products actually use
     * run from near zero (a true grey) to about 40 (a slate or a zinc, which carry a
     * deliberate cool cast). The two colours that wrongly won this role before the ceiling
     * existed were an amber and a deep blue, both far above it. Anything between is rare
     * and would be a judgement call either way.
     */
    private const val MAX_DIM_CHROMA = 60

    /** Where real muted text lands against its page, measured across the sample. */
    private const val DIM_TARGET = 5.0

    // ------------------------------------------------------------------ entry

    fun from(sources: List<Source>): DesignSystem =
        try {
            detect(sources)
        } catch (e: Exception) {
            logger.warn("Nexus could not read this project's design system, so no interface will be recreated", e)
            DesignSystem()
        }

    private fun detect(all: List<Source>): DesignSystem {
        /*
         * Our own output is not their design system.
         *
         * Exports land in `<project>/yasin-reel/` and working files in `.idea/yasin-reel/`,
         * both inside the project being read, so the second time anyone generates anything
         * the harvester finds our stylesheet sitting in their repository and reads our
         * colours back to them as theirs. On the first real project this was tried against
         * it won outright, because our stylesheet declares more tokens than the project's
         * own does. A tool that shows you your own template and calls it your brand is the
         * exact failure this feature exists to avoid.
         */
        val sources = all.filterNot { ours(it.path) }
        if (sources.isEmpty()) return DesignSystem()

        val scheme = schemeOf(sources)
        // Authority first, and depth as the tie-break, exactly as BrandPalette ranks.
        val ranked = sources.sortedByDescending { score(it) }.take(MAX_FILES)

        /*
         * One pool across the project, not one per file.
         *
         * A file is not the unit of a design system. The overwhelmingly common split is a
         * stylesheet that carries the body rule and a config that carries the colours:
         * 42-studio declares its ground in `styles.css` and its teal in
         * `tailwind.config.ts`, and reading either alone gets half an answer. What has to
         * be guarded is not which file a value came from but whether the set is coherent,
         * and that is what the contrast checks in `rolesFrom` are for: a light page with a
         * dark ink fails them, whichever files they came out of.
         */
        val tokens = LinkedHashMap<String, String>()
        ranked.forEach { source -> tokensIn(source.text).forEach { (k, v) -> tokens.putIfAbsent(k, v) } }

        val ground = ranked.firstNotNullOfOrNull { source -> groundIn(source.text, tokens) }
            ?: return DesignSystem(scheme = scheme)

        val pool = LinkedHashMap<String, Candidate>()
        ranked.forEach { source ->
            poolIn(source.text, tokens, ground.page).forEach { pool.putIfAbsent(it.colour.hex(), it) }
        }

        val roles = rolesFrom(ground, pool.values.toList()) ?: return DesignSystem(scheme = scheme)
        val body = ground.font ?: fontIn(ranked, "body", "sans", "ui", "base")
        return DesignSystem(
            roles = roles,
            displayFont = fontIn(ranked, "display", "heading", "head", "title") ?: body,
            bodyFont = body,
            monoFont = fontIn(ranked, "mono", "code"),
            radiusPx = radiusIn(ranked),
            scheme = scheme,
            source = ranked.firstOrNull { groundIn(it.text, tokens) != null }?.path,
            confidence = DesignSystem.TOKENS
        )
    }

    /**
     * Paths this plugin writes into the project it is reading.
     *
     * Mirrors ReelExporter and ReelPipeline's own layout. Matched on a path segment so a
     * project that legitimately has a directory called `reel` is not excluded.
     */
    private fun ours(path: String): Boolean =
        path.replace('\\', '/').split('/').any { segment ->
            val lowered = segment.lowercase()
            lowered in OURS || NOT_THE_PRODUCT.containsMatchIn(lowered)
        }

    private val OURS = setOf(
        "yasin-reel", "yasin-deck", "yasin-shared", ".work", "brag-output", ".claude",
        "test", "tests", "__tests__", "fixtures", "mocks", "stories", ".storybook", "docs"
    )

    /**
     * The site that sells the product is not the product, and it is usually a separate
     * package in the same repository with its own stylesheet. Reading it gives a deck
     * whose words came out of the application and whose colours came out of the brochure,
     * which is a combination nobody has ever seen on a screen.
     */
    private val NOT_THE_PRODUCT = Regex("""(?i)(landing|marketing|brag|storybook|fixture)""")

    // ------------------------------------------------------------ the body rule

    /** What the body rule pins down, which is the ground everything else is measured against. */
    private data class Ground(val page: Rgb, val ink: Rgb, val font: String?)

    private fun groundIn(text: String, tokens: Map<String, String>): Ground? =
        BODY_RULE.findAll(text).mapNotNull { groundOf(it.groupValues[1], tokens) }.firstOrNull()
            // An app shell that paints instead of the body, which is what a React app does.
            // The triple of background, colour and family together is what tells a shell
            // apart from a card, and a card is not the page.
            ?: SHELL_RULE.findAll(text).mapNotNull { groundOf(it.groupValues[2], tokens, needFont = true) }.firstOrNull()

    private fun groundOf(block: String, tokens: Map<String, String>, needFont: Boolean = false): Ground? {
        val declared = DECLARATION.findAll(block).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

        // Tailwind's `@apply bg-x text-y` is the same statement in another spelling.
        val applied = APPLY_RULE.find(block)?.let { rule ->
            APPLY_TOKEN.findAll(rule.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        }.orEmpty()

        val background = declared["background-color"] ?: declared["background"]?.substringBefore(' ')
            ?: applied["bg"]?.let { "var(--$it)" }
        val colour = declared["color"] ?: applied["text"]?.let { "var(--$it)" }
        val family = declared["font-family"]
        if (needFont && family == null) return null

        val page = resolve(background, tokens) ?: return null
        val ink = resolve(colour, tokens) ?: return null
        return Ground(page, ink, familyIn(family))
    }

    /** Follows one level of `var()` indirection, which is all a real stylesheet uses. */
    private fun resolve(raw: String?, tokens: Map<String, String>, depth: Int = 0): Rgb? {
        val value = raw?.trim() ?: return null
        ColorMath.parse(value)?.let { return it }
        if (depth >= MAX_VAR_DEPTH) return null
        val referenced = VAR_REFERENCE.find(value)?.groupValues?.get(1) ?: return null
        val next = tokens[referenced] ?: return null
        return resolve(next, tokens, depth + 1)
    }

    // ---------------------------------------------------------------- the pool

    /** One candidate colour the project declared, with the name it was declared under. */
    private data class Candidate(val name: String, val colour: Rgb)

    private fun tokensIn(text: String): Map<String, String> =
        CUSTOM_PROPERTY.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun poolIn(text: String, tokens: Map<String, String>, page: Rgb): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        tokens.forEach { (name, value) ->
            // shadcn writes `0 0% 100%` and means hsl(). Nothing else writes a bare triple.
            val colour = HSL_TRIPLE.matchEntire(value)?.let {
                ColorMath.fromHsl(it.groupValues[1].toDouble(), it.groupValues[2].toDouble() / 100, it.groupValues[3].toDouble() / 100)
            } ?: resolve(value, tokens)
            if (colour != null) out.putIfAbsent(colour.hex(), Candidate(name, colour))
        }
        // A Tailwind config or a token module is a map of names to hex with no `--`.
        NAMED_HEX.findAll(text).forEach { match ->
            val colour = ColorMath.parse(match.groupValues[2], over = page) ?: return@forEach
            out.putIfAbsent(colour.hex(), Candidate(match.groupValues[1], colour))
        }
        return out.values.toList()
    }

    private val NAMED_HEX = Regex(
        """['"]?([A-Za-z][\w.-]{1,40})['"]?\s*:\s*['"](#[0-9a-fA-F]{3,8}|(?:rgba?|hsla?|oklch)\([^)'"]{1,60}\))['"]"""
    )

    // ----------------------------------------------------------------- oracles

    private fun rolesFrom(ground: Ground, pool: List<Candidate>): Roles? {
        // A recreation nobody can read is worse than no recreation.
        if (ColorMath.contrast(ground.ink, ground.page) < 4.5) return null

        val taken = mutableSetOf(ground.page.hex(), ground.ink.hex())
        fun free() = pool.filterNot { it.colour.hex() in taken }

        /*
         * The panel: the nearest thing to the page that is not the page.
         *
         * Falls back to the page itself rather than to a guess, because a product whose
         * cards are the same white as its ground is a real and common design, and the
         * hairline is what separates them there.
         */
        val surface = free()
            .filter { abs(ColorMath.lightness(it.colour) - ColorMath.lightness(ground.page)) <= 0.10 }
            .minByOrNull { abs(ColorMath.contrast(it.colour, ground.page) - 1.18) }
            ?.also { taken += it.colour.hex() }
            ?.colour ?: ground.page

        /*
         * The hairline: the one role whose target contrast is LOW. A rule that stands out
         * is not a rule, it is a border, and no design system has those between a card and
         * its page. Measured on real products this lands between 1.2:1 and 1.5:1.
         *
         * Measured against the page rather than against the panel, because the panel is
         * itself a measurement and chaining two of them compounds the error. On the first
         * real project this was tried against, measuring from the panel picked the design
         * system's `border.strong` over its `border.DEFAULT`, which is a heavier rule than
         * the product draws anywhere.
         */
        val line = free()
            .filter { ColorMath.contrast(it.colour, ground.page) < 2.2 }
            .minByOrNull { abs(ColorMath.contrast(it.colour, ground.page) - 1.3) }
            ?.also { taken += it.colour.hex() }
            ?.colour ?: return null

        /*
         * Secondary text: still legible, still clearly below the ink, and NEUTRAL.
         *
         * The chroma ceiling is the part that is easy to leave out and wrong to. Without
         * it this picks the most contrasting thing left, which on a dark product was its
         * amber and on a light one a dark shade of its own blue, so every unselected row
         * of the recreated sidebar came out coloured. Nobody's secondary text is amber.
         * A muted label is a desaturated version of the ink in essentially every design
         * system there is, so a colourful candidate is not a candidate, and the blend of
         * ink into page is a better answer than the best coloured one.
         */
        val dim = free()
            .filter { it.colour.chroma() <= MAX_DIM_CHROMA }
            .filter { ColorMath.contrast(it.colour, ground.page) >= 4.0 }
            .filter { ColorMath.contrast(it.colour, ground.page) < ColorMath.contrast(ground.ink, ground.page) }
            /*
             * Nearest to [DIM_TARGET], not most contrasting and not least colourful.
             *
             * Both of those were tried and both are wrong in the same way, which is that
             * they treat this as an extreme when it is a middle. Most contrasting picks a
             * brand colour, because a brand colour is the strongest readable thing in most
             * stylesheets. Least colourful picks the near-black at the bottom of the
             * neutral ramp, which is the ink again rather than something below it. Muted
             * text is defined by sitting between the two, and on real products it sits
             * between about 4.5:1 and 7:1, so that is what this measures.
             */
            .sortedWith(compareBy<Candidate> { abs(ColorMath.contrast(it.colour, ground.page) - DIM_TARGET) }
                .thenBy { it.colour.chroma() })
            .firstOrNull()
            ?.also { taken += it.colour.hex() }
            ?.colour ?: muted(ground.ink, ground.page)

        /*
         * The accent: the most colourful thing left, with status hues vetoed by name.
         * That veto is the whole legitimate use of names here, and it is what stops a red
         * called "error" or "unmatched" being presented as somebody's brand.
         */
        val accent = free()
            .filterNot { STATUS.containsMatchIn(it.name) }
            .filter { it.colour.chroma() >= MIN_ACCENT_CHROMA }
            .sortedWith(compareByDescending<Candidate> { it.colour.chroma() }
                .thenByDescending { if (BRANDY.containsMatchIn(it.name)) 1 else 0 })
            .firstOrNull()?.colour ?: return null

        // Below four distinct colours we would be drawing our own template in their grey.
        if (setOf(ground.page.hex(), surface.hex(), line.hex(), ground.ink.hex(), accent.hex()).size < 4) return null
        // Two surfaces this far apart are two different themes, which means a light block
        // and a dark block have been read as one.
        if (ColorMath.contrast(surface, ground.page) > 3.0) return null

        val onAccent = if (ColorMath.contrast(ColorMath.WHITE, accent) >= ColorMath.contrast(ColorMath.BLACK, accent)) {
            ColorMath.WHITE
        } else {
            ColorMath.BLACK
        }
        return Roles(
            page = ground.page.hex(),
            surface = surface.hex(),
            line = line.hex(),
            ink = ground.ink.hex(),
            dim = dim.hex(),
            accent = accent.hex(),
            accentInk = onAccent.hex(),
            // Pre-mixed, because a slide has no alpha and both renderers must paint the
            // same number. A wash computed twice is a wash computed differently.
            accentWash = surface.over(accent, 0.10).hex()
        )
    }

    private fun blend(a: Rgb, b: Rgb, amount: Double): Rgb = a.over(b, amount)

    /**
     * Ink mixed into the page until it is muted but still readable.
     *
     * A fixed ratio cannot do this. The same 62 percent that reads as a proper muted grey
     * against one product's white lands under 3:1 against another's, because how far a
     * blend travels depends entirely on how far apart the two ends are. So it walks up
     * until it clears the threshold and stops, which is the same answer a designer picking
     * a muted token by eye arrives at.
     */
    private fun muted(ink: Rgb, page: Rgb): Rgb {
        var amount = 0.45
        while (amount < 0.95) {
            val candidate = page.over(ink, amount)
            if (ColorMath.contrast(candidate, page) >= 3.2) return candidate
            amount += 0.05
        }
        return page.over(ink, 0.95)
    }

    // ------------------------------------------------------------- trimmings

    /**
     * Which scheme the product ships in, not which one a visitor might prefer.
     *
     * An explicit `color-scheme` is an author stating the answer, so it wins outright.
     * A `prefers-color-scheme` block is a preference and is deliberately ignored: the
     * outer block is the product.
     */
    private fun schemeOf(sources: List<Source>): String {
        sources.forEach { source ->
            COLOR_SCHEME.find(source.text)?.let { return it.groupValues[1].lowercase() }
        }
        return DesignSystem.LIGHT
    }

    /** The corner the product uses most often, which is more honest than its smallest or largest. */
    private fun radiusIn(sources: List<Source>): Int? {
        val counted = mutableMapOf<Int, Int>()
        sources.forEach { source ->
            RADIUS.findAll(source.text).forEach { match ->
                val raw = match.groupValues[1].toDoubleOrNull() ?: return@forEach
                val px = if (match.groupValues[2].lowercase() == "px") raw else raw * 16
                // A pill is a shape, not a corner radius, so it never speaks for the system.
                if (px in 1.0..28.0) counted.merge(px.toInt(), 1, Int::plus)
            }
        }
        return counted.maxByOrNull { it.value }?.key
    }

    private fun fontIn(sources: List<Source>, vararg roles: String): String? {
        sources.forEach { source ->
            CUSTOM_PROPERTY.findAll(source.text).forEach { match ->
                val name = match.groupValues[1].lowercase()
                if (!name.contains("font")) return@forEach
                if (roles.none { name.contains(it) }) return@forEach
                familyIn(match.groupValues[2])?.let { return it }
            }
            FONT_FAMILY.find(source.text)?.let { match -> familyIn(match.groupValues[1])?.let { return it } }
        }
        return null
    }

    /** The first family that names a real face, with the fallback stack dropped. */
    private fun familyIn(declaration: String?): String? {
        val value = declaration?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("var(") } ?: return null
        QUOTED_OR_WORD.findAll(value).forEach { match ->
            val name = (match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }).trim()
            if (name.isNotEmpty() && name.lowercase() !in GENERIC_FAMILIES) return name
        }
        return null
    }

    /** Stylesheets and token modules first, and the shallowest of those first. */
    private fun score(source: Source): Int {
        val path = source.path.lowercase()
        var score = 0
        if (path.endsWith(".css") || path.endsWith(".scss")) score += 40
        if (path.contains("global") || path.contains("index.css") || path.contains("app.css")) score += 30
        if (path.contains("tailwind.config")) score += 35
        if (path.contains("token") || path.contains("theme") || path.contains("palette")) score += 25
        score -= path.count { it == '/' } * 2
        return score
    }

    private const val MAX_FILES = 24
    private const val MAX_VAR_DEPTH = 3
}
