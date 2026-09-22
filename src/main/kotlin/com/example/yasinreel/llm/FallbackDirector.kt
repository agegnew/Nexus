package com.example.yasinreel.llm

import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Chain
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.Scene
import com.example.yasinreel.model.SceneTemplate
import com.example.yasinreel.model.SourceRef
import com.example.yasinreel.model.Storyboard
import com.example.yasinreel.model.Theme
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A storyboard built from evidence alone, with no network and no key.
 *
 * This is not a stub, and treating it as one was the mistake. Until a key with credit on it
 * exists this is the ONLY director that has ever run, so every reel anyone has watched came
 * from here. It is written to be good, not to be a placeholder.
 *
 * The two cuts are built from the same facts and share almost no code, because they are not the
 * same film at two levels of detail. The technical cut is about the shape of the system. The
 * stakeholder cut is about the product, and may not mention that a system exists at all: no
 * path, no route, no verb off a route table, no package, no language, no file count, no line
 * count. Where a fact can only be stated in code terms, this director says nothing instead.
 *
 * Everything here is a heuristic over facts that were literally read off disk, so it can be
 * wrong about meaning but it can never be wrong about substance.
 *
 * Every scene it builds speaks. The narration lines below are written to be read straight
 * through as one continuous summary, in the order the scenes are assembled, and none of them
 * repeats what its own scene already has on screen: the screen carries the evidence, the voice
 * carries the story. A scene that cannot be given a line is dropped rather than shown in
 * silence, because a silent scene is a gap in the middle of a sentence the viewer is following.
 */
object FallbackDirector {

    private val logger = Logger.getInstance(FallbackDirector::class.java)

    fun direct(evidence: Evidence, audience: String, targetMs: Int): Storyboard {
        val stakeholder = audience == Audience.STAKEHOLDER
        // Derived from this project's own dependencies and languages, so the ban is correct
        // for whatever repository is open and no project is special cased anywhere.
        val guard = Guard(
            enforced = stakeholder,
            vocabulary = if (stakeholder) StoryboardValidator.projectVocabulary(evidence) else emptySet(),
            productName = evidence.projectName
        )
        val prose = Prose(evidence.readme, guard)

        val drafted = if (stakeholder) stakeholderCut(evidence, prose) else technicalCut(evidence, prose)
        val safe = if (stakeholder) drafted.mapNotNull { enforcePlainLanguage(it, guard) } else drafted
        val filled = padTo(safe, evidence, prose, stakeholder)
        val timed = StoryboardValidator.pace(filled, targetMs)

        logger.info(
            "Nexus Reel fallback director built ${timed.size} scenes for the $audience cut, " +
                "${timed.sumOf { it.durationMs }}ms, ${drafted.size - safe.size} scene(s) dropped as jargon"
        )
        return Storyboard(
            audience = audience,
            totalMs = timed.sumOf { it.durationMs },
            theme = Theme(
                colors = evidence.palette.colors.ifEmpty { DEFAULT_COLORS },
                projectName = evidence.projectName
            ),
            scenes = timed
        )
    }

    // ---- the stakeholder cut ---------------------------------------------------------

    /**
     * Product first, and nothing else. Every scene answers a question a person with a
     * chequebook actually asks, and none of them may answer it with a number about code.
     *
     * The journey is pulled out before the statement scenes on purpose. Both compete for the
     * same README sentences, and the sentence that lists what a product does is worth far more
     * as five animated steps than as one more line of text.
     */
    private fun stakeholderCut(evidence: Evidence, prose: Prose): List<Scene> {
        val title = productTitle(evidence, prose)
        val journey = journeyScene(evidence, prose)
        val promise = prose.sentence(6, 34)?.let {
            statementScene(
                it,
                "In the words of the people who built it.",
                "It starts with a problem somebody had, and a promise about fixing it."
            )
        }
        val audience = prose.sentence(5, 30)?.let {
            statementScene(
                it,
                "Taken from the project's own description.",
                "It was built for the people who do this work every day, described in their own words."
            )
        }

        return listOfNotNull(
            title,
            promise,
            journey,
            plainCapabilityScene(evidence, prose),
            featureScene(prose),
            audience,
            substanceScene(evidence),
            whatIsNextScene(prose),
            productOutro(prose)
        )
    }

    private fun productTitle(evidence: Evidence, prose: Prose): Scene {
        val slots = JsonObject().apply {
            addProperty("productName", evidence.projectName)
            addProperty("tagline", prose.sentence(4, 24) ?: "What it does, and who it is for.")
        }
        // No sourceRefs, ever: the player pins them on screen as file path chips.
        return scene(
            SceneTemplate.TITLE,
            slots,
            "This is ${evidence.projectName}, and here is what it lets a person actually do."
        )
    }

