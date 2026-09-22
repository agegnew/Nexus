package com.example.yasinreel.harvest

import com.example.yasinreel.model.DesignSystem
import com.example.yasinreel.model.ProductUi
import com.example.yasinreel.model.UiRow
import com.intellij.openapi.diagnostic.Logger

/**
 * Reads what a product's interface actually says: the rows of its navigation and the
 * labels on the stages of its pipeline.
 *
 * ### Why not one regex
 *
 * Across a set of unrelated real repositories, navigation is authored in exactly two
 * shapes and never in one. Most of it is a declared array of rows, which is the shape a
 * designer would guess:
 *
 * ```ts
 * export const NAV: NavItem[] = [
 *   { id: "dashboard", label: "Dashboard", Icon: LayoutDashboard },
 *   { id: "campaigns", label: "Producer",  Icon: Radio, badge: "beta" },
 * ]
 * ```
 *
 * But a smaller app writes the words directly into the markup with no array anywhere,
 * and an extractor that only understands arrays reports that such a project has no
 * interface, which is a lie about a project that has one. So both are read.
 *
 * ### Why the ranking matters more than the extraction
 *
 * Finding navigation is easy and finding *the* navigation is the whole problem. A real
 * repository yields around five plausible candidates, and the obvious tie-break, the
 * shallowest path, picked the wrong one on every project it was tried against: a legal
 * footer in one, a marketing site header in another, a settings sub-menu in a third.
 *
 * Two of the losers were an internal operations console and a platform-admin console,
 * whose rows read "Dead-letter", "Breaker", "Disputes", "Change Client Plan" and
 * "Platform Staff". Those are not ugly words on a slide. Put in front of the room this
 * film is made for, they are a disclosure. That is why the internal, test and marketing
 * paths below are a hard filter and not a low score: a scoring system that is usually
 * right is not good enough when the failure is unrecoverable.
 *
 * ### Why the banned-word list does not apply here
 *
 * These strings are the product's own vocabulary, chosen by the people who made it and
 * already on screen in front of its users. The narration guard, tuned for prose, deletes
 * "Library" and "Requests" as jargon while passing "Dead-letter" and "Breaker" untouched.
 * The path filter and the value guard below are the mechanism instead, and they work on
 * where a string came from rather than on whether it contains a stem.
 */
object UiStructureHarvester {

    private val logger = Logger.getInstance(UiStructureHarvester::class.java)

    /** One file, already read. Kept free of the IDE so this can be tested on real repos. */
    data class Source(val path: String, val text: String)

    // ------------------------------------------------------------- JSX lexing

    /*
     * Two spellings of "the rest of a JSX tag", and using the wrong one matches nothing.
     *
     * Attribute values contain the two characters a naive pattern stops at. `>` appears
     * inside every arrow function, so `<button[^>]*>` ends at the arrow, and `style={{…}}`
     * nests braces two deep, so a one level skipper walks straight past a real element.
     *
     * The possessive form can never give back what it consumed. That is correct, and
     * fast, when the very next thing to match is the tag's own `>`, which the pattern
     * cannot eat. It is fatal anywhere else: put a class literal after it and the soup
     * swallows the class and refuses to return it, so the pattern matches nothing at all,
     * on every file, silently. Hence two constants and not one.
     *
     * The bound on the lazy form is not tidiness. Unbounded, a generated or minified file
     * takes fifteen seconds of backtracking for one regex, on a thread the IDE is waiting
     * on. Bounded it takes a third of a second and returns identical results on real code.
     */
    private const val ATTRS_END = """(?>[^>{]|\{(?>[^{}]|\{[^{}]{0,200}\})*+\})*+"""

    private val NAV_BLOCK = Regex("""<(nav|aside)\b$ATTRS_END>([\s\S]{0,8000}?)</\1>""")
    private val CLICKABLE = Regex(
        """<(?:button|a|Link|NavLink)\b$ATTRS_END>\s*([A-Za-z0-9][^<>{}\n]{0,40}?)\s*</(?:button|a|Link|NavLink)>"""
    )

