package com.example.yasinreel.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The product's own interface, as typed data: what its navigation says, what its
 * pipeline calls its stages, and the colours it wears while saying them.
 *
 * ### Why this exists
 *
 * A stakeholder watching a film about software they paid for wants to see the software.
 * Every scene before this one is a claim about the product; this is the product. The
 * reference implementation for that idea hand-authored a page per project with an agent
 * looking at the source, which is not a thing that can run in a progress bar, so instead
 * the shape is ours and fixed and everything inside it is theirs and measured. The
 * template is a sidebar, a header and a row of stage chips, always. The words in the
 * sidebar, the labels on the chips, the accent on the selected row and the corner radius
 * are read off their repository and are never ours.
 *
 * ### What it deliberately does not carry
 *
 * **A product name.** It would be a third place the product's name is decided, after the
 * README and the manifest, and the two obvious sources are both wrong on real projects:
 * one flagship repository's manifest still holds a superseded codename, and another's
 * wordmark is authored as two spans so reading the first one renders four letters of a
 * seven letter word. The lockup shows the name the rest of the film already uses.
 *
 * **Which row is selected.** No project in the sample declares one: they compute it from
 * the router. Picking a row to highlight would be inventing a UI state, and inventing
 * anything is the one thing this feature cannot do, since its entire claim is that what
 * is on screen came off disk. So nothing is highlighted unless [activeIndex] was read.
 */
data class ProductUi(
    /** In source order, already cleaned and capped. Fewer than [MIN_ROWS] means no scene. */
    val nav: List<UiRow> = emptyList(),
    /** A pipeline the product puts in front of its user, in order. Often empty; that is fine. */
    val stages: List<String> = emptyList(),
    /** Roles, fonts and radius. Without [DesignSystem.roles] nothing is drawn at all. */
    val design: DesignSystem = DesignSystem(),
    /** Project-relative file the navigation was read from, so the claim can be checked. */
    val source: String? = null,
    /** Only ever set when the project declared it. See the class note. */
    val activeIndex: Int? = null
) {
    /**
     * Whether there is enough to draw an interface that is honestly theirs.
     *
     * The design system is not optional. Their words in our colours is a mock-up of a
     * product that does not exist, and it would be shown under a line claiming the
     * opposite. A recreation is either fully theirs or it is not drawn.
     */
    val usable: Boolean get() = design.roles != null && nav.size >= MIN_ROWS

    /**
     * The one slot object both renderers read.
     *
     * The film's outro once wrote `{headline, sub}` while the player read `{cta, repoUrl}`
     * and the last scene of every film quietly showed a hardcoded string. Two renderers
     * reading one writer is the fix, so the JSON is built here rather than twice.
     */
    fun slots(brand: String, brandSub: String?): JsonObject? {
        val roles = design.roles ?: return null
        if (nav.size < MIN_ROWS) return null

        val rows = JsonArray()
        nav.take(MAX_ROWS).forEachIndexed { i, row ->
            rows.add(JsonObject().apply {
                addProperty("label", row.label)
                row.badge?.let { addProperty("badge", it) }
                if (activeIndex == i) addProperty("active", true)
            })
        }

        val chips = JsonArray()
        stages.take(MAX_STAGES).forEach { chips.add(it) }

        val tokens = JsonObject().apply {
            addProperty("page", roles.page)
            addProperty("surface", roles.surface)
            addProperty("line", roles.line)
            addProperty("ink", roles.ink)
            addProperty("dim", roles.dim)
            addProperty("accent", roles.accent)
            addProperty("accentInk", roles.accentInk)
            addProperty("accentWash", roles.accentWash)
            addProperty("radius", design.radiusPx ?: DEFAULT_RADIUS)
            addProperty("scheme", design.scheme)
        }

        return JsonObject().apply {
            addProperty("brand", brand)
            brandSub?.let { addProperty("brandSub", it) }
            add("nav", rows)
            /*
             * How many rows did not fit, said out loud.
             *
             * Without it a viewer who uses this product every day sees eight of their ten
             * and reads the two missing ones as the tool having quietly decided something
             * about their software. Naming the number is a statement about the drawing,
             * not a claim about the product, which is the only kind of sentence this scene
             * is allowed to add.
             */
            if (nav.size > MAX_ROWS) addProperty("more", nav.size - MAX_ROWS)
            if (chips.size() > 0) add("stages", chips)
            add("tokens", tokens)
        }
    }

    companion object {
        /** Two rows is a pair of buttons. Three is a navigation. */
        const val MIN_ROWS = 3

        /**
         * Eight, because the project this was checked against has eight and shows eight.
         *
         * A cap of seven would have made our recreation of that sidebar less faithful
         * than the thing it is recreating, which is a strange way to lose an argument
         * about honesty.
         */
        const val MAX_ROWS = 8
        const val MAX_STAGES = 5

        /** Only ever used when the project declares no radius anywhere. */
        const val DEFAULT_RADIUS = 8
    }
}

/** One row of the product's navigation. [badge] is a real one it declared, such as "beta". */
data class UiRow(val label: String, val badge: String? = null)
