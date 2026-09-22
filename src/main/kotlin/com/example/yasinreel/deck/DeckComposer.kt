package com.example.yasinreel.deck

import com.example.yasinreel.llm.FallbackDirector
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.ProductModel
import com.example.yasinreel.model.RecapFacts
import com.example.yasinreel.model.Scene
import com.example.yasinreel.model.SceneTemplate
import com.example.yasinreel.model.Storyboard
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Builds a [Deck] for one audience. Deterministic, and deliberately so.
 *
 * The plan for this started with a third AI call, a deck director sitting beside the two
 * film directors. Writing it out made clear that would be a call added for its own sake.
 * [ProductModel] was already designed to serve two audiences: a capability carries both
 * a `userFacingName` with a `userBenefit` and a `technicalSummary`, and the model already
 * holds a problem statement, a target user, layers, flows, a stack, measurements and an
 * honest list of gaps. That is a deck outline. Projecting it costs no latency, adds no
 * new prompt to get wrong, and makes it impossible for the deck to contradict the film,
 * because both are reading the same understanding.
 *
 * The AI is still what makes this possible. It just already ran, in stage 2, and its
 * result is cached, which is why a deck arrives in about a second on a project whose
 * film has already been made.
 *
 * Two entry points, matching the film's two paths:
 *  - [fromModel] when stage 2 produced a [ProductModel]
 *  - [fromEvidence] when there was no key and nothing but harvested facts
 *
 * Both read [Evidence.recap]. When it is set the user did not ask what the product is,
 * they asked what happened to it between two dates, and those are different decks: a
 * progress update opens on the period rather than on the problem, and it leads with the
 * work instead of with the promise. Getting that wrong is worse than not offering ranges
 * at all, because a deck titled "what we built last week" that is really a product tour
 * is a deck that lies to the room it is shown in.
 */
object DeckComposer {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun fromModel(model: ProductModel, evidence: Evidence, audience: String): Deck {
        val stakeholder = audience == Audience.STAKEHOLDER
        val recap = evidence.recap
        val slides = mutableListOf<Slide>()

        slides += slide(SlideLayout.TITLE, obj {
            put("productName", model.productName)
            put("tagline", if (recap != null) periodHeadline(recap, stakeholder) else model.tagline)
            put("project", evidence.projectName)
            put("stamp", "${audienceLabel(audience, recap)}  ·  ${stampFor(recap)}")
        }, if (recap != null && stakeholder) {
            "This covers ${spoken(recap)} only. Everything after this slide is work that was actually finished in that window, not a plan for it."
        } else if (recap != null) {
            "This covers ${spoken(recap)} only. Everything on the slides after this was read off the commits in that window, not out of the project as a whole."
        } else if (stakeholder) {
            "Everything in this deck was worked out from the product itself, so every number on it is real rather than illustrative."
        } else {
            "This deck was generated from the codebase itself. Every number and every name on the following slides came out of the repository, not out of a template."
        })

        // Built last but inserted here, once the deck knows what it actually contains.
        val agendaAt = slides.size

        /*
         * The period takes the slot the problem statement holds in a product deck.
         *
         * Both are the "why are we here" slide, and a progress update already has its
         * answer: this is the window, and this is what landed in it. Keeping the problem
         * slide as well would open a fortnight's update by re-explaining the product to
         * people who have been funding it for a year.
         */
        if (recap != null) {
            slides += periodSlide(recap, stakeholder)
            // Raw commit subjects, for the audience that reads them daily. A stakeholder
            // gets the same period through the capabilities below, in product terms.
            if (!stakeholder) workSlide(recap)?.let { slides += it }
        } else {
            slides += problemSlide(model, stakeholder)
        }

        if (stakeholder) {
            slides += slide(SlideLayout.STATEMENT, obj {
                put("statement", if (recap != null) periodHeadline(recap, true) else model.tagline)
                put("attribution", if (recap != null) spoken(recap).replaceFirstChar { it.uppercase() } else "For ${model.targetUser}")
            }, if (recap != null) {
                "One line for what the period was for. Everything after this slide is evidence for it."
            } else {
                "This is the promise in one line. Everything after this slide is evidence for it."
            })
            journeySlide(model)?.let { slides += it }
        } else {
            architectureSlide(model)?.let { slides += it }
            flowSlide(model)?.let { slides += it }
            stackSlide(model)?.let { slides += it }
        }