    // ---------------------------------------------------------- declared rows

    private val ARRAY_DECL = Regex(
        """(?:export\s+)?const\s+([A-Za-z_$][\w$]{0,60})\s*(?::[^=\n]{0,120})?=\s*\(?\s*\["""
    )

    /**
     * Only `label`, and the reason is that `name` and `title` are not navigation words.
     *
     * Widening the alternation by those two, which looks obviously right, harvests a
     * simulation fixture's four customers with their real phone numbers and email
     * addresses, because `{ name: 'Adam Bakirov' }` and `{ name: 'Dashboard' }` are the
     * same shape. It also turns one project's tab strip into alternating labels and
     * descriptions, because that file uses both keys on every row.
     */
    private val LABEL = Regex("""\blabel\s*:\s*(['"`])([^'"`\n]{1,60})\1""")
    private val BADGE = Regex("""\b(?:badge|tag|pill|chip)\s*:\s*(['"`])([^'"`\n]{1,24})\1""")
    private val HAS_ICON = Regex("""\b(?:Icon|icon|Ico|iconName|glyph|emoji)\s*:""")
    private val HAS_ROUTE = Regex("""\b(?:href|to|path|route|url|slug)\s*:""")

    // --------------------------------------------------------------- stages

    /**
     * A pipeline's labels are a map from an internal key to a display string, not a list.
     *
     * The list exists too, ten lines above, and it holds the internal keys. First match
     * wins puts "producer, editor, preview, calendar, approval" on a stakeholder slide
     * where the product itself says "Ideation, Editing, Finalization, Scheduling,
     * Approval". The file's own comment explains the trap: the keys stay, the labels
     * change. So the map is preferred and the values are taken, never the keys.
     */
    private val STAGE_MAP = Regex(
        """(?:export\s+)?const\s+([A-Za-z_$][\w$]{0,60})\s*(?::\s*[^=\n]{0,90})?=\s*\{([^{}]{0,2000})}"""
    )
    private val MAP_VALUE = Regex("""[:,]\s*(['"`])([^'"`\n]{1,48})\1""")
    private val STAGEY = Regex("""(?i)(step|stage|phase|pipeline|milestone|workflow)""")
    private val LABELLY = Regex("""(?i)(labels?|names?|titles?|copy|text)$""")
    private val KEYSY = Regex("""(?i)(order|keys?|ids?|slugs?|values?|tone|status|colou?rs?)$""")

    // ---------------------------------------------------------- the ranking

    private val CHROME_FILE = Regex("""(?i)(sidebar|navbar|shell|nav|layout|header|menu|chrome)""")
    private val FOOTERISH = Regex("""(?i)foot""")

    /** Splits `ADMIN_NAV` and `navItems` alike, because `_` is a word character. */
    private val SEGMENT = Regex("""[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+""")
    private val NAV_SEGMENT = setOf(
        "nav", "navs", "navigation", "menu", "menus", "sidebar", "tab", "tabs",
        "link", "links", "route", "routes", "item", "items", "section", "sections"
    )

    /**
     * Never read, whatever is in it.
     *
     * Four groups, and each one earned its place by putting something specific on a
     * screen. Our own exports, because the second run of this plugin finds the first
     * run's output sitting in the project and reads it back as the project. Test
     * fixtures, because one repository's scraped HTML fixtures made the recreated
     * interface a foreign military's public website. Internal consoles, because their
     * rows are commercially sensitive. And the marketing site, because the page that
     * sells the product is not the product, and its menu says "Private beta" and
     * "Early partners, announcing soon" where the real one says what the software does.
     */
    private val EXCLUDED = setOf(
        "yasin-reel", "yasin-deck", "yasin-shared", ".work", "brag-output", ".claude",
        "test", "tests", "__tests__", "__mocks__", "spec", "specs", "e2e", "cypress",
        "playwright", "fixture", "fixtures", "mock", "mocks", "stories", ".storybook",
        "storybook", "seed", "seeds", "seed-data", "sim", "sample", "samples",
        "example", "examples", "demo", "demos", "sandbox", "scripts", "script",
        "admin", "superadmin", "sysadmin", "dev", "devtools", "internal", "staff",
        "ops", "debug", "marketing", "landing", "legal", "footer", "docs", "doc"
    )