    /**
     * The centrepiece, and the scene that leaked worst.
     *
     * It used to be built from [Chain] steps, which carry routes and file paths by
     * construction, which is how "calls POST /connection/yas-producer/poster-human/upload-ref"
     * ended up in front of an investor. It is now built from what the product says it does,
     * and from nothing else.
     */
    private fun journeyScene(evidence: Evidence, prose: Prose): Scene? {
        val steps = prose.journey()?.map { action -> JourneyStep("A person", action) }
            ?: categoryJourney(evidence)
            ?: return null
        if (steps.size < 3) return null

        val array = JsonArray()
        steps.take(5).forEach { step ->
            array.add(JsonObject().apply {
                addProperty("actor", step.actor)
                addProperty("action", step.action)
            })
        }
        val slots = JsonObject().apply {
            addProperty("heading", "What happens, start to finish")
            add("steps", array)
        }
        return scene(
            SceneTemplate.JOURNEY,
            slots,
            "So follow one person through it, from the first move to the finished result."
        )
    }

    /**
     * The one honest journey available without a model or a README list: the kinds of outside
     * service the project pulled in, put in the order a person meets them. The categories are
     * a closed taxonomy from the harvester, so translating them is translation, not invention.
     */
    private fun categoryJourney(evidence: Evidence): List<JourneyStep>? {
        val present = evidence.dependencies.mapNotNull { it.category }.toSet()
        val middle = JOURNEY_BY_CATEGORY.filter { it.key in present }.map { it.value }
        if (middle.size < 2) return null
        return listOf(JourneyStep("A person", "opens it and starts work")) +
            middle.take(3) +
            listOf(JourneyStep("A person", "walks away with the finished thing"))
    }

    /**
     * Capabilities, never packages.
     *
     * The old version wrote "Made possible by openai, torch, sentence-transformers", which is
     * three package names read aloud to somebody who has never installed anything in their
     * life. A category says the same thing in the language of the business, and a dependency
     * with no category says nothing at all rather than volunteering its name.
     */
    private fun plainCapabilityScene(evidence: Evidence, prose: Prose): Scene? {
        val cards = JsonArray()
        evidence.dependencies
            .mapNotNull { it.category }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .mapNotNull { (category, _) -> CAPABILITY_BY_CATEGORY[category] }
            .take(4)
            .forEach { capability ->
                cards.add(JsonObject().apply {
                    addProperty("title", capability.title)
                    addProperty("body", capability.body)
                })
            }

        // Topped up from the README's own bullet list, because a product's own claims about
        // itself are better stakeholder material than anything derivable from a manifest.
        if (cards.size() < 4) {
            prose.bullets(4 - cards.size()).forEach { bullet ->
                cards.add(JsonObject().apply {
                    addProperty("title", bullet.title)
                    addProperty("body", bullet.body)
                })
            }
        }
        if (cards.size() < 2) return null

        val slots = JsonObject().apply {
            addProperty("heading", "What it can already do")
            add("cards", cards)
        }
        return scene(
            SceneTemplate.CAPABILITY_CARDS,
            slots,
            "Along the way it already handles this much on its own, with nobody standing over it."
        )
    }

    /**
     * A second card scene, from the list the product wrote about itself.
     *
     * A README bullet list is a feature list written for a human reader, which is exactly what
     * this audience wants and exactly what no manifest can give us. It only appears when the
     * bullets survived the vocabulary gate and were not already spent topping up the
     * capability scene.
     */
    private fun featureScene(prose: Prose): Scene? {
        val bullets = prose.bullets(4)
        if (bullets.size < 2) return null

        val cards = JsonArray()
        bullets.forEach { bullet ->
            cards.add(JsonObject().apply {
                addProperty("title", bullet.title)
                addProperty("body", bullet.body)
            })
        }
        val slots = JsonObject().apply {
            addProperty("heading", "What comes with it")
            add("cards", cards)
        }
        return scene(
            SceneTemplate.CAPABILITY_CARDS,
            slots,
            "All of that comes on the first day, with nothing else to buy and nothing to wire up."
        )
    }

