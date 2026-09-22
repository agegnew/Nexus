package com.example.yasinreel.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * One OpenAI key we could try, and where it came from.
 *
 * [source] is a human label ("keychain", "OPENAI_KEY", ".env of 42-studio") and is the
 * only half of this pair that is ever allowed near a log line, a progress message or the
 * player. [key] is a credential: it goes into an Authorization header and nowhere else.
 */
data class ApiKeyCandidate(val key: String, val source: String)

/**
 * What happened when the pipeline went looking for a key that works.
 *
 * This exists because of a real incident. A developer had a working key in one project's
 * `.env` and opened a different project whose `.env` held an exhausted key. The plugin
 * took the dead one, every AI call returned 429, the pipeline quietly degraded to the
 * offline director, and a perfectly nice video played. Nobody could tell the AI had never
 * run. A silent fallback that still produces output is the most dangerous failure mode in
 * this product, so the run now carries a verdict it can show on screen.
 *
 * [tried] holds source labels in the order they were attempted, never key values.
 * [workingSource] is the label of the key that actually completed a call, or null when
 * none did. [lastError] is the raw provider message from the final failure, kept for the
 * log and for classification, never shown raw to a non-technical viewer.
 */
data class KeyDiagnosis(
    val tried: List<String>,
    val workingSource: String?,
    val lastError: String?
) {

    /** True when a real model call succeeded, so the reel is AI written rather than harvested. */
    val aiRan: Boolean
        get() = workingSource != null

    /** Four words for a banner headline, before anyone reads the rest. */
    fun headline(): String = if (aiRan) "AI narration is on" else "The AI did not run"

    /**
     * The whole verdict as one sentence a tired non-technical reader gets right at 2am.
     *
     * Deliberately says "no credits" rather than "429", and names the place the key came
     * from rather than the key, because the remedy depends entirely on which of the two
     * `.env` files on the machine was picked up.
     */
    fun plainSentence(): String {
        if (aiRan) {
            return "The AI wrote this reel, using ${describeSource(workingSource!!)}."
        }
        val failing = tried.lastOrNull()
            ?: return "The AI could not run: no OpenAI key was found on this machine, " +
                "so the reel was built from the code alone."

        val reason = reasonFor(lastError)
        return if (tried.size > 1) {
            "The AI could not run: none of the ${tried.size} keys it found would work, " +
                "and the last one, ${describeSource(failing)}, $reason, " +
                "so the reel was built from the code alone."
        } else {
            "The AI could not run: ${describeSource(failing)} $reason, " +
                "so the reel was built from the code alone."
        }
    }

    /**
     * What to actually do about it, in one sentence.
     *
     * No settings screen is registered yet, so this never sends anyone to a Preferences
     * page that does not exist. Update it here, in one place, when one lands.
     */
    fun remedy(): String {
        if (aiRan) return ""
        val detail = lastError.orEmpty()
        return when {
            tried.isEmpty() ->
                "Put a key in this project's .env file as OPENAI_KEY, then build the cut again."
            detail.mentionsNoCredit() ->
                "That account is out of credit. Add credit to it, or put a different key in " +
                    "this project's .env file as OPENAI_KEY, then build the cut again."
            detail.mentionsRateLimit() ->
                "OpenAI asked us to slow down. Wait a moment, then build the cut again."
            detail.mentionsBadKey() ->
                "That key was refused. Replace it in this project's .env file as OPENAI_KEY, " +
                    "then build the cut again."
            detail.mentionsNetwork() ->
                "The connection to OpenAI did not get through. Check the network, then build the cut again."
            else ->
                "Put a working key in this project's .env file as OPENAI_KEY, then build the cut again."
        }
    }

    /** Both halves, for a caller that wants one string rather than a two line banner. */
    fun plainSummary(): String = listOf(plainSentence(), remedy()).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * Turns a source label into something that reads like English inside a sentence.
     *
     * The `.env of X` labels get the possessive treatment because "the key found in
     * 42-studio's .env file" is the phrasing that makes a reader realise the plugin took
     * a key from the wrong project, which is the failure this whole type exists for.
     */
    private fun describeSource(source: String): String = when {
        source == SOURCE_KEYCHAIN -> "the key saved in this IDE"
        source.startsWith(DOT_ENV_PREFIX) -> {
            val project = source.removePrefix(DOT_ENV_PREFIX).trim()
            if (project.isEmpty()) "the key found in a project's .env file"
            else "the key found in $project's .env file"
        }
        else -> "the key from the $source environment variable"
    }

    private fun reasonFor(error: String?): String {
        val text = error.orEmpty()
        return when {
            text.mentionsNoCredit() -> "has no credits remaining"
            text.mentionsRateLimit() -> "was rate limited, too many requests at once"
            text.mentionsBadKey() -> "was refused as not valid"
            text.mentionsNetwork() -> "could not be reached"
            else -> "did not work"
        }
    }

    private companion object {
        const val SOURCE_KEYCHAIN = ReelSettings.SOURCE_KEYCHAIN
        const val DOT_ENV_PREFIX = ReelSettings.DOT_ENV_SOURCE_PREFIX

        /**
         * Matched on the provider's words, not only on the status code, because the same
         * 429 covers both "out of money" and "slow down", and only the first is worth
         * telling a non-technical viewer about in those terms.
         */
        fun String.mentionsNoCredit(): Boolean = containsAnyIgnoringCase(
            "credit_balance_exhausted", "no credits", "insufficient_quota", "insufficient funds",
            "exceeded your current quota", "billing", "spending limit", "out of credit"
        )

        /** A 429 that is not about money is about pace, and the remedy is completely different. */
        fun String.mentionsRateLimit(): Boolean = containsAnyIgnoringCase(
            "rate limit", "rate_limit", "too many requests", "429"
        )

        fun String.mentionsBadKey(): Boolean = containsAnyIgnoringCase(
            "invalid_api_key", "incorrect api key", "invalid api key", "401",
            "unauthorized", "403", "permission", "does not have access", "revoked"
        )

        fun String.mentionsNetwork(): Boolean = containsAnyIgnoringCase(
            "unknownhost", "unable to resolve host", "connection refused", "connect timed out",
            "timeout", "timed out", "network is unreachable", "no route to host", "ssl"
        )

        fun String.containsAnyIgnoringCase(vararg needles: String): Boolean =
            needles.any { contains(it, ignoreCase = true) }
    }
}