    /**
     * The same four groups again, for the very common case where the word is part of a
     * name rather than the whole of it: `web-landing`, `LegalPageShell.tsx`, `Footer.tsx`.
     *
     * Segment equality alone missed every one of those, and the last two are how a
     * project's privacy links and terms of service were drawn as its product navigation.
     */
    private val EXCLUDED_WITHIN =
        Regex("""(?i)(landing|marketing|brag|storybook|fixture|superadmin|legal|footer|privacy|cookie)""")

    private val INTERNAL_ID = setOf("admin", "superadmin", "sysadmin", "dev", "internal", "staff", "ops", "debug")

    private val UI_FILE = Regex("""(?i)\.(tsx|jsx|ts|js|mjs|vue|svelte|html)$""")

    // ------------------------------------------------------------ value guard

    private val I18N_KEY = Regex("""^[a-z0-9]+([_.:-][a-z0-9]+)+$""")
    private val CLASS_TOKEN = Regex("""(^|\s)(bg|text|border|max-w|min-w|rounded|shadow|flex|grid|gap|p|px|py|m|mx|my|w|h)-""")
    private val SCREAMING = Regex("""^[A-Z][A-Z0-9]*([ _-][A-Z0-9]+)+$""")
    private val BAD_CHAR = Regex("""[/#@\\{}<>|`~^*]|https?:""")

    /**
     * Words that are true of the software and wrong on this screen.
     *
     * Every one of these was measured reaching a slide. A failure pill is the highest
     * frequency badge in one repository, so a naive frequency ranking shows the room
     * "canned output" as the product's most characteristic label.
     */
    private val UNSHOWABLE = Regex(
        """(?i)\b(failed|failure|rejected|expired|declined|error|errors|canned|mock|stub|dead-?letter|""" +
            """breaker|dispute|disputes|deprecated|impersonate|not set|unresolved|unmatched|todo|fixme)\b"""
    )

    private val ENTITIES = mapOf(
        "&amp;" to "&", "&#038;" to "&", "&apos;" to "’", "&#39;" to "’",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&quot;" to "\"", "&ldquo;" to "“",
        "&rdquo;" to "”", "&nbsp;" to " ", "&ndash;" to "–", "&hellip;" to "…"
    )

    private const val MAX_FILES = 900
    private const val MIN_STAGES = 3

    // ------------------------------------------------------------------ entry

    fun from(sources: List<Source>, design: DesignSystem): ProductUi =
        try {
            detect(sources, design)
        } catch (e: Exception) {
            logger.warn("Nexus could not read this project's interface, so none will be recreated", e)
            ProductUi(design = design)
        }

    private fun detect(all: List<Source>, design: DesignSystem): ProductUi {
        val sources = all.asSequence()
            .filter { UI_FILE.containsMatchIn(it.path) }
            .filterNot { excluded(it.path) }
            .take(MAX_FILES)
            .toList()
        if (sources.isEmpty()) return ProductUi(design = design)

        val best = sources.flatMap { candidates(it) }.maxWithOrNull(RANK)
            ?: return ProductUi(design = design, stages = stages(sources))

        return ProductUi(
            nav = best.rows,
            stages = stages(sources),
            design = design,
            source = best.path
        )
    }

    /** Ranked first, then shallowest, then alphabetical, so the answer never moves. */
    private val RANK: Comparator<Candidate> = compareBy<Candidate> { it.score }
        .thenByDescending { it.path.count { ch -> ch == '/' } }
        .thenByDescending { it.path }

