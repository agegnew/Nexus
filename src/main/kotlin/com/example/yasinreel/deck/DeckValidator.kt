package com.example.yasinreel.deck

import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

/**
 * The deterministic critic, the deck's equivalent of [StoryboardValidator].
 *
 * It owns structure and vocabulary. It does not own length: how much text fits is a
 * property of a box, and only [DeckGeometry] knows the boxes, so it clips there. Two
 * places clipping the same strings to different numbers is how a contract drifts.
 *
 * The vocabulary rule is the one this project has already been burned by. A stakeholder
 * cut once went out full of POST, FastAPI and file paths, and the response to that was
 * fair and blunt. So a stakeholder deck is scanned word by word against the same list the
 * film uses, and anything that trips it is removed rather than shipped.
 */
object DeckValidator {

    private val logger = Logger.getInstance(DeckValidator::class.java)

    data class Result(val deck: Deck, val issues: List<String>) {
        val ok: Boolean get() = issues.isEmpty()
    }

    /** Slots whose text a reader sees. Notes are the presenter's, and handled separately. */
    private val VISIBLE = setOf(
        "productName", "tagline", "project", "heading", "eyebrow", "statement", "body",
        "context", "attribution", "framing", "headline", "cta", "title", "label",
        "detail", "actor", "action", "value", "name", "category", "items", "components"
    )

    fun validate(deck: Deck, evidence: Evidence): Result {
        val issues = mutableListOf<String>()
        val stakeholder = deck.audience == Audience.STAKEHOLDER
        val seen = mutableMapOf<String, Int>()
        val kept = mutableListOf<Slide>()

        for (slide in deck.slides) {
            if (slide.layout !in SlideLayout.ALL) {
                issues += "Dropped a slide with the unknown layout '${slide.layout}'."
                continue
            }
            val wrongAudience =
                (stakeholder && slide.layout in SlideLayout.TECHNICAL_ONLY) ||
                    (!stakeholder && slide.layout in SlideLayout.STAKEHOLDER_ONLY)
            if (wrongAudience) {
                issues += "Dropped a ${slide.layout} slide, which is not for this audience."
                continue
            }
            val allowed = SlideLayout.MAX_REPEATS[slide.layout] ?: 2
            val used = seen.getOrDefault(slide.layout, 0)
            if (used >= allowed) {
                issues += "Dropped a repeated ${slide.layout} slide, the deck already had $allowed."
                continue
            }

            val cleaned = if (stakeholder) scrub(slide, evidence, issues) else slide
            if (cleaned == null) {
                issues += "Dropped a ${slide.layout} slide whose text could not be made plain."
                continue
            }
            seen[slide.layout] = used + 1
            kept += cleaned
        }

        if (kept.size > Caps.MAX_SLIDES) {
            issues += "Trimmed the deck from ${kept.size} slides to ${Caps.MAX_SLIDES}."
        }
        val final = kept.take(Caps.MAX_SLIDES)

        if (final.isEmpty()) {
            // Never hand back an empty package. A deck with one honest slide is a result;
            // a .pptx with no slides is a file that will not open.
            issues += "No slide survived validation, so the deck is a single title card."
            return Result(deck.copy(slides = listOf(titleOnly(deck))), issues)
        }
        if (final.size < Caps.MIN_SLIDES) {
            issues += "Only ${final.size} slides had enough evidence behind them."
        }
        if (issues.isNotEmpty()) {
            logger.info("Nexus Deck validation on the ${deck.audience} deck: ${issues.joinToString(" ")}")
        }
        return Result(deck.copy(slides = final), issues)
    }