        // One capability slide in an update, because the work slide above is already a
        // CAPABILITY_GRID and the validator allows two. Two product slides and no period
        // slide would be the wrong deck surviving the trim.
        capabilitySlides(model, stakeholder, recap).forEach { slides += it }
        // A product deck counts the whole project. An update already counted the period
        // on its own stats slide, and two sets of numbers on one deck invites the reader
        // to work out which of them is the real one.
        if (recap == null) statsSlide(model, evidence, stakeholder)?.let { slides += it }
        gapsSlide(model, stakeholder)?.let { slides += it }

        slides += slide(SlideLayout.CLOSING, obj {
            put("headline", when {
                recap != null -> periodHeadline(recap, stakeholder)
                stakeholder -> model.tagline
                else -> "${model.productName}, end to end"
            })
            put("stamp", "${evidence.projectName}  ·  generated by Nexus on ${LocalDate.now().format(STAMP)}")
        }, if (recap != null && stakeholder) {
            "Close here. Everything on this deck was read off the work itself ${spoken(recap)}, so any of it can be traced back to something that was really built."
        } else if (recap != null) {
            "Close here. Every claim on this deck came out of the commits between ${recap.since} and ${recap.until}, so any of it can be traced back to one."
        } else if (stakeholder) {
            "Close here. Everything shown was worked out from the product itself, so any of it can be traced back to the thing that was actually built."
        } else {
            "Close here. The deck and the video were both generated from this codebase, so anything on them can be traced back to a file."
        })

        slides.add(agendaAt, agendaFor(slides, stakeholder))

        return Deck(
            audience = audience,
            title = model.productName,
            subtitle = if (recap != null) periodHeadline(recap, stakeholder) else model.tagline,
            projectName = evidence.projectName,
            slides = slides.take(Caps.MAX_SLIDES)
        )
    }

    /**
     * The no-key path.
     *
     * Rather than a second set of rules for building slides out of raw facts, this
     * converts what [FallbackDirector] already produces. That director was written and
     * corrected against the one complaint that mattered most on this project, that a
     * stakeholder must never be shown a file path or the word endpoint, and rewriting
     * that judgement here would mean rediscovering it.
     */
    fun fromEvidence(evidence: Evidence, audience: String): Deck {
        val storyboard = FallbackDirector.direct(evidence, audience, 60_000)
        val stakeholder = audience == Audience.STAKEHOLDER
        val slides = storyboard.scenes.mapNotNull { fromScene(it, evidence, audience) }.toMutableList()

        val agendaAt = minOf(1, slides.size)
        if (slides.size >= Caps.MIN_SLIDES - 1) {
            slides.add(agendaAt, agendaFor(slides, stakeholder))
        }
        return Deck(
            audience = audience,
            title = storyboard.theme.projectName.ifBlank { evidence.projectName },
            // FallbackDirector already opens a recap with the period and follows it with the
            // commit subjects, so the slides are right; only the deck's own label is not.
            subtitle = evidence.recap
                ?.let { periodHeadline(it, stakeholder) }
                ?: evidence.readme?.let { firstSentence(it) }.orEmpty(),
            projectName = evidence.projectName,
            slides = slides.take(Caps.MAX_SLIDES)
        )
    }

    // ------------------------------------------------------------ model slides

    private fun problemSlide(model: ProductModel, stakeholder: Boolean): Slide =
        slide(SlideLayout.PROBLEM, obj {
            put("eyebrow", "The problem")
            put("heading", if (stakeholder) "Why this exists" else "What it solves")
            put("body", model.problemStatement)
            put("context", "Built for ${model.targetUser}.")
        }, "Open on the problem, not the product. ${model.problemStatement}")