    private data class Candidate(val path: String, val rows: List<UiRow>, val score: Int)

    // ------------------------------------------------------------- exclusions

    private fun excluded(path: String): Boolean =
        path.replace('\\', '/').split('/').any { segment ->
            val lowered = segment.lowercase()
            lowered in EXCLUDED || EXCLUDED_WITHIN.containsMatchIn(lowered)
        }

    private fun internal(identifier: String): Boolean =
        SEGMENT.findAll(identifier).any { it.value.lowercase() in INTERNAL_ID }

    // --------------------------------------------------------- the candidates

    private fun candidates(source: Source): List<Candidate> =
        declaredRows(source) + markupRows(source)

    /** The common shape: `const NAV = [{ label: "..." }, ...]`. */
    private fun declaredRows(source: Source): List<Candidate> =
        ARRAY_DECL.findAll(source.text).mapNotNull { decl ->
            val identifier = decl.groupValues[1]
            if (internal(identifier)) return@mapNotNull null
            val body = bracketed(source.text, decl.range.last, '[', ']') ?: return@mapNotNull null

            /*
             * Only the array's own elements, which a regex for `{...}` cannot express: it
             * matches innermost braces and so descends into a nested object. That is how a
             * database seed file's `payload: { title: "..." }` rows were harvested as
             * navigation, one internal roadmap note per row.
             */
            val items = children(body)
            val rows = items.mapNotNull { item ->
                val label = LABEL.find(item)?.groupValues?.get(2)?.let(::clean) ?: return@mapNotNull null
                UiRow(label, BADGE.find(item)?.groupValues?.get(2)?.let(::clean))
            }.distinctBy { it.label }
            if (rows.size < ProductUi.MIN_ROWS) return@mapNotNull null

            /*
             * Row count is a tie-break, never a qualification.
             *
             * Every array of three or more display strings looks like a navigation from
             * far enough away, and one project's widest such array is the column header
             * set of a results table: "When (UTC), Subject area, Severity, Score". Eight
             * real strings, really on screen, and not a navigation. So a candidate has to
             * carry at least one structural signal that it is chrome: it lives in a file
             * named for the shell, it is named for a menu, or its rows carry the icon or
             * the destination that only a navigable row has.
             */
            var signals = 0
            if (CHROME_FILE.containsMatchIn(source.path.substringAfterLast('/'))) signals += 3
            if (SEGMENT.findAll(identifier).any { it.value.lowercase() in NAV_SEGMENT }) signals += 2
            if (items.any { HAS_ICON.containsMatchIn(it) }) signals += 2
            if (items.any { HAS_ROUTE.containsMatchIn(it) }) signals += 2
            if (signals == 0) return@mapNotNull null
            Candidate(source.path, rows, signals + rows.size / 2)
        }.toList()

    /** The other shape: the words are in the markup and there is no array anywhere. */
    private fun markupRows(source: Source): List<Candidate> =
        NAV_BLOCK.findAll(source.text).mapNotNull { block ->
            val attrs = block.value.substringBefore('>')
            // A footer is navigation and is not the navigation.
            if (FOOTERISH.containsMatchIn(attrs)) return@mapNotNull null

            val rows = CLICKABLE.findAll(block.groupValues[2])
                .mapNotNull { clean(it.groupValues[1]) }
                .distinct()
                .map { UiRow(it) }
                .toList()
            if (rows.size < ProductUi.MIN_ROWS) return@mapNotNull null

            // Being literally a <nav> is the signal here, so this path always has one.
            var signals = 2
            if (CHROME_FILE.containsMatchIn(source.path.substringAfterLast('/'))) signals += 3
            if (attrs.contains("aria-label")) signals += 2
            Candidate(source.path, rows, signals + rows.size / 2)
        }.toList()

    // -------------------------------------------------------------- the stages