    /**
     * Strips anything from a stakeholder slide that reads as engineering.
     *
     * Removal is by field, not by slide, wherever that is possible: losing the body of
     * one card costs the reader a sentence, and dropping the slide costs them the whole
     * idea. A slide only goes when the thing that failed was the thing it is about.
     */
    private fun scrub(slide: Slide, evidence: Evidence, issues: MutableList<String>): Slide? {
        val slots = slide.slots.deepCopy()
        val product = evidence.projectName
        var fatal = false

        fun clean(value: String): Boolean =
            StoryboardValidator.bannedMatches(value, emptySet(), product).isEmpty()

        fun walk(host: JsonObject, essential: Set<String>) {
            for (key in host.keySet().toList()) {
                val element = host.get(key)
                if (key !in VISIBLE) continue
                when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                        if (!clean(element.asString)) {
                            host.remove(key)
                            if (key in essential) fatal = true
                            issues += "Removed '$key' from a ${slide.layout} slide: it was written in engineering terms."
                        }
                    }
                    element.isJsonArray -> {
                        val survivors = JsonArray()
                        for (item in element.asJsonArray) {
                            when {
                                item.isJsonPrimitive && item.asJsonPrimitive.isString ->
                                    if (clean(item.asString)) survivors.add(item)
                                item is JsonObject -> {
                                    walk(item, setOf("title", "label", "action", "value"))
                                    // A card that lost its own title has nothing left to be.
                                    val hasSubject = item.has("title") || item.has("label") ||
                                        item.has("action") || item.has("value") || item.has("name")
                                    if (hasSubject) survivors.add(item)
                                }
                                else -> survivors.add(item)
                            }
                        }
                        if (survivors.size() == 0) {
                            host.remove(key)
                            if (key in essential) fatal = true
                        } else {
                            host.add(key, survivors)
                        }
                    }
                }
            }
        }

        walk(slots, essentialFor(slide.layout))
        if (fatal) return null

        /*
         * The notes are spoken to the same room, so they answer to the same rule. A note
         * that trips it is replaced rather than deleted: a half edited sentence read
         * aloud is worse than none, but handing a presenter a blank card is worse still,
         * and this slide is about to be in front of people either way.
         */
        val notes = slide.notes?.takeIf { clean(it) } ?: run {
            if (!slide.notes.isNullOrBlank()) {
                issues += "Replaced the speaker notes on a ${slide.layout} slide: they were written in engineering terms."
            }
            fallbackNotes(slide.layout)
        }
        return slide.copy(slots = slots, notes = notes)
    }

    /** The slot a layout cannot render without. Losing it means losing the slide. */
    private fun essentialFor(layout: String): Set<String> = when (layout) {
        SlideLayout.STATEMENT -> setOf("statement", "body")
        SlideLayout.PROBLEM -> setOf("body")
        SlideLayout.CAPABILITY_GRID -> setOf("cards")
        SlideLayout.STATS -> setOf("stats")
        SlideLayout.JOURNEY, SlideLayout.FLOW -> setOf("steps")
        SlideLayout.GAPS -> setOf("items")
        SlideLayout.AGENDA -> setOf("items")
        SlideLayout.CLOSING -> setOf("headline", "cta")
        else -> emptySet()
    }

    /** Never a blank card. Enough for someone holding the clicker to keep going. */
    private fun fallbackNotes(layout: String): String = when (layout) {
        SlideLayout.TITLE -> "Introduce the product and say who it is for."
        SlideLayout.AGENDA -> "Thirty seconds. It exists so nobody is wondering how long this will take."
        SlideLayout.PROBLEM -> "Start with the problem rather than the product."
        SlideLayout.STATEMENT -> "Let this one sit for a beat before moving on."
        SlideLayout.CAPABILITY_GRID -> "Take these one at a time, in the order they are on the slide."
        SlideLayout.FLOW -> "Walk left to right, one step at a time."
        SlideLayout.JOURNEY -> "Tell it as a story about a person, from the top down."
        SlideLayout.STATS -> "Every number here was counted rather than estimated."
        SlideLayout.GAPS -> "Say this out loud rather than skipping it. Naming what is missing is what makes the rest believable."
        SlideLayout.CLOSING -> "Stop here and take questions."
        else -> "Talk to what is on the slide."
    }

    private fun titleOnly(deck: Deck): Slide = Slide(
        SlideLayout.TITLE,
        JsonObject().apply {
            addProperty("productName", deck.title.ifBlank { deck.projectName })
            addProperty("tagline", deck.subtitle)
            addProperty("project", deck.projectName)
        },
        "There was not enough evidence in this project to build a full deck."
    )
}