    private fun architectureSlide(model: ProductModel): Slide? {
        val layers = model.architecture.layers.take(Caps.MAX_LAYERS)
        if (layers.isEmpty()) return null
        return slide(SlideLayout.ARCH_LAYERS, obj {
            put("eyebrow", "Architecture")
            put("heading", "How it is put together")
            put("layers", array(layers) { layer ->
                obj {
                    put("name", layer.name)
                    put("components", array(layer.components.take(Caps.MAX_COMPONENTS)) { component ->
                        primitive(component.tech?.takeIf { it.isNotBlank() }?.let { "${component.name} · $it" } ?: component.name)
                    })
                }
            })
        }, "Walk down the layers. " + layers.joinToString("; ") { "${it.name}: ${it.components.take(3).joinToString(", ") { c -> c.name }}" })
    }

    private fun flowSlide(model: ProductModel): Slide? {
        val flow = model.keyFlows.firstOrNull() ?: return null
        val steps = flow.steps.take(Caps.MAX_STEPS)
        if (steps.isEmpty()) return null
        return slide(SlideLayout.FLOW, obj {
            put("eyebrow", "One request, end to end")
            put("heading", flow.name)
            put("steps", array(steps) { step ->
                obj {
                    put("label", step.actor)
                    put("detail", step.action)
                }
            })
        }, "This is the path a single piece of work takes through the system: " +
            steps.joinToString(", then ") { "${it.actor} ${it.action}" } + ".")
    }

    private fun stackSlide(model: ProductModel): Slide? {
        val byCategory = model.techStack
            .filter { it.name.isNotBlank() }
            .groupBy { it.category.ifBlank { "Other" } }
        if (byCategory.isEmpty()) return null
        val groups = byCategory.entries.sortedByDescending { it.value.size }.take(Caps.MAX_GROUPS)
        return slide(SlideLayout.STACK, obj {
            put("eyebrow", "The stack")
            put("heading", "What it is built on")
            put("groups", array(groups) { entry ->
                obj {
                    put("category", entry.key)
                    put("items", array(entry.value.take(Caps.MAX_GROUP_ITEMS)) { primitive(it.name) })
                }
            })
        }, "The stack, and the reasons behind it. " +
            groups.flatMap { it.value }.take(3).joinToString(" ") { "${it.name}: ${it.why}" })
    }

    private fun journeySlide(model: ProductModel): Slide? {
        val flow = model.keyFlows.firstOrNull() ?: return null
        val steps = flow.steps.take(Caps.MAX_STEPS)
        if (steps.isEmpty()) return null
        return slide(SlideLayout.JOURNEY, obj {
            put("eyebrow", "What happens")
            put("heading", flow.name)
            put("steps", array(steps) { step ->
                obj {
                    put("actor", step.actor)
                    put("action", step.action)
                }
            })
        }, "Told as something a person does, not as something the software does: " +
            steps.joinToString(", then ") { "${it.actor} ${it.action}" } + ".")
    }

    /**
     * Capabilities, split across as many slides as it takes.
     *
     * Six to a slide, because seven small cards is a slide nobody reads and two slides of
     * four is a deck that keeps its pace.
     */
    private fun capabilitySlides(model: ProductModel, stakeholder: Boolean, recap: RecapFacts? = null): List<Slide> {
        val all = model.capabilities.filter { it.userFacingName.isNotBlank() }
        if (all.isEmpty()) return emptyList()
        val perSlide = if (all.size <= 6) all.size else 4
        // An update already spent one of its two grids on the work itself.
        val maxSlides = if (recap != null) 1 else 2
        return all.chunked(perSlide).take(maxSlides).mapIndexed { index, chunk ->
            slide(SlideLayout.CAPABILITY_GRID, obj {
                put("eyebrow", when {
                    recap != null -> "What it means"
                    index == 0 -> "What it does"
                    else -> "What it does, continued"
                })
                put("heading", when {
                    recap != null && stakeholder -> "What you can do that you could not before"
                    recap != null -> "What this period changed"
                    stakeholder -> "What you can do with it"
                    else -> "Capabilities"
                })
                put("cards", array(chunk) { capability ->
                    obj {
                        put("title", capability.userFacingName)
                        put("body", if (stakeholder) capability.userBenefit else capability.technicalSummary)
                    }
                })
            }, chunk.joinToString(" ") { "${it.userFacingName}: ${it.userBenefit}" })
        }
    }