    private fun stages(sources: List<Source>): List<String> =
        sources.asSequence().flatMap { source ->
            STAGE_MAP.findAll(source.text).mapNotNull { match ->
                val identifier = match.groupValues[1]
                if (!STAGEY.containsMatchIn(identifier) || internal(identifier)) return@mapNotNull null
                val values = MAP_VALUE.findAll(match.groupValues[2])
                    .mapNotNull { clean(it.groupValues[2]) }
                    .distinct()
                    .toList()
                if (values.size < MIN_STAGES) return@mapNotNull null

                /*
                 * A stage a person is shown is written like a heading. An internal key is
                 * not, and the two live side by side in the same file under names that
                 * differ by one word. The capital is the honest discriminator: it is what
                 * separates "Ideation, Editing, Finalization" from "producer, editor,
                 * preview" and, in another repository, a real wizard from a list of CSS
                 * tone tokens reading "ok, accent, warn, quiet".
                 */
                if (values.count { it.first().isUpperCase() } * 3 < values.size * 2) return@mapNotNull null

                var score = 0
                if (LABELLY.containsMatchIn(identifier)) score += 3
                if (KEYSY.containsMatchIn(identifier)) score -= 3
                Triple(score, source.path, values)
            }
        }.maxWithOrNull(
            compareBy<Triple<Int, String, List<String>>> { it.first }.thenByDescending { it.second }
        )?.third.orEmpty()

    // ---------------------------------------------------------------- cleaning

    /**
     * One string, decided.
     *
     * Everything rejected here was measured reaching a screen from a real repository:
     * an untranslated i18n key, a Tailwind class name used as a size label, an internal
     * agent codename in screaming kebab, an undecoded HTML entity, a slash-separated
     * pair of internal team names, and a marketing commitment with a date in it.
     */
    private fun clean(raw: String): String? {
        var value = raw
        ENTITIES.forEach { (entity, real) -> value = value.replace(entity, real) }
        value = value.replace(Regex("""&#\d{2,5};"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('→', '›', '»', '>', '…', ':')
            .trim()

        if (value.length !in 2..24) return null
        // Absolute, everywhere: an em dash never leaves this plugin, harvested or written.
        if (value.contains('\u2014')) return null
        if (value.none { it.isLetter() }) return null
        if (BAD_CHAR.containsMatchIn(value)) return null
        if (I18N_KEY.matches(value)) return null
        if (CLASS_TOKEN.containsMatchIn(value)) return null
        if (SCREAMING.matches(value)) return null
        if (UNSHOWABLE.containsMatchIn(value)) return null
        return value
    }

    // ----------------------------------------------------------------- parsing

    /** The balanced span opened by the bracket at or just after [from], exclusive of both ends. */
    private fun bracketed(text: String, from: Int, open: Char, close: Char): String? {
        val start = text.indexOf(open, from - 1).takeIf { it >= 0 } ?: return null
        var depth = 0
        var quote = '\u0000'
        var i = start
        while (i < text.length) {
            val ch = text[i]
            when {
                quote != '\u0000' -> {
                    if (ch == '\\') i++ else if (ch == quote) quote = '\u0000'
                }
                ch == '\'' || ch == '"' || ch == '`' -> quote = ch
                ch == open -> depth++
                ch == close -> {
                    depth--
                    if (depth == 0) return text.substring(start + 1, i)
                }
            }
            i++
            if (i - start > MAX_SPAN) return null
        }
        return null
    }

    /** The `{...}` spans that are direct elements of an array body, never the ones inside them. */
    private fun children(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var quote = '\u0000'
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                quote != '\u0000' -> {
                    if (ch == '\\') i++ else if (ch == quote) quote = '\u0000'
                }
                ch == '\'' || ch == '"' || ch == '`' -> quote = ch
                ch == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                ch == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += body.substring(start + 1, i)
                        start = -1
                    }
                    if (depth < 0) return out
                }
            }
            i++
        }
        return out
    }

    private const val MAX_SPAN = 12_000
}