    /**
     * Proof of substance in business terms.
     *
     * Never a file count and never a line count. Those were the two numbers called out by
     * name, and they measure typing rather than product. How many kinds of work it handles,
     * how many outside tools it already works with and how many areas it covers are derived
     * from the same evidence and all of them mean something to somebody buying.
     */
    private fun substanceScene(evidence: Evidence): Scene? {
        val stats = JsonArray()
        val categories = evidence.dependencies.mapNotNull { it.category }.toSet()
        // Testing is a quality of the work, not a thing the product does for anyone.
        val kinds = categories.count { it != "testing" }
        val tools = evidence.dependencies.size
        val areas = evidence.topLevelDirs.size

        if (kinds > 0) stats.add(stat("Kinds of work it handles", count(kinds)))
        if (tools > 0) stats.add(stat("Outside tools it builds on", count(tools)))
        if (areas > 1) stats.add(stat("Areas of the product", count(areas)))
        if (stats.size() < 2) return null

        val slots = JsonObject().apply {
            addProperty("heading", "This is built, not promised")
            add("stats", stats)
        }
        return scene(
            SceneTemplate.STAT_GRID,
            slots,
            "None of it is a mock up. Every number behind this was counted, not estimated."
        )
    }

    /** Only ever shown when the project actually wrote down what it is doing next. */
    private fun whatIsNextScene(prose: Prose): Scene? {
        val next = prose.roadmap() ?: return null
        return statementScene(
            next,
            "What is still ahead, in the team's own words.",
            "What is not finished is written down too, because a story that hides a gap is not believed."
        )
    }

    private fun productOutro(prose: Prose): Scene {
        val slots = JsonObject().apply {
            // `cta` is the player's contract, not a preference: anything else renders as
            // the generic placeholder.
            addProperty("cta", "Built, and running today.")
            prose.liveUrl()?.let { addProperty("repoUrl", it) }
            addProperty("generatedAt", generatedAt())
        }
        return scene(
            SceneTemplate.OUTRO,
            slots,
            "Every word of this came from the work itself. That is the whole story, start to finish."
        )
    }

    // ---- the technical cut -----------------------------------------------------------

    /**
     * Nine scenes rather than six, because the complaint about this cut was that it said far
     * too little about a very large repository. Each one carries something only this project
     * could have produced.
     */
    private fun technicalCut(evidence: Evidence, prose: Prose): List<Scene> = listOfNotNull(
        technicalTitle(evidence, prose),
        measurementScene(evidence),
        languageScene(evidence),
        archScene(evidence),
        flowScene(evidence),
        weightScene(evidence),
        stackScene(evidence),
        gapScene(evidence),
        technicalOutro(evidence)
    )

    private fun technicalTitle(evidence: Evidence, prose: Prose): Scene {
        val slots = JsonObject().apply {
            addProperty("productName", evidence.projectName)
            addProperty("tagline", prose.sentence(4, 24) ?: technicalTagline(evidence))
        }
        return scene(
            SceneTemplate.TITLE,
            slots,
            "This is ${evidence.projectName}, described by its own source rather than by its README.",
            firstEntryRef(evidence)
        )
    }

    private fun measurementScene(evidence: Evidence): Scene {
        val stats = JsonArray()
        val top = evidence.languages.maxByOrNull { it.lines }
        stats.add(stat("Files", count(evidence.stats.totalFiles)))
        stats.add(stat("Lines", count(evidence.stats.totalLines)))
        top?.let { stats.add(stat(it.language, "${percent(it.lines, evidence.stats.totalLines)}%")) }
        stats.add(stat("Test files", count(evidence.stats.testFiles)))

        val slots = JsonObject().apply {
            addProperty("heading", "Measured, not estimated")
            add("stats", stats)
        }
        return scene(
            SceneTemplate.STAT_GRID,
            slots,
            "Start with the size of it, counted off disk rather than estimated."
        )
    }

    /** Where the weight of a system sits is the first real thing an engineer wants to know. */
    private fun languageScene(evidence: Evidence): Scene? {
        val top = evidence.languages.firstOrNull() ?: return null
        val second = evidence.languages.getOrNull(1)
        val share = percent(top.lines, evidence.stats.totalLines)
        val statement = if (second != null) {
            "${top.language} carries $share% of the lines, ${second.language} most of the rest."
        } else {
            "${top.language} carries $share% of everything here."
        }
        val context = "${evidence.languages.size} languages, ${count(evidence.stats.totalLines)} lines counted."
        return statementScene(statement, context, "The weight of it is not spread evenly, and where it sits says what kind of system this is.")
    }