    private fun statsSlide(model: ProductModel, evidence: Evidence, stakeholder: Boolean): Slide? {
        val facts = model.scaleFacts
            .filter { it.label.isNotBlank() && it.value.isNotBlank() }
            .take(Caps.MAX_STATS)
            .ifEmpty {
                // The model is allowed to have no measurements; the harvester always has some.
                listOfNotNull(
                    evidence.stats.totalFiles.takeIf { it > 0 }?.let { "Files" to group(it) },
                    evidence.stats.totalLines.takeIf { it > 0 }?.let { "Lines of source" to group(it) },
                    evidence.dependencies.size.takeIf { it > 0 }?.let { "Dependencies" to group(it) },
                    evidence.languages.size.takeIf { it > 0 }?.let { "Languages" to group(it) }
                ).take(Caps.MAX_STATS).map { (label, value) -> com.example.yasinreel.model.ScaleFact(label, value, null) }
            }
        if (facts.isEmpty()) return null
        return slide(SlideLayout.STATS, obj {
            put("eyebrow", "By the numbers")
            put("heading", if (stakeholder) "The size of what was built" else "The shape of it")
            put("stats", array(facts) { fact ->
                obj {
                    put("value", fact.value)
                    put("label", fact.label)
                }
            })
        }, "Every one of these was counted, not estimated: " +
            facts.joinToString(", ") { "${it.value} ${it.label.lowercase()}" } + ".")
    }

    /**
     * The gaps, which are the slide most decks leave out.
     *
     * This project's own charter says never to invent a relationship and to keep
     * unsupported results visible, and a deck that claims a system is finished when the
     * analysis found no tests is exactly that kind of invention. For a stakeholder the
     * same list is framed as what comes next, which is the honest reading of it.
     */
    private fun gapsSlide(model: ProductModel, stakeholder: Boolean): Slide? {
        val items = model.gaps.filter { it.isNotBlank() }.take(Caps.MAX_BULLETS)
        if (items.isEmpty()) return null
        return slide(SlideLayout.GAPS, obj {
            put("eyebrow", if (stakeholder) "What is next" else "Known gaps")
            put("heading", if (stakeholder) "Where this goes from here" else "What the analysis could not find")
            put("framing", if (stakeholder) {
                "These are the things the product does not do yet."
            } else {
                "Listed rather than hidden. Each one is something the harvest looked for and did not find."
            })
            put("items", array(items) { primitive(it) })
        }, "Say this out loud rather than skipping it. Being the person who names the gaps is what makes the rest of the deck believable.")
    }

    // ------------------------------------------------------------ period slides

    /**
     * The counted shape of the period, and the first thing the room wants.
     *
     * Four numbers, because [Caps.MAX_STATS] is four and because a fifth is the one
     * nobody reads. Work that is not committed yet does not get a tile: it is a caveat
     * rather than an achievement, so it goes into the sentence the presenter says, where
     * it is heard rather than skimmed. The one exception is a period that deleted
     * nothing, which frees the fourth tile for it.
     */
    private fun periodSlide(recap: RecapFacts, stakeholder: Boolean): Slide {
        /*
         * "Commits" is on the banned list, and rightly: it is a word about the tool, not
         * about the work. The stakeholder labels are not softer names for the same thing,
         * they are the same counts said in the room's own language, so the slide survives
         * the scrub intact rather than losing its numbers to it.
         */
        val tiles = buildList {
            add((if (stakeholder) "Separate changes" else "Commits") to group(recap.commits))
            add((if (stakeholder) "Parts of the product" else "Files touched") to group(recap.filesTouched))
            add((if (stakeholder) "New work written" else "Lines added") to "+${group(recap.linesAdded)}")
            when {
                recap.linesDeleted > 0 ->
                    add((if (stakeholder) "Old work removed" else "Lines removed") to "-${group(recap.linesDeleted)}")
                recap.uncommittedFiles > 0 ->
                    add((if (stakeholder) "Still in progress" else "Not landed yet") to group(recap.uncommittedFiles))
            }
        }.take(Caps.MAX_STATS)

        // Work in progress is a caveat rather than an achievement, so it is said out loud
        // instead of shown as a tile. The film's offline director makes the same call.
        val pending = when {
            recap.uncommittedFiles == 0 -> ""
            stakeholder -> " ${plural(recap.uncommittedFiles, "piece")} of this is still being worked on and is not finished, so say so."
            else -> " ${plural(recap.uncommittedFiles, "file")} of it is not committed yet, so say so."
        }
        val who = when {
            recap.authors.size == 1 -> " All of it by ${recap.authors.first()}."
            recap.authors.size > 1 -> " Across ${recap.authors.size} people."
            else -> ""
        }
        return slide(SlideLayout.STATS, obj {
            put("eyebrow", "The period")
            put("heading", readable(recap))
            put("stats", array(tiles) { (label, value) ->
                obj {
                    put("value", value)
                    put("label", label)
                }
            })
        }, "Every number here was counted off the record rather than estimated.$who$pending")
    }

