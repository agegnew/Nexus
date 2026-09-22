package com.example.yasinreel.deck

import com.google.gson.JsonObject

/**
 * Stage 3' output: the deck, and the contract between the director and [DeckGeometry].
 *
 * Deliberately the same shape as [com.example.yasinreel.model.Storyboard]: the AI never
 * writes XML, it picks a [Slide.layout] from [SlideLayout.ALL] and fills typed slots.
 * That single constraint is what made the film reliably good looking, because an unknown
 * layout is impossible and therefore a broken slide is impossible.
 *
 * A deck differs from a film in exactly one way that matters here, and it is [Slide.notes].
 * A film says everything out loud. A deck is read by a person standing in front of a room
 * who did not write the code, and the notes are what they say.
 */
data class Deck(
    val audience: String,
    val title: String,
    val subtitle: String,
    val projectName: String,
    val slides: List<Slide>
)

data class Slide(
    val layout: String,
    val slots: JsonObject,
    /** What the presenter says while this slide is up. Two or three sentences. */
    val notes: String?
)

object SlideLayout {
    const val TITLE = "title"
    const val AGENDA = "agenda"
    const val PROBLEM = "problem"
    const val STATEMENT = "statement"
    const val CAPABILITY_GRID = "capability-grid"
    const val ARCH_LAYERS = "arch-layers"
    const val FLOW = "flow"
    const val STATS = "stats"
    const val STACK = "stack"
    const val JOURNEY = "journey"
    const val GAPS = "gaps"
    const val CLOSING = "closing"

    val ALL = listOf(
        TITLE, AGENDA, PROBLEM, STATEMENT, CAPABILITY_GRID, ARCH_LAYERS,
        FLOW, STATS, STACK, JOURNEY, GAPS, CLOSING
    )

    /** Layouts that assume the reader reads code, so never used in a stakeholder deck. */
    val TECHNICAL_ONLY = setOf(ARCH_LAYERS, STACK)

    /** Layouts that assume the reader does not, so never used in a technical deck. */
    val STAKEHOLDER_ONLY = setOf(JOURNEY)

    /**
     * How many of each a deck may carry.
     *
     * A deck with three stat slides is a deck nobody finishes, and the director will
     * reach for the layout it found easiest to fill unless it is stopped.
     */
    val MAX_REPEATS: Map<String, Int> = mapOf(
        TITLE to 1, AGENDA to 1, CLOSING to 1, PROBLEM to 1,
        STATS to 1, GAPS to 1, JOURNEY to 1, ARCH_LAYERS to 1,
        STACK to 1, STATEMENT to 2, FLOW to 2, CAPABILITY_GRID to 2
    )
}

/**
 * Hard caps on every slot the director can fill, enforced by [DeckValidator].
 *
 * These exist because nothing downstream can measure text. The film could shrink type
 * until it fitted a real box in a real browser; a .pptx writer has no such luxury, so
 * the content is bounded before it ever reaches a slide. The number that mattered most
 * in the film was the 12 character cap on a stat value, which is the direct fix for the
 * frame where a 48 character sentence was typeset as display digits and clipped.
 */
object Caps {
    const val DECK_TITLE = 48
    const val DECK_SUBTITLE = 120

    const val HEADING = 54
    const val EYEBROW = 28

    const val STAT_VALUE = 12
    const val STAT_LABEL = 30
    const val MAX_STATS = 4

    const val CARD_TITLE = 42

    /**
     * A card with no body under it, which owns the whole box instead of the top of it.
     *
     * The progress deck's "what was done" grid is built from commit subjects, and a
     * commit subject is a sentence. At [CARD_TITLE] most of them ended on an ellipsis,
     * which is the one thing worse than not showing them: it looks like the tool ran out
     * of room rather than like the developer said something.
     */
    const val CARD_TITLE_ALONE = 96
    const val CARD_BODY = 150
    const val MAX_CARDS = 6

    const val AGENDA_ITEM = 46
    const val MAX_AGENDA = 6

    const val STEP_LABEL = 34
    const val STEP_DETAIL = 72
    const val MAX_STEPS = 5

    const val LAYER_NAME = 28
    // A pill sizes itself to its text, so this only has to stop one component name
    // eating a whole band. "EvidenceHarvester \u00b7 Kotlin" is 26 characters and was
    // being cut to "EvidenceHarvester \u00b7\u2026" by a cap set for a narrower layout.
    const val COMPONENT = 32
    const val MAX_LAYERS = 5
    const val MAX_COMPONENTS = 6

    const val GROUP_NAME = 24
    const val GROUP_ITEM = 26
    const val MAX_GROUPS = 4
    const val MAX_GROUP_ITEMS = 6

    const val BULLET = 92
    const val MAX_BULLETS = 5

    const val STATEMENT = 130
    const val BODY = 240
    const val NOTES = 600

    const val MAX_SLIDES = 14
    const val MIN_SLIDES = 6
}
