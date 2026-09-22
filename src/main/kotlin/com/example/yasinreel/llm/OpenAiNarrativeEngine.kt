package com.example.yasinreel.llm

import com.example.yasinreel.model.Architecture
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Capability
import com.example.yasinreel.model.Component
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.FlowStep
import com.example.yasinreel.model.KeyFlow
import com.example.yasinreel.model.Layer
import com.example.yasinreel.model.ProductModel
import com.example.yasinreel.model.ScaleFact
import com.example.yasinreel.model.Scene
import com.example.yasinreel.model.SceneTemplate
import com.example.yasinreel.model.SourceRef
import com.example.yasinreel.model.Storyboard
import com.example.yasinreel.model.TechItem
import com.example.yasinreel.model.Theme
import com.example.yasinreel.settings.ApiKeyCandidate
import com.example.yasinreel.settings.KeyDiagnosis
import com.example.yasinreel.settings.ReelSettings
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The only [NarrativeEngine] implementation for now: OpenAI chat completions over OkHttp.
 *
 * Two deliberate asymmetries between the stages:
 *
 *  - understanding is a real tool loop. The model gets the harvested evidence and four
 *    callbacks, and keeps asking until it can support an answer. That is where the
 *    judgement is, so it gets the strong model and the round budget.
 *  - directing is one call with no tools. The output is constrained to a fixed template
 *    enum with typed slots, so a cheap model is enough and the result cannot be malformed
 *    in a way that breaks the player.
 *
 * Parsing is hand written rather than reflective because a field the model forgot must
 * become an empty list, not a null in a non-null Kotlin field two stages later.
 */
class OpenAiNarrativeEngine(private val settings: ReelSettings) : NarrativeEngine {

    private val logger = Logger.getInstance(OpenAiNarrativeEngine::class.java)
    private val gson = Gson()

    /**
     * The candidate that actually worked, remembered for the rest of the run.
     *
     * Without it every one of the eight tool rounds and both direct() calls would walk
     * the candidate list again, so a machine with a dead key first would pay that 401
     * ten times over. Volatile because the pipeline thread writes it and a later stage
     * on another thread may read it.
     */
    @Volatile
    private var proven: ApiKeyCandidate? = null

    /** Source labels only, never key values, and insertion ordered so the last one is the last tried. */
    private val triedSources = LinkedHashSet<String>()

    @Volatile
    private var lastError: String? = null

    /** What the honesty banner is built from. Safe to show a user: it carries no key material. */
    fun diagnosis(): KeyDiagnosis = KeyDiagnosis(triedSources.toList(), proven?.source, lastError)

    private val client = OkHttpClient.Builder()
        // A whole codebase understood in one request is a slow request. The read timeout
        // has to outlast a strong model thinking, or we throw away work that was going to
        // arrive; the connect timeout stays short because a dead network should fail fast
        // and drop us onto the fallback director.
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun understand(evidence: Evidence, tools: EvidenceTools, progress: (String) -> Unit): ProductModel {
        return try {
            understandLoop(evidence, tools, progress, strictSchema = true)
        } catch (e: SchemaRejectedException) {
            // Not every model id the key can see supports json_schema. Losing the schema is
            // survivable because the parser is tolerant; losing the run is not.
            logger.info("Nexus Reel falling back to json_object: ${e.message}")
            progress("Model rejected the strict schema, retrying with tolerant JSON")
            understandLoop(evidence, tools, progress, strictSchema = false)
        }
    }