/**
 * Configuration for Nexus Reel, application wide because a key and a model choice
 * belong to the developer, not to one checkout.
 *
 * Model ids and durations live in [State] and are serialised to `nexus-reel.xml`.
 * The OpenAI key never goes there: it is held by IntelliJ's [PasswordSafe], which
 * means the keychain on macOS and the encrypted store elsewhere. Keeping the two
 * apart is why a settings file can be shared or committed by accident without
 * leaking anything.
 *
 * Key resolution follows one rule, learned the hard way: **the first key that works
 * wins, not the first key that is found.** Everything below serves that rule. The
 * settings object offers every key it can see, in order of how much it trusts them; the
 * engine is what decides which one is real, by making a call; and only a key that has
 * proved itself is ever written to the keychain.
 */
@Service(Service.Level.APP)
@State(name = "NexusReelSettings", storages = [Storage("nexus-reel.xml")])
class ReelSettings : PersistentStateComponent<ReelSettings.State> {

    private val logger = Logger.getInstance(ReelSettings::class.java)
    private var internalState = State()

    /** Mutable holder; every field must be a `var` with a default for XML serialisation. */
    class State {
        var understandingModel: String = DEFAULT_UNDERSTANDING_MODEL
        var directingModel: String = DEFAULT_DIRECTING_MODEL
        var ttsModel: String = DEFAULT_TTS_MODEL
        var targetMs: Int = DEFAULT_TARGET_MS
        var narrationEnabled: Boolean = true
    }