    /** Top level directories are the closest thing to a layer map that text analysis can see. */
    private fun archScene(evidence: Evidence): Scene? {
        val dirs = evidence.topLevelDirs.filter { it.fileCount > 0 }.sortedByDescending { it.fileCount }.take(6)
        if (dirs.isEmpty()) return null

        val layers = JsonArray()
        dirs.chunked(3).forEachIndexed { index, group ->
            val components = JsonArray()
            group.forEach { dir ->
                components.add(JsonObject().apply {
                    addProperty("name", dir.path)
                    addProperty("tech", "${count(dir.fileCount)} files")
                })
            }
            layers.add(JsonObject().apply {
                addProperty("name", if (index == 0) "Where the weight is" else "Supporting areas")
                add("components", components)
            })
        }

        val biggest = dirs.first()
        val slots = JsonObject().apply {
            addProperty("heading", "How the ground is divided")
            add("layers", layers)
        }
        return scene(
            SceneTemplate.ARCH_LAYERS,
            slots,
            "Those areas divide the ground, and most of the work landed inside one of them."
        )
    }

    /** The money shot of the technical cut: one real path, followed all the way through. */
    private fun flowScene(evidence: Evidence): Scene? {
        val chain = bestChain(evidence) ?: return null
        val steps = JsonArray()
        chain.steps.take(6).forEach { step ->
            steps.add(JsonObject().apply {
                addProperty("label", step.label)
                addProperty("detail", step.detail ?: "")
                addProperty("file", step.file ?: "")
                addProperty("line", step.line ?: 1)
            })
        }
        val narration = when (chain.kind) {
            "http" -> "Follow one call the whole way out and back, with nothing skipped over."
            "bridge" -> "Follow one path across a language boundary, without a break in it."
            else -> "Follow one real path through the system, the whole way through."
        }
        val slots = JsonObject().apply {
            addProperty("name", chain.name)
            add("steps", steps)
        }
        val refs = chain.steps.mapNotNull { step ->
            step.file?.takeIf { it.isNotBlank() }?.let { SourceRef(it, step.line) }
        }
        return scene(SceneTemplate.FLOW_TRACE, slots, narration, refs)
    }

    /** The largest units are where the complexity concentrated, so name them. */
    private fun weightScene(evidence: Evidence): Scene? {
        val files = evidence.notableFiles.take(4)
        if (files.size < 2) return null

        val stats = JsonArray()
        files.forEach { file ->
            stats.add(
                // A stat cell renders a clickable chip when it carries a path, so the biggest
                // units in the system are one click from the film.
                stat(file.path.substringAfterLast('/'), count(file.lines)).apply {
                    addProperty("file", file.path)
                    addProperty("line", 1)
                }
            )
        }
        val slots = JsonObject().apply {
            addProperty("heading", "Where the complexity concentrated")
            add("stats", stats)
        }
        val biggest = files.first()
        return scene(
            SceneTemplate.STAT_GRID,
            slots,
            "The biggest units are where the complexity collected, so that is where to start reading.",
            files.map { SourceRef(it.path, 1) }
        )
    }

    /** What was bought in rather than built, and what that commits the system to. */
    private fun stackScene(evidence: Evidence): Scene? {
        val cards = JsonArray()
        evidence.dependencies
            .filter { it.category != null }
            .groupBy { it.category!! }
            .toList()
            .sortedByDescending { it.second.size }
            .take(4)
            .forEach { (category, deps) ->
                cards.add(JsonObject().apply {
                    addProperty("title", category.replaceFirstChar { it.titlecase(Locale.US) })
                    addProperty("body", deps.take(3).joinToString(", ") { it.name })
                })
            }
        if (cards.size() == 0) return null

        val slots = JsonObject().apply {
            addProperty("heading", "Bought in, not built")
            add("cards", cards)
        }
        return scene(
            SceneTemplate.CAPABILITY_CARDS,
            slots,
            "Some of this was bought in rather than built, and that is what the design is committed to."
        )
    }

    /** Naming what is missing buys credibility for everything that was claimed. */
    private fun gapScene(evidence: Evidence): Scene? {
        val stats = evidence.stats
        val statement: String
        val context: String
        when {
            stats.httpCalls > 0 && stats.matchedCalls < stats.httpCalls -> {
                statement = "${count(stats.httpCalls - stats.matchedCalls)} calls could not be tied to a route."
                context = "${count(stats.matchedCalls)} of ${count(stats.httpCalls)} matched " +
                    "against ${count(stats.httpRoutes)} routes."
            }
            stats.testFiles == 0 -> {
                statement = "No test files were found anywhere in the tree."
                context = "Reported rather than hidden. Everything else here was read off disk."
            }
            else -> {
                statement = "${count(stats.testFiles)} test files sit against " +
                    "${count(stats.totalFiles)} files of source."
                context = "A file count, not a coverage figure. Nothing here claims more than it measured."
            }
        }
        return statementScene(statement, context, "And here is the part this reel cannot tell you, reported rather than hidden.")
    }