    private fun understandLoop(
        evidence: Evidence,
        tools: EvidenceTools,
        progress: (String) -> Unit,
        strictSchema: Boolean
    ): ProductModel {
        val model = settings.understandingModel
        val messages = JsonArray()
        messages.add(textMessage("system", Prompts.UNDERSTAND_SYSTEM))
        messages.add(textMessage("user", understandUserPrompt(evidence)))

        for (round in 1..MAX_TOOL_ROUNDS) {
            progress(if (round == 1) "Reading the evidence" else "Looking deeper, round $round of $MAX_TOOL_ROUNDS")

            val payload = basePayload(model, messages).apply {
                add("tools", EvidenceTools.toolDefinitions())
                addProperty("tool_choice", "auto")
                add("response_format", if (strictSchema) productModelResponseFormat() else jsonObjectFormat())
            }
            val reply = assistantMessage(chat(payload))
            messages.add(reply)

            val toolCalls = reply.arrayOrNull("tool_calls")
            if (toolCalls == null || toolCalls.size() == 0) {
                progress("Writing the product model")
                return parseProductModel(reply.stringOrNull("content").orEmpty(), evidence)
            }

            for (element in toolCalls) {
                val call = element.asJsonObject
                val function = call.objectOrNull("function")
                val name = function?.stringOrNull("name").orEmpty()
                val arguments = function?.stringOrNull("arguments").orEmpty()
                progress("Inspecting the codebase: $name")
                logger.info("Nexus Reel tool call $name")
                messages.add(toolResultMessage(call.stringOrNull("id").orEmpty(), tools.execute(name, arguments)))
            }
        }

        // Out of rounds. Take the tools away and demand the answer, so a model that likes
        // browsing cannot spend the whole budget and return nothing.
        progress("Writing the product model")
        messages.add(textMessage("user", "You have used all available tool calls. Answer now with the JSON object only."))
        val payload = basePayload(model, messages).apply {
            add("response_format", if (strictSchema) productModelResponseFormat() else jsonObjectFormat())
        }
        val reply = assistantMessage(chat(payload))
        return parseProductModel(reply.stringOrNull("content").orEmpty(), evidence)
    }

    override fun direct(model: ProductModel, evidence: Evidence, audience: String, targetMs: Int): Storyboard {
        val system = if (audience == Audience.STAKEHOLDER) {
            Prompts.DIRECT_STAKEHOLDER_SYSTEM
        } else {
            Prompts.DIRECT_TECHNICAL_SYSTEM
        }

        // A recap is told to people who already know the product, so the overlay goes after the
        // audience prompt and overrides the "introduce the product" framing it opens with.
        val framed = if (evidence.recap != null) system + "\n" + Prompts.RECAP_OVERLAY else system

        val messages = JsonArray()
        messages.add(textMessage("system", framed + "\n" + Prompts.SHOW_THE_REAL_THING))
        messages.add(textMessage("user", directUserPrompt(model, evidence, audience, targetMs)))

        val payload = basePayload(settings.directingModel, messages).apply {
            add("response_format", jsonObjectFormat())
        }
        val reply = assistantMessage(chat(payload))
        return parseStoryboard(reply.stringOrNull("content").orEmpty(), evidence, audience, targetMs)
    }

    // ---- prompts -------------------------------------------------------------------

    private fun understandUserPrompt(evidence: Evidence): String = buildString {
        append("PROJECT: ").append(evidence.projectName).append('\n')
        append("Evidence ids are the keys and the `id` fields below. Cite them exactly as written.\n\n")
        append("EVIDENCE (JSON)\n")
        append(evidenceJson(evidence))
        append("\n\nProduce the product model as a single JSON object with these keys: productName, ")
        append("tagline, problemStatement, targetUser, confidence, capabilities, architecture, ")
        append("techStack, keyFlows, scaleFacts, gaps.\n")
    }