    /**
     * What the commits themselves say, which beats anything this composer could infer.
     *
     * These are the developer's own words for the work. The film's offline director
     * reaches for exactly the same list for exactly the same reason, and a stakeholder
     * deck does not get this slide at all: a commit subject is written for the person who
     * will run `git log`, and the validator would strip most of them anyway.
     */
    private fun workSlide(recap: RecapFacts): Slide? {
        val subjects = recap.subjects.filter { it.isNotBlank() }.take(Caps.MAX_CARDS)
        if (subjects.isEmpty()) return null
        return slide(SlideLayout.CAPABILITY_GRID, obj {
            put("eyebrow", "In the commits")
            put("heading", "What was done")
            put("cards", array(subjects) { subject ->
                // No body on purpose: a commit subject is already the summary, and a grid
                // of one-line cards reads faster than a grid of half-filled ones.
                obj { put("title", DeckTheme.clip(subject, Caps.CARD_TITLE_ALONE)) }
            })
        }, "Newest first, in the words they were written in: " +
            subjects.take(3).joinToString("; ") + ".")
    }

    /** The headline a period deck carries, on the title, the closing and the deck itself. */
    private fun periodHeadline(recap: RecapFacts, stakeholder: Boolean): String =
        if (stakeholder) "What we shipped ${spoken(recap)}" else "What changed ${spoken(recap)}"

    /**
     * The range as a person would say it out loud, collapsing the parts that repeat.
     *
     * "between 1 and 22 September 2026" rather than "between 2026-09-01 and 2026-09-22",
     * because this text is read aloud in a meeting and spoken by the presenter, while the
     * ISO form stays on the stats slide heading where it can be checked against git.
     */
    private fun spoken(recap: RecapFacts): String {
        val from = date(recap.since)
        val to = date(recap.until)
        if (from == null || to == null) return "in this period"
        if (from == to) return "on ${from.dayOfMonth} ${month(to)} ${to.year}"
        val sameYear = from.year == to.year
        val sameMonth = sameYear && from.month == to.month
        val start = when {
            sameMonth -> "${from.dayOfMonth}"
            sameYear -> "${from.dayOfMonth} ${month(from)}"
            else -> "${from.dayOfMonth} ${month(from)} ${from.year}"
        }
        return "between $start and ${to.dayOfMonth} ${month(to)} ${to.year}"
    }

    /** The exact bounds, for the slide a reader is allowed to check against the history. */
    private fun readable(recap: RecapFacts): String = "${recap.since} to ${recap.until}"

    /** A recap is stamped with the period it covers; a product deck with the day it was made. */
    private fun stampFor(recap: RecapFacts?): String =
        if (recap == null) LocalDate.now().format(STAMP) else readable(recap)