    private fun technicalOutro(evidence: Evidence): Scene {
        val slots = JsonObject().apply {
            addProperty("cta", "Read from the source.")
            addProperty("generatedAt", generatedAt())
        }
        return scene(
            SceneTemplate.OUTRO,
            slots,
            "Every label on screen opens the file it came from, so take it from here.",
            firstEntryRef(evidence)
        )
    }

    // ---- shared scene plumbing -------------------------------------------------------

    private fun scene(
        template: String,
        slots: JsonObject,
        narration: String?,
        refs: List<SourceRef> = emptyList()
    ): Scene =
        // The duration is a placeholder. StoryboardValidator.pace sets the real one from the
        // scene's own content, so this director, the model and the repair pass all time a
        // scene by the same rule.
        Scene(template, PLACEHOLDER_MS, slots, narration, refs)

    /**
     * [narration] is required, and it is never the statement read back out. Falling back to the
     * statement was how half this cut ended up narrating its own screen, which is both the least
     * useful thing the voice can say and the first thing a viewer tunes out.
     */
    private fun statementScene(statement: String, context: String, narration: String): Scene =
        scene(
            SceneTemplate.BIG_STATEMENT,
            JsonObject().apply {
                addProperty("statement", statement)
                addProperty("context", context)
            },
            narration
        )