    private fun directUserPrompt(model: ProductModel, evidence: Evidence, audience: String, targetMs: Int): String {
        val allowed = SceneTemplate.ALL.filter { template ->
            when (audience) {
                Audience.STAKEHOLDER -> template !in SceneTemplate.TECHNICAL_ONLY
                else -> template !in SceneTemplate.STAKEHOLDER_ONLY
            }
        }
        val seconds = targetMs / 1000
        return buildString {
            append("AUDIENCE: ").append(audience).append('\n')
            evidence.recap?.let { recap ->
                append("RECAP RANGE: ").append(recap.since).append(" to ").append(recap.until)
                append(" (").append(recap.area).append(")\n")
                append("RECAP FACTS: ").append(recap.commits).append(" commits, ")
                append(recap.filesTouched).append(" files, +").append(recap.linesAdded)
                append(" -").append(recap.linesDeleted)
                if (recap.uncommittedFiles > 0) {
                    append(", ").append(recap.uncommittedFiles).append(" files not committed yet")
                }
                append('\n')
                if (recap.subjects.isNotEmpty()) {
                    append("WHAT THE COMMITS SAY, newest first:\n")
                    recap.subjects.forEach { append("  - ").append(it).append('\n') }
                }
                append('\n')
            }
            append("TARGET RUNTIME: ").append(seconds).append(" seconds (").append(targetMs).append(" ms)\n")
            append("Use ").append(suggestedSceneCount(targetMs)).append(" scenes, opening on `title` and closing on `outro`.\n\n")

            append("TEMPLATES ALLOWED FOR THIS AUDIENCE: ").append(allowed.joinToString(", ")).append('\n')
            append("Any other template name is rejected and the scene is dropped.\n")
            append(Prompts.SCENE_CATALOGUE)
            append(Prompts.STORYBOARD_SHAPE)

            append("\nPRODUCT MODEL (JSON)\n")
            append(gson.toJson(model))

            append("\n\nPROVENANCE. Real files and lines you may cite in sourceRefs:\n")
            append(provenanceBlock(evidence))
        }
    }

    /** Only the parts of the evidence a director needs: what is on disk, and where. */
    private fun provenanceBlock(evidence: Evidence): String = buildString {
        evidence.chains.take(3).forEach { chain ->
            append("chain ").append(chain.id).append(" (").append(chain.kind).append("): ").append(chain.name).append('\n')
            chain.steps.forEach { step ->
                append("  ").append(step.label)
                step.file?.let { append("  ").append(it).append(':').append(step.line ?: 1) }
                append('\n')
            }
        }
        evidence.entryPoints.take(6).forEach { append("entry point: ").append(it.path).append(':').append(it.line ?: 1).append('\n') }
        evidence.topLevelDirs.take(10).forEach { append("dir: ").append(it.path).append("  ").append(it.fileCount).append(" files\n") }
        append("stats: ").append(evidence.stats.totalFiles).append(" files, ")
            .append(evidence.stats.totalLines).append(" lines, ")
            .append(evidence.stats.testFiles).append(" test files\n")
    }

    private fun evidenceJson(evidence: Evidence): String {
        val full = gson.toJson(evidence)
        if (full.length <= MAX_EVIDENCE_CHARS) return full
        // The file heads and the README are the only unbounded parts, so they are what gets
        // cut. The counts and the traced chains, which every later claim cites, stay whole.
        val trimmed = evidence.copy(
            readme = evidence.readme?.take(4_000),
            notableFiles = evidence.notableFiles.take(6).map { it.copy(head = it.head.take(800)) }
        )
        return gson.toJson(trimmed)
    }

    /**
     * Seven scenes at a minute, not five.
     *
     * Five scenes across 60 seconds is twelve seconds a scene, which is the slideshow
     * the user complained about: the frame composes in about a second and then sits
     * still for eleven.
     */
    private fun suggestedSceneCount(targetMs: Int): Int = (targetMs / 7_000).coerceIn(7, 9)

    // ---- transport -----------------------------------------------------------------

    private fun basePayload(model: String, messages: JsonArray): JsonObject = JsonObject().apply {
        addProperty("model", model)
        add("messages", messages)
    }