    private fun date(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()

    private fun month(date: LocalDate): String = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun plural(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "${group(count)} ${noun}s"

    // --------------------------------------------------------- evidence slides

    /** Maps one film scene onto the slide layout that carries the same idea. */
    private fun fromScene(scene: Scene, evidence: Evidence, audience: String): Slide? {
        val slots = scene.slots.deepCopy()
        val notes = scene.narration.orEmpty()
        return when (scene.template) {
            SceneTemplate.TITLE -> {
                slots.addProperty("project", evidence.projectName)
                slots.addProperty("stamp", "${audienceLabel(audience)}  ·  ${LocalDate.now().format(STAMP)}")
                Slide(SlideLayout.TITLE, slots, notes)
            }
            SceneTemplate.BIG_STATEMENT -> Slide(SlideLayout.STATEMENT, slots, notes)
            SceneTemplate.STAT_GRID -> Slide(SlideLayout.STATS, slots, notes)
            SceneTemplate.CAPABILITY_CARDS -> Slide(SlideLayout.CAPABILITY_GRID, slots, notes)
            SceneTemplate.ARCH_LAYERS -> Slide(SlideLayout.ARCH_LAYERS, slots, notes)
            SceneTemplate.FLOW_TRACE -> Slide(SlideLayout.FLOW, slots, notes)
            SceneTemplate.JOURNEY -> Slide(SlideLayout.JOURNEY, slots, notes)
            SceneTemplate.OUTRO -> {
                slots.addProperty("stamp", "${evidence.projectName}  ·  generated by Nexus")
                Slide(SlideLayout.CLOSING, slots, notes)
            }
            else -> null
        }
    }

    // -------------------------------------------------------------- the agenda

    /**
     * Built from the deck rather than written for it, so it can never promise a slide
     * that is not there. That is also why it is inserted after everything else exists.
     */
    private fun agendaFor(slides: List<Slide>, stakeholder: Boolean): Slide {
        val items = slides
            .filter { it.layout != SlideLayout.TITLE && it.layout != SlideLayout.CLOSING }
            .mapNotNull { agendaLabel(it) }
            .distinct()
            .take(Caps.MAX_AGENDA)
        return slide(SlideLayout.AGENDA, obj {
            put("eyebrow", "Agenda")
            put("heading", if (stakeholder) "What we will cover" else "What this covers")
            put("items", array(items) { label -> obj { put("label", label) } })
        }, "Thirty seconds on this slide. It exists so nobody in the room is wondering how long this will take.")
    }

    private fun agendaLabel(slide: Slide): String? {
        val heading = slide.slots.get("heading")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        if (!heading.isNullOrEmpty()) return DeckTheme.clip(heading, Caps.AGENDA_ITEM)
        return when (slide.layout) {
            SlideLayout.PROBLEM -> "The problem"
            SlideLayout.STATEMENT -> "The promise"
            SlideLayout.CAPABILITY_GRID -> "What it does"
            SlideLayout.ARCH_LAYERS -> "Architecture"
            SlideLayout.FLOW -> "One request, end to end"
            SlideLayout.STATS -> "By the numbers"
            SlideLayout.STACK -> "The stack"
            SlideLayout.JOURNEY -> "What happens"
            SlideLayout.GAPS -> "What is next"
            else -> null
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun slide(layout: String, slots: JsonObject, notes: String): Slide =
        Slide(layout, slots, DeckTheme.clip(notes, Caps.NOTES))

    private fun obj(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

    private fun <T> array(items: Iterable<T>, map: (T) -> com.google.gson.JsonElement): JsonArray =
        JsonArray().apply { items.forEach { add(map(it)) } }

    private fun primitive(value: String): com.google.gson.JsonElement =
        com.google.gson.JsonPrimitive(value)

    private fun JsonObject.put(key: String, value: String?) {
        if (!value.isNullOrBlank()) addProperty(key, value.replace(Regex("\\s+"), " ").trim())
    }

    private fun JsonObject.put(key: String, value: JsonArray) {
        if (value.size() > 0) add(key, value)
    }

    private fun group(value: Int): String =
        value.toString().reversed().chunked(3).joinToString(",").reversed()

    private fun audienceLabel(audience: String, recap: RecapFacts? = null): String = when {
        recap != null && audience == Audience.STAKEHOLDER -> "Progress update"
        recap != null -> "Engineering update"
        audience == Audience.STAKEHOLDER -> "Stakeholder deck"
        else -> "Technical deck"
    }

    private fun firstSentence(readme: String): String {
        val clean = readme.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[!") && !it.startsWith("<") }
            .firstOrNull()
            .orEmpty()
        return DeckTheme.clip(clean.substringBefore(". ").removeSuffix("."), Caps.DECK_SUBTITLE)
    }
}