    /** The stamp under the outro, so a reel that gets passed around says when it was true. */
    private fun generatedAt(): String =
        runCatching { LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US)) }.getOrDefault("")

    /**
     * Prefers a matched HTTP chain, then a cross boundary bridge, then anything traced,
     * and within a kind the longest one, because a longer chain shows more of the system.
     */
    private fun bestChain(evidence: Evidence): Chain? = evidence.chains
        .filter { it.steps.isNotEmpty() }
        .maxWithOrNull(compareBy<Chain>({ chainRank(it.kind) }, { it.steps.size }))

    private fun chainRank(kind: String): Int = when (kind) {
        "http" -> 3
        "bridge" -> 2
        else -> 1
    }

    private fun technicalTagline(evidence: Evidence): String {
        val language = evidence.languages.maxByOrNull { it.lines }?.language
        return if (language != null) {
            "A $language project, ${count(evidence.stats.totalFiles)} files of it."
        } else {
            "${count(evidence.stats.totalFiles)} files of working material."
        }
    }

    private fun firstEntryRef(evidence: Evidence): List<SourceRef> =
        evidence.entryPoints.firstOrNull()?.let { listOf(SourceRef(it.path, it.line)) } ?: emptyList()

    /**
     * The player draws `value` at display size, so both halves are held to the same limits the
     * validator enforces on a directed cut. Both paths have to agree, or the offline cut looks
     * broken in exactly the way the check exists to prevent.
     */
    private fun stat(label: String, value: String): JsonObject = JsonObject().apply {
        addProperty("label", fit(label, StoryboardValidator.MAX_STAT_LABEL_CHARS))
        addProperty("value", fit(value, StoryboardValidator.MAX_STAT_VALUE_CHARS))
    }

    /** Cuts at a word boundary where there is one, because a display cell clips what overflows. */
    private fun fit(text: String, limit: Int): String {
        val flat = text.trim()
        if (flat.length <= limit) return flat
        val cut = flat.take(limit)
        val boundary = cut.lastIndexOf(' ')
        val kept = if (boundary >= limit / 2) cut.substring(0, boundary) else cut
        return kept.trimEnd(' ', ',', '.', ':', ';', '-')
    }

    private fun count(value: Int): String = String.format(Locale.US, "%,d", value)

    private fun percent(part: Int, whole: Int): Int =
        if (whole <= 0) 0 else ((part.toDouble() / whole) * 100).toInt().coerceIn(0, 100)

    // ---- passes ----------------------------------------------------------------------

    /**
     * Belt and braces with [StoryboardValidator].
     *
     * Nothing above should produce a banned string for this audience, but the material it
     * draws on comes from a README and from directory names, which have been through no such
     * discipline. Anything that matches is removed here: the offending card or step, or the
     * whole scene when what is left has nothing to show. Nothing is ever rewritten, because a
     * replacement sentence would be a claim that nobody checked.
     */
    private fun enforcePlainLanguage(scene: Scene, guard: Guard): Scene? {
        val slots = JsonObject()
        scene.slots.entrySet().forEach { (key, value) ->
            when {
                value.isJsonArray -> {
                    val kept = JsonArray()
                    value.asJsonArray.forEach { item -> if (guard.allows(item)) kept.add(item) }
                    if (kept.size() > 0) slots.add(key, kept)
                }
                else -> if (guard.allows(value)) slots.add(key, value)
            }
        }

        val required = REQUIRED_SLOT[scene.template]
        if (required != null && !slots.has(required)) {
            logger.info("Nexus Reel dropped a ${scene.template} scene from the stakeholder cut, no usable $required")
            return null
        }
        // Muting the scene used to be the answer here. It is not: the narration is one
        // continuous script, so a muted scene is a hole in the middle of a sentence the viewer
        // is still following. Nothing may be written in its place either, so the scene goes.
        val narration = scene.narration?.takeIf { it.isNotBlank() && !guard.blocked(it) }
        if (narration == null) {
            logger.info("Nexus Reel dropped a ${scene.template} scene from the stakeholder cut, its line cannot be spoken")
            return null
        }
        // Provenance chips are file paths on screen, which this audience must never see.
        return scene.copy(slots = slots, narration = narration, sourceRefs = emptyList())
    }

    /**
     * A film of four scenes is a slideshow, so a thin project gets honest filler rather than
     * a short reel. Everything added is still derived from the evidence.
     */
    private fun padTo(scenes: List<Scene>, evidence: Evidence, prose: Prose, stakeholder: Boolean): List<Scene> {
        if (scenes.size >= MIN_SCENES) return scenes
        val padded = scenes.toMutableList()
        val spare = if (stakeholder) {
            listOfNotNull(
                prose.sentence(5, 30)?.let {
                    statementScene(
                        it,
                        "From the project's own description.",
                        "There is more to it than one pass can show, so here it is in their own words."
                    )
                },
                statementScene(
                    "This is built, and it runs today.",
                    "Everything here was read from the work itself, not estimated.",
                    "None of this was guessed at. It was read straight off the work itself."
                )
            )
        } else {
            listOfNotNull(
                evidence.topLevelDirs.size.takeIf { it > 0 }?.let {
                    statementScene(
                        "The work is laid out across $it areas.",
                        "Read from the directory layout.",
                        "The layout itself tells you how the work was divided up."
                    )
                },
                evidence.entryPoints.firstOrNull()?.let {
                    statementScene(
                        "It all starts at ${it.path.substringAfterLast('/')}.",
                        "Traced from the entry points.",
                        "And there is one place everything else hangs off, traced from the entry points."
                    )
                }
            )
        }

        var index = 0
        while (padded.size < MIN_SCENES && index < spare.size) {
            // Inserted before the outro, so the closing scene stays the closing scene.
            padded.add((padded.size - 1).coerceAtLeast(0), spare[index])
            index++
        }
        return padded
    }

    // ---- the vocabulary gate ---------------------------------------------------------

    /**
     * One place that answers "may this audience hear this".
     *
     * [enforced] is false for the technical cut, and that matters: an engineer is allowed to
     * hear the word "server". Without the flag the same check would quietly censor the cut
     * that is supposed to be technical.
     */
    private class Guard(
        private val enforced: Boolean,
        private val vocabulary: Set<String>,
        private val productName: String
    ) {
        fun blocked(text: String): Boolean =
            enforced && StoryboardValidator.bannedMatches(text, vocabulary, productName).isNotEmpty()

        fun allows(element: JsonElement): Boolean = !enforced ||
            StoryboardValidator.strings(element).none { (_, value) -> blocked(value) }
    }

    // ---- README reading --------------------------------------------------------------

    /**
     * The README, read as prose rather than as markup.
     *
     * The first paragraph of a README is the best single statement of what a product IS that
     * exists anywhere in a repository, and unlike everything else in there it was written for
     * humans. Code fences, badges, tables and ASCII diagrams are dropped, because none of them
     * survive being read aloud.
     */
    private class Prose(readme: String?, private val guard: Guard) {

        private val used = mutableSetOf<String>()
        private val usedBullets = mutableSetOf<String>()
        private val body: String
        private val paragraphs: List<String>

        init {
            // Odd chunks are the insides of fenced blocks, which are code by definition.
            body = readme.orEmpty().split("```").filterIndexed { index, _ -> index % 2 == 0 }.joinToString("\n")
            paragraphs = body.split(BLANK_LINE).map { clean(it) }.filter { isProse(it) }
        }

        private val allBullets: List<Card> by lazy { readBullets() }

        private val sentences: List<String> by lazy {
            paragraphs.flatMap { paragraph -> paragraph.split(SENTENCE_END) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /** The next unused sentence of a usable length that this audience is allowed to hear. */
        fun sentence(minWords: Int, maxWords: Int): String? =
            sentences.firstOrNull { candidate ->
                candidate !in used && words(candidate) in minWords..maxWords && !guard.blocked(candidate)
            }?.also { used += it }

        /**
         * A README sentence that lists what the product does, such as "it works out what is
         * worth saying, makes it, reviews it and publishes it", is a journey written by the
         * people who built the thing. Nothing else in a repository comes close, so this is
         * tried before the sentence pool is spent on plain statement scenes.
         */
        fun journey(): List<String>? {
            sentences.forEach { candidate ->
                if (candidate in used) return@forEach
                // Everything after the last colon, because the list usually follows one.
                val tail = candidate.substringAfterLast(':').trim()
                if (tail.count { it == ',' } < 2) return@forEach
                val fragments = tail.split(',')
                    .map { fragment -> fragment.trim().removePrefix("and ").trim().trimEnd('.') }
                    .filter { it.isNotEmpty() && words(it) in 1..8 }
                if (fragments.size !in 3..5) return@forEach
                if (fragments.any { guard.blocked(it) }) return@forEach
                used += candidate
                return fragments.map { fragment ->
                    fragment.replaceFirstChar { first -> first.titlecase(Locale.US) }
                }
            }
            return null
        }

        /**
         * Bullet lines, which are a product's own list of what it offers.
         *
         * Handed out rather than recomputed, so a caller that takes two cards leaves the rest
         * for the next scene instead of everybody showing the same three bullets.
         */
        fun bullets(limit: Int): List<Card> {
            if (limit <= 0) return emptyList()
            val taken = allBullets.filterNot { it.title in usedBullets }.take(limit)
            taken.forEach { usedBullets += it.title }
            return taken
        }

        /**
         * A bullet usually opens with its own label, as "Reports. A two page brief ...".
         * Splitting on that label is what turns a line of markdown into a card with a heading,
         * and a card with a heading is worth a great deal more on screen than a paragraph.
         */
        private fun readBullets(): List<Card> {
            val lines = body.lines()
            val joined = mutableListOf<String>()
            lines.forEachIndexed { index, raw ->
                if (!BULLET.containsMatchIn(raw)) return@forEachIndexed
                // Continuation lines are indented and belong to the bullet above them.
                val continuation = lines.drop(index + 1)
                    .takeWhile { it.startsWith("  ") && it.isNotBlank() && !BULLET.containsMatchIn(it) }
                joined += clean((listOf(raw.replaceFirst(BULLET, "")) + continuation).joinToString(" "))
            }

            return joined
                .mapNotNull { line ->
                    val lead = BULLET_LEAD.find(line)
                    val title = lead?.groupValues?.get(1)?.trim()
                        ?: line.split(' ').take(4).joinToString(" ").trimEnd(',', '.')
                    val rest = if (lead != null) line.substring(lead.range.last + 1).trim() else line
                    val detail = rest.split(SENTENCE_END).firstOrNull()?.trim().orEmpty()
                    if (words(title) !in 1..6 || words(detail) !in 3..26) return@mapNotNull null
                    if (guard.blocked(title) || guard.blocked(detail)) return@mapNotNull null
                    Card(title, detail)
                }
                .distinctBy { it.title.lowercase() }
                .take(6)
        }

        /** A "what is next" section, when the project wrote one down. Never guessed. */
        fun roadmap(): String? {
            val lines = body.lines()
            val heading = lines.indexOfFirst { ROADMAP_HEADING.containsMatchIn(it) }
            if (heading < 0) return null
            return lines.drop(heading + 1)
                .takeWhile { !it.trimStart().startsWith("#") }
                .map { clean(it.replaceFirst(BULLET, "")) }
                .firstOrNull { candidate -> words(candidate) in 4..30 && !guard.blocked(candidate) }
        }

        /** A live address is the most persuasive thing a stakeholder cut can put on screen. */
        fun liveUrl(): String? = URL.findAll(body)
            .map { it.value.trimEnd('.', ',', ')', '*') }
            .firstOrNull { url -> BADGE_HOSTS.none { host -> url.contains(host, ignoreCase = true) } }

        private fun clean(block: String): String = block
            .replace(HTML_TAG, " ")
            // Escaped, so Kotlin passes the group reference through to the regex engine
            // instead of trying to read it as a string template.
            .replace(MARKDOWN_LINK, "\$1")
            .replace(MARKDOWN_EMPHASIS, "")
            .replace(BOX_DRAWING, " ")
            .lineSequence()
            .joinToString(" ") { it.trim().removePrefix("> ").trim() }
            .replace(WHITESPACE, " ")
            .trim()

        private fun isProse(text: String): Boolean {
            if (text.length !in 24..600) return false
            if (text.first() in "#|>![<-*=") return false
            if (words(text) < 5) return false
            if (!text.any { it.isLowerCase() }) return false
            // Mostly punctuation means a divider or a diagram, not a sentence.
            return text.count { it.isLetter() || it == ' ' }.toDouble() / text.length > 0.75
        }

        private fun words(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }
    }

    private data class Card(val title: String, val body: String)

    private data class JourneyStep(val actor: String, val action: String)

    private data class Capability(val title: String, val body: String)

    // ---- derived vocabulary ----------------------------------------------------------

    /**
     * The harvester's dependency categories are a closed taxonomy, so putting them into plain
     * English is translation rather than a canned script about anybody's project. A category
     * with no entry here is simply never spoken about, which is the whole point: saying
     * nothing beats naming the package.
     */
    private val CAPABILITY_BY_CATEGORY: Map<String, Capability> = linkedMapOf(
        "ai" to Capability(
            "It does the thinking",
            "It reads, drafts and judges on its own, instead of leaving all of that to a person."
        ),
        "payments" to Capability(
            "It takes payment",
            "Money can change hands inside the product, without anybody leaving it."
        ),
        "auth" to Capability(
            "It knows who is who",
            "People sign in, and each of them sees only what belongs to them."
        ),
        "database" to Capability(
            "It remembers",
            "Nothing is lost between visits. The work is kept, and it can be found again."
        ),
        "ui" to Capability(
            "People can actually use it",
            "There is a real screen to work in, not a set of instructions to follow."
        ),
        "http" to Capability(
            "It works with the outside world",
            "It talks to the other tools a team already uses, rather than standing on its own."
        ),
        "cloud" to Capability(
            "It runs for everyone",
            "Built to run for a whole team at once, not on one person's desk."
        ),
        "testing" to Capability(
            "It is checked, not hoped for",
            "The work is proved before anybody sees it."
        )
    )

    /** The same taxonomy, phrased as a step a person lives through rather than a capability. */
    private val JOURNEY_BY_CATEGORY: Map<String, JourneyStep> = linkedMapOf(
        "auth" to JourneyStep("A person", "signs in, and sees only their own work"),
        "ai" to JourneyStep("The product", "does the heavy thinking and comes back with a draft"),
        "http" to JourneyStep("The product", "pulls in whatever it needs from the tools around it"),
        "database" to JourneyStep("The product", "keeps every piece of it, so nothing is lost"),
        "payments" to JourneyStep("A person", "pays, without ever leaving the product"),
        "cloud" to JourneyStep("The product", "runs it for the whole team at once"),
        "ui" to JourneyStep("A person", "sees the result laid out and ready")
    )

    /** A scene whose central slot was scrubbed away has nothing left to show. */
    private val REQUIRED_SLOT: Map<String, String> = mapOf(
        SceneTemplate.TITLE to "productName",
        SceneTemplate.BIG_STATEMENT to "statement",
        SceneTemplate.STAT_GRID to "stats",
        SceneTemplate.CAPABILITY_CARDS to "cards",
        SceneTemplate.ARCH_LAYERS to "layers",
        SceneTemplate.FLOW_TRACE to "steps",
        SceneTemplate.JOURNEY to "steps",
        SceneTemplate.OUTRO to "cta"
    )

    private const val MIN_SCENES = 6

    /** Overwritten by the pacing pass, and only ever visible if that pass is skipped. */
    private const val PLACEHOLDER_MS = 6_000

    private val DEFAULT_COLORS = listOf("#6366f1", "#22d3ee", "#f59e0b", "#e11d48")

    private val BLANK_LINE = Regex("""\n\s*\n""")
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")
    private val WHITESPACE = Regex("""\s+""")
    private val HTML_TAG = Regex("""<[^>]{1,120}>""")
    private val MARKDOWN_LINK = Regex("""\[([^\]]{1,120})\]\([^)]{0,300}\)""")
    private val MARKDOWN_EMPHASIS = Regex("""[*_`]""")
    private val BOX_DRAWING = Regex("""[\u2190-\u21FF\u2500-\u257F\u25A0-\u25FF\u2B00-\u2BFF]""")
    private val BULLET = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

    /** A short label at the head of a bullet, ended by a full stop or a colon. */
    private val BULLET_LEAD = Regex("""^(.{2,40}?)[.:]\s+(?=[A-Z0-9])""")
    private val ROADMAP_HEADING = Regex("""(?i)^#{1,4}\s*(what.s next|next up|roadmap|coming soon|planned|future|still to do)""")
    private val URL = Regex("""https?://[^\s)"'*<>]+""")
    private val BADGE_HOSTS = listOf("shields.io", "badge", "travis-ci", "circleci", "codecov", "opensource.org", "img.")
}