    /**
     * One call, against whichever key works.
     *
     * A 401, 403 or 429 is a verdict on the key rather than on the run, so it moves to
     * the next candidate instead of ending the film. This is the incident that motivated
     * the whole round: a working key sat in one project's .env while an exhausted key in
     * another project's .env silently forced every run onto the offline director.
     */
    private fun chat(payload: JsonObject): JsonObject {
        val candidates = proven?.let { listOf(it) } ?: settings.apiKeyCandidates()
        if (candidates.isEmpty()) {
            lastError = null
            throw IllegalStateException("No OpenAI key was found.")
        }

        var lastAuthFailure: AuthFailure? = null
        for (candidate in candidates) {
            triedSources.add(candidate.source)
            try {
                val result = call(candidate.key, payload)
                if (proven == null) {
                    proven = candidate
                    // The only place a key ever reaches PasswordSafe, and only after a 2xx.
                    settings.rememberWorkingKey(candidate.key)
                }
                return result
            } catch (e: AuthFailure) {
                lastError = "${e.status}: ${e.detail}"
                lastAuthFailure = e
                // A stored key that has started being refused must not stay first forever.
                if (candidate.source == ReelSettings.SOURCE_KEYCHAIN) settings.forgetKey(candidate.key)
                logger.warn("Nexus Reel key from ${candidate.source} was refused with ${e.status}")
                continue
            } catch (e: SchemaRejectedException) {
                throw e
            } catch (e: Exception) {
                // Network, 5xx or malformed JSON: another key would fail the same way, so
                // burning the rest of the list would only slow the fall back down.
                lastError = e.message ?: e.javaClass.simpleName
                throw e
            }
        }
        throw lastAuthFailure ?: IllegalStateException("No OpenAI key worked.")
    }

    private fun call(key: String, payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $key")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA))
            .build()

        // The body is read inside `use` and the response is closed before anything is
        // decided about it, so no failure path can leak a connection.
        val (code, body) = client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }

        if (code !in 200..299) {
            val detail = apiErrorMessage(body)
            // Logged without the request, because the request carries the key.
            logger.warn("Nexus Reel OpenAI call failed with $code: $detail")
            if (code == 400 && (detail.contains("schema", ignoreCase = true) ||
                    detail.contains("response_format", ignoreCase = true))
            ) {
                throw SchemaRejectedException(detail)
            }
            if (code == 401 || code == 403 || code == 429) throw AuthFailure(code, detail)
            throw IllegalStateException("OpenAI returned $code: $detail")
        }