    override fun getState(): State = internalState

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, internalState)
    }

    /** Stage 2. The only stage where the quality of judgement decides whether the film is right. */
    var understandingModel: String
        get() = internalState.understandingModel.ifBlank { DEFAULT_UNDERSTANDING_MODEL }
        set(value) {
            internalState.understandingModel = value.trim().ifBlank { DEFAULT_UNDERSTANDING_MODEL }
        }

    /** Stage 3. Constrained slot filling, run twice per project, so a cheaper tier is enough. */
    var directingModel: String
        get() = internalState.directingModel.ifBlank { DEFAULT_DIRECTING_MODEL }
        set(value) {
            internalState.directingModel = value.trim().ifBlank { DEFAULT_DIRECTING_MODEL }
        }

    /** Stage 5. */
    var ttsModel: String
        get() = internalState.ttsModel.ifBlank { DEFAULT_TTS_MODEL }
        set(value) {
            internalState.ttsModel = value.trim().ifBlank { DEFAULT_TTS_MODEL }
        }

    /** Requested runtime of one cut, in milliseconds. */
    var targetMs: Int
        get() = internalState.targetMs.coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
        set(value) {
            internalState.targetMs = value.coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
        }

    /** When false the reel plays silently, which is also the no-key path. */
    var narrationEnabled: Boolean
        get() = internalState.narrationEnabled
        set(value) {
            internalState.narrationEnabled = value
        }

    /**
     * Every key this machine can offer, most trustworthy first, de-duplicated by value.
     *
     * The order is the whole point:
     *
     *  1. the keychain, because a key gets in there only by having worked,
     *  2. `OPENAI_KEY`, the variable a developer exports on purpose for this plugin,
     *  3. `OPENAI_API_KEY`, which most other tooling on the machine also sets,
     *  4. the `.env` of each open project, **last**, because a key sitting in whatever
     *     repository happens to be open is the most likely of all of these to be stale,
     *     exhausted or simply somebody else's.
     *
     * Returning a list instead of one key is what lets the engine walk past a key that
     * comes back 401 or 429 instead of failing the whole run on it.
     */
    fun apiKeyCandidates(): List<ApiKeyCandidate> {
        val found = ArrayList<ApiKeyCandidate>(4)

        storedKey()?.let { found.add(ApiKeyCandidate(it, SOURCE_KEYCHAIN)) }
        System.getenv("OPENAI_KEY").orNull()?.let { found.add(ApiKeyCandidate(it, "OPENAI_KEY")) }
        System.getenv("OPENAI_API_KEY").orNull()?.let { found.add(ApiKeyCandidate(it, "OPENAI_API_KEY")) }
        found.addAll(dotEnvCandidates())

        // First occurrence wins, which is why the list is built in trust order: a key that
        // appears both in the keychain and in some project's .env keeps the better label,
        // so a later diagnosis blames the right place.
        val seen = HashSet<String>()
        val unique = found.filter { seen.add(it.key) }

        if (unique.isEmpty()) {
            logger.info("Nexus Reel found no OpenAI key in any source")
        } else {
            logger.info("Nexus Reel key candidates, in order: ${unique.joinToString(", ") { it.source }}")
        }
        return unique
    }

    /** The source labels alone, for a log line or a progress message that must not carry secrets. */
    fun candidateSources(): List<String> = apiKeyCandidates().map { it.source }

    /**
     * The first candidate, for callers that only ever wanted one key.
     *
     * Kept so nothing breaks, but it is the weaker contract: "first found" is exactly the
     * rule that picked a dead key once already. Anything that makes a network call should
     * use [apiKeyCandidates] and try them in turn.
     */
    fun apiKey(): String? = apiKeyCandidates().firstOrNull()?.key

    /** True when any source can produce a key, used to pick the fallback director without a network call. */
    fun hasApiKey(): Boolean = apiKeyCandidates().isNotEmpty()

    /**
     * Promotes a key to the keychain because it has **proved** it works.
     *
     * The old code adopted the first key it found, which would have burned an exhausted
     * key into the keychain permanently and made the bad state follow the developer into
     * every other project. Proof is a completed API call and nothing less, so only the
     * engine may call this, and only after a 2xx.
     */
    fun rememberWorkingKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        if (trimmed == storedKey()) return // Already ours; no reason to touch the keychain again.
        logger.info("Nexus Reel remembered a key that completed a call; it will be tried first from now on")
        setApiKey(trimmed)
    }

    /**
     * Drops [key] from the keychain if that is what is stored there.
     *
     * Without this a key that starts coming back 401 or 429 stays first in the candidate
     * order forever, and every run pays a failed request before moving on. Forgetting it
     * costs nothing: any still-live source will offer it again next time.
     */
    fun forgetKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        if (trimmed != storedKey()) return
        logger.info("Nexus Reel forgot the stored key after it was refused")
        setApiKey(null)
    }

    /** A null or blank key clears the stored credential rather than storing an empty one. */
    fun setApiKey(key: String?) {
        val trimmed = key?.trim()
        val credentials = if (trimmed.isNullOrEmpty()) null else Credentials(CREDENTIAL_USER, trimmed)
        try {
            PasswordSafe.instance.set(credentialAttributes(), credentials)
        } catch (e: Exception) {
            logger.warn("Nexus Reel could not store the key", e)
        }
    }

    private fun storedKey(): String? = try {
        PasswordSafe.instance.getPassword(credentialAttributes()).orNull()
    } catch (e: Exception) {
        // A locked or unavailable keychain must degrade to the remaining candidates,
        // never take the whole pipeline down.
        logger.warn("Nexus Reel could not read the stored key, continuing with the other sources", e)
        null
    }

    /**
     * Keys from the `.env` at the root of each open project, in project order.
     *
     * Reading these at all is deliberate: during a hackathon the key genuinely lives in a
     * `.env`, and without this the plugin only works in the shell that exported it. Ranking
     * them last is equally deliberate, for the same reason. A repository someone cloned
     * this morning is the likeliest place on the machine to find a key that used to work.
     *
     * Values are used as credentials and are never logged, never harvested, and never
     * reach the evidence the model is shown.
     */
    private fun dotEnvCandidates(): List<ApiKeyCandidate> {
        val projects = runCatching { ProjectManager.getInstance().openProjects }.getOrNull() ?: return emptyList()
        val candidates = ArrayList<ApiKeyCandidate>(2)

        for (project in projects) {
            val base = project.basePath ?: continue
            val env = java.io.File(base, ".env")
            if (!env.isFile || env.length() > MAX_DOT_ENV_BYTES) continue

            val lines = runCatching { env.readLines() }.getOrNull() ?: continue
            val byName = LinkedHashMap<String, String>()
            for (line in lines) {
                val trimmed = line.trim().removePrefix("export ").trim()
                if (trimmed.startsWith("#") || !trimmed.contains('=')) continue
                val name = trimmed.substringBefore('=').trim()
                if (name != "OPENAI_KEY" && name != "OPENAI_API_KEY") continue
                val value = trimmed.substringAfter('=').trim().trim('"', '\'').orNull() ?: continue
                byName.putIfAbsent(name, value)
            }
            if (byName.isEmpty()) continue

            // OPENAI_KEY before OPENAI_API_KEY inside one file, matching the environment order.
            val label = DOT_ENV_SOURCE_PREFIX + project.name
            byName["OPENAI_KEY"]?.let { candidates.add(ApiKeyCandidate(it, label)) }
            byName["OPENAI_API_KEY"]?.let { candidates.add(ApiKeyCandidate(it, label)) }
            logger.info("Nexus Reel saw a key in the .env of ${project.name}")
        }
        return candidates
    }

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName(CREDENTIAL_SUBSYSTEM, CREDENTIAL_USER))

    private fun String?.orNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Largest `.env` we will scan for a key, so a stray huge file cannot stall startup. */
        const val MAX_DOT_ENV_BYTES = 64L * 1024

        /** Source labels. Public because [KeyDiagnosis] renders sentences out of them. */
        const val SOURCE_KEYCHAIN = "keychain"
        const val DOT_ENV_SOURCE_PREFIX = ".env of "

        // Verified present on the hackathon key via GET /v1/models. Understanding is the
        // only stage where judgement decides whether the film is right, so it gets the
        // flagship; directing only fills typed slots, so it gets a mini.
        const val DEFAULT_UNDERSTANDING_MODEL = "gpt-5.5"
        const val DEFAULT_DIRECTING_MODEL = "gpt-5.4-mini"
        const val DEFAULT_TTS_MODEL = "gpt-4o-mini-tts"

        /**
         * One minute, because that is how long anyone actually watches.
         *
         * The 90 second default read as slow: scenes sat still long enough for the screen
         * to look empty. Shorter is not a compromise here, it is the edit.
         */
        const val DEFAULT_TARGET_MS = 60_000
        const val MIN_TARGET_MS = 20_000
        const val MAX_TARGET_MS = 300_000

        private const val CREDENTIAL_SUBSYSTEM = "Nexus Reel"
        private const val CREDENTIAL_USER = "openai-api-key"

        fun getInstance(): ReelSettings = service()
    }
}