        return try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw IllegalStateException("OpenAI returned a response that was not JSON", e)
        }
    }

    /** The assistant turn, rebuilt from scratch so provider specific extras never go back up. */
    private fun assistantMessage(response: JsonObject): JsonObject {
        val choices = response.arrayOrNull("choices")
            ?: throw IllegalStateException("OpenAI returned no choices")
        if (choices.size() == 0) throw IllegalStateException("OpenAI returned no choices")
        val message = choices[0].asJsonObject.objectOrNull("message")
            ?: throw IllegalStateException("OpenAI returned a choice with no message")

        return JsonObject().apply {
            addProperty("role", "assistant")
            message.stringOrNull("content")?.let { addProperty("content", it) }
            message.arrayOrNull("tool_calls")?.let { add("tool_calls", it) }
        }
    }

    private fun textMessage(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun toolResultMessage(callId: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", "tool")
        addProperty("tool_call_id", callId)
        addProperty("content", content)
    }

    private fun apiErrorMessage(body: String): String = try {
        JsonParser.parseString(body).asJsonObject
            .objectOrNull("error")
            ?.stringOrNull("message")
            ?: body.take(400)
    } catch (e: Exception) {
        body.take(400)
    }

    // ---- response formats ----------------------------------------------------------

    private fun jsonObjectFormat(): JsonObject = JsonObject().apply {
        addProperty("type", "json_object")
    }

    private fun productModelResponseFormat(): JsonObject = JsonObject().apply {
        addProperty("type", "json_schema")
        add("json_schema", JsonObject().apply {
            addProperty("name", "product_model")
            addProperty("strict", true)
            add("schema", productModelSchema())
        })
    }

    private fun productModelSchema(): JsonObject {
        val confidence = schemaEnum("high", "medium", "low")
        val component = schemaObject("name" to schemaString(), "tech" to schemaNullableString())
        val layer = schemaObject("name" to schemaString(), "components" to schemaArray(component))
        val capability = schemaObject(
            "id" to schemaString(),
            "userFacingName" to schemaString(),
            "userBenefit" to schemaString(),
            "technicalSummary" to schemaString(),
            "evidence" to schemaArray(schemaString()),
            "confidence" to confidence
        )
        val flowStep = schemaObject(
            "actor" to schemaString(),
            "action" to schemaString(),
            "evidenceRef" to schemaNullableString()
        )
        return schemaObject(
            "productName" to schemaString(),
            "tagline" to schemaString(),
            "problemStatement" to schemaString(),
            "targetUser" to schemaString(),
            "confidence" to confidence,
            "capabilities" to schemaArray(capability),
            "architecture" to schemaObject(
                "layers" to schemaArray(layer),
                "dataStores" to schemaArray(schemaString())
            ),
            "techStack" to schemaArray(
                schemaObject("name" to schemaString(), "category" to schemaString(), "why" to schemaString())
            ),
            "keyFlows" to schemaArray(
                schemaObject("name" to schemaString(), "steps" to schemaArray(flowStep))
            ),
            "scaleFacts" to schemaArray(
                schemaObject(
                    "label" to schemaString(),
                    "value" to schemaString(),
                    "evidenceRef" to schemaNullableString()
                )
            ),
            "gaps" to schemaArray(schemaString())
        )
    }

    /** Strict mode wants every key required and no extras, so both are set here, not per call site. */
    private fun schemaObject(vararg properties: Pair<String, JsonObject>): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        addProperty("additionalProperties", false)
        add("properties", JsonObject().apply { properties.forEach { add(it.first, it.second) } })
        add("required", JsonArray().also { array -> properties.forEach { array.add(it.first) } })
    }

    private fun schemaString(): JsonObject = JsonObject().apply { addProperty("type", "string") }

    private fun schemaNullableString(): JsonObject = JsonObject().apply {
        add("type", JsonArray().also {
            it.add("string")
            it.add("null")
        })
    }

    private fun schemaArray(items: JsonObject): JsonObject = JsonObject().apply {
        addProperty("type", "array")
        add("items", items)
    }

    private fun schemaEnum(vararg values: String): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        add("enum", JsonArray().also { array -> values.forEach(array::add) })
    }

    // ---- parsing -------------------------------------------------------------------

    private fun parseProductModel(content: String, evidence: Evidence): ProductModel {
        val root = parseObject(content) ?: throw IllegalStateException("The model did not return a JSON object")

        val architecture = root.objectOrNull("architecture")
        return ProductModel(
            productName = root.stringOrNull("productName")?.ifBlank { null } ?: evidence.projectName,
            tagline = root.stringOrNull("tagline").orEmpty(),
            problemStatement = root.stringOrNull("problemStatement").orEmpty(),
            targetUser = root.stringOrNull("targetUser").orEmpty(),
            confidence = root.stringOrNull("confidence")?.ifBlank { null } ?: "medium",
            capabilities = root.objects("capabilities").mapIndexed { index, item ->
                Capability(
                    id = item.stringOrNull("id")?.ifBlank { null } ?: "cap-${index + 1}",
                    userFacingName = item.stringOrNull("userFacingName").orEmpty(),
                    userBenefit = item.stringOrNull("userBenefit").orEmpty(),
                    technicalSummary = item.stringOrNull("technicalSummary").orEmpty(),
                    evidence = item.strings("evidence"),
                    confidence = item.stringOrNull("confidence")?.ifBlank { null } ?: "medium"
                )
            },
            architecture = Architecture(
                layers = (architecture?.objects("layers") ?: emptyList()).map { layer ->
                    Layer(
                        name = layer.stringOrNull("name").orEmpty(),
                        components = layer.objects("components").map { component ->
                            Component(
                                name = component.stringOrNull("name").orEmpty(),
                                tech = component.stringOrNull("tech")
                            )
                        }
                    )
                },
                dataStores = architecture?.strings("dataStores") ?: emptyList()
            ),
            techStack = root.objects("techStack").map { item ->
                TechItem(
                    name = item.stringOrNull("name").orEmpty(),
                    category = item.stringOrNull("category").orEmpty(),
                    why = item.stringOrNull("why").orEmpty()
                )
            },
            keyFlows = root.objects("keyFlows").map { flow ->
                KeyFlow(
                    name = flow.stringOrNull("name").orEmpty(),
                    steps = flow.objects("steps").map { step ->
                        FlowStep(
                            actor = step.stringOrNull("actor").orEmpty(),
                            action = step.stringOrNull("action").orEmpty(),
                            evidenceRef = step.stringOrNull("evidenceRef")
                        )
                    }
                )
            },
            scaleFacts = root.objects("scaleFacts").map { fact ->
                ScaleFact(
                    label = fact.stringOrNull("label").orEmpty(),
                    value = fact.stringOrNull("value").orEmpty(),
                    evidenceRef = fact.stringOrNull("evidenceRef")
                )
            },
            gaps = root.strings("gaps")
        )
    }

    private fun parseStoryboard(content: String, evidence: Evidence, audience: String, targetMs: Int): Storyboard {
        val root = parseObject(content) ?: throw IllegalStateException("The director did not return a JSON object")

        val scenes = root.objects("scenes").mapNotNull { scene ->
            val template = scene.stringOrNull("template")?.trim().orEmpty()
            if (template !in SceneTemplate.ALL) {
                logger.warn("Nexus Reel dropped a scene with unknown template $template")
                return@mapNotNull null
            }
            if (!templateAllowed(template, audience)) {
                logger.warn("Nexus Reel dropped a $template scene, not allowed for the $audience cut")
                return@mapNotNull null
            }
            Scene(
                template = template,
                durationMs = scene.intOr("durationMs", DEFAULT_SCENE_MS).coerceIn(MIN_SCENE_MS, MAX_SCENE_MS),
                slots = scene.objectOrNull("slots") ?: JsonObject(),
                narration = scene.stringOrNull("narration")?.trim()?.ifBlank { null },
                sourceRefs = scene.objects("sourceRefs").mapNotNull { ref ->
                    val file = ref.stringOrNull("file")?.trim().orEmpty()
                    if (file.isEmpty()) null else SourceRef(file, ref.intOrNull("line"))
                }
            )
        }

        if (scenes.isEmpty()) throw IllegalStateException("The director returned no usable scenes")

        // Timed by the validator so the model path, the offline path and the repair
        // path all size a scene by one rule.
        val timed = StoryboardValidator.pace(scenes, targetMs)
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

    private fun templateAllowed(template: String, audience: String): Boolean = when (audience) {
        Audience.STAKEHOLDER -> template !in SceneTemplate.TECHNICAL_ONLY
        else -> template !in SceneTemplate.STAKEHOLDER_ONLY
    }

    /**
     * Models still fence JSON or wrap it in a sentence, and neither is worth failing a
     * 60 second run over, so the outermost braces are what gets parsed rather than the
     * whole reply.
     */
    private fun parseObject(content: String): JsonObject? {
        val text = content.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val candidate = if (start >= 0 && end > start) text.substring(start, end + 1) else text
        return try {
            JsonParser.parseString(candidate).asJsonObject
        } catch (e: Exception) {
            logger.warn("Nexus Reel could not parse the model response as JSON")
            null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
            try {
                it.asInt
            } catch (e: Exception) {
                null
            }
        }

    private fun JsonObject.intOr(key: String, fallback: Int): Int = intOrNull(key) ?: fallback

    private fun JsonObject.objects(key: String): List<JsonObject> =
        arrayOrNull(key)?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null } ?: emptyList()

    private fun JsonObject.strings(key: String): List<String> =
        arrayOrNull(key)?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()

    /** Typed accessors that return null on a wrong shaped value instead of throwing at us. */
    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    /** Signals that this model id will not take a json_schema response format. */
    private class SchemaRejectedException(message: String) : RuntimeException(message)

    /** A refusal that names the key rather than the request, so another candidate is worth trying. */
    private class AuthFailure(val status: Int, val detail: String) :
        RuntimeException("OpenAI returned $status: $detail")

    companion object {
        private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val MAX_TOOL_ROUNDS = 8
        private const val MAX_EVIDENCE_CHARS = 50_000
        private const val DEFAULT_SCENE_MS = 6_000

        /** One duration policy for the whole product, so a 25 second scene cannot come back. */
        private const val MIN_SCENE_MS = StoryboardValidator.MIN_SCENE_MS
        private const val MAX_SCENE_MS = StoryboardValidator.MAX_SCENE_MS

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val DEFAULT_COLORS = listOf("#6366f1", "#22d3ee", "#f59e0b", "#e11d48")
    }
}
