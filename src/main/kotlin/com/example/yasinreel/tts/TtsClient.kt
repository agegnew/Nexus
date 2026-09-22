package com.example.yasinreel.tts

import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Storyboard
import com.example.yasinreel.settings.ReelSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Stage 5: turns each scene's narration into one mp3 on disk.
 *
 * Two rules shape everything here.
 *
 * **The reel must never go silent as a failure.** No key, no credits, a dead network
 * or narration switched off all mean "this cut plays without voice", never an
 * exception and never a half broken run. Nothing in this class throws.
 *
 * **Voicing a cut twice must cost nothing.** Every clip is addressed by the sha256 of
 * what actually determines the audio (model, voice, and the narration text), so an
 * edited scene re-synthesises and the five untouched ones are read straight off disk.
 * That is what makes re-rendering during a demo free rather than a bill.
 *
 * The voice differs per audience on purpose: a warm read for the stakeholder cut and a
 * crisp one for the technical cut is the cheapest possible way to make the two films
 * feel like they were made for different rooms.
 */
class TtsClient(private val settings: ReelSettings) {

    private val logger = Logger.getInstance(TtsClient::class.java)
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        // Short connect so a dead network drops us to a silent reel quickly, long read
        // because a long paragraph genuinely takes a while to come back as audio.
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * One entry per scene that got audio, in scene order. Scenes with no narration, or
     * whose clip could not be produced, are simply absent.
     *
     * [relativeUrl] is `tts/<hash>.mp3`, relative to the player's own origin, so the
     * page can use it verbatim in an `<audio src>`. Whatever serves the reel has to map
     * that prefix onto `<project>/.idea/yasin-reel/tts/`.
     */
    data class SceneAudio(val sceneIndex: Int, val relativeUrl: String, val durationMs: Int?)

    /**
     * Voices the whole storyboard.
     *
     * @param projectPath the project base path; clips are cached under its `.idea`
     * @param progress narrative progress for the same status line the pipeline uses
     * @return the clips that exist, possibly empty, never null and never partial garbage
     */
    fun synthesise(storyboard: Storyboard, projectPath: String, progress: (String) -> Unit): List<SceneAudio> {
        if (!settings.narrationEnabled) {
            progress("Narration is switched off, so this cut plays silently.")
            return emptyList()
        }

        val spoken = storyboard.scenes
            .mapIndexed { index, scene -> index to scene.narration?.trim().orEmpty() }
            .filter { it.second.isNotEmpty() }
        if (spoken.isEmpty()) return emptyList()

        val dir = cacheDir(projectPath)
        if (dir == null) {
            progress("Nowhere to cache narration, so this cut plays silently.")
            return emptyList()
        }

        val model = settings.ttsModel
        val voice = voiceFor(storyboard.audience)
        val key = runCatching { settings.apiKey() }.getOrNull()

        val results = ArrayList<SceneAudio>(spoken.size)
        var reused = 0
        var made = 0
        // Set by the first failure that no retry can fix (no key, no credits, bad key).
        // Once that is known, the remaining scenes are served from cache only, because
        // clips that were already paid for are still worth playing.
        var fatal: String? = null

        for ((position, entry) in spoken.withIndex()) {
            val (sceneIndex, text) = entry
            val input = text.take(MAX_INPUT_CHARS)
            val hash = digest(model, voice, input)
            val file = dir.resolve("$hash.$FORMAT")

            if (isUsable(file)) {
                reused++
                results.add(SceneAudio(sceneIndex, relativeUrl(hash), durationMs(file)))
                continue
            }

            if (key.isNullOrBlank()) {
                fatal = fatal ?: "No API key, so this cut plays silently."
                continue
            }
            if (fatal != null) continue

            progress("Voicing scene ${position + 1} of ${spoken.size}")
            when (val outcome = speak(key, model, voice, input)) {
                is Outcome.Audio -> {
                    if (write(file, outcome.bytes)) {
                        made++
                        results.add(SceneAudio(sceneIndex, relativeUrl(hash), durationMs(file)))
                    }
                }
                is Outcome.Fatal -> {
                    fatal = outcome.reason
                    logger.info("Nexus Reel stopped voicing the ${storyboard.audience} cut: ${outcome.reason}")
                }
                is Outcome.Skip -> {
                    // One scene failing is not a reason to lose the other five, and the
                    // player already treats a missing clip as a silent scene.
                    logger.warn("Nexus Reel could not voice scene $sceneIndex: ${outcome.reason}")
                }
            }
        }

        report(progress, results.size, spoken.size, reused, made, fatal)
        return results
    }

    private fun report(
        progress: (String) -> Unit,
        voiced: Int,
        wanted: Int,
        reused: Int,
        made: Int,
        fatal: String?
    ) {
        if (voiced == 0) {
            progress(fatal ?: "Narration is unavailable, so this cut plays silently.")
            return
        }
        val detail = buildString {
            append("Narration ready for $voiced of $wanted scenes")
            if (reused > 0) append(", $reused reused from cache")
            if (made > 0) append(", $made newly voiced")
            append('.')
            if (fatal != null) append(" The rest are silent: $fatal")
        }
        progress(detail)
    }

    private sealed interface Outcome {
        /** The mp3 itself. */
        class Audio(val bytes: ByteArray) : Outcome

        /** This scene failed but the next one is worth trying. */
        class Skip(val reason: String) : Outcome

        /** Nothing further will succeed this run, so stop calling out. */
        class Fatal(val reason: String) : Outcome
    }

    private fun speak(key: String, model: String, voice: String, input: String): Outcome {
        val first = post(key, body(model, voice, input, withInstructions = supportsInstructions(model)))
        // A model id the key resolves to may predate the steering fields. Losing the
        // delivery note is survivable, losing the voice is not, so try again plainly.
        if (first is Outcome.Skip && first.reason.contains("instructions", ignoreCase = true)) {
            return post(key, body(model, voice, input, withInstructions = false))
        }
        return first
    }

    private fun post(key: String, payload: JsonObject): Outcome {
        val request = Request.Builder()
            .url(SPEECH_URL)
            .addHeader("Authorization", "Bearer $key")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code in 200..299) {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.size < MIN_CLIP_BYTES) {
                        Outcome.Skip("the service returned $code with no usable audio")
                    } else {
                        Outcome.Audio(bytes)
                    }
                } else {
                    // Read as text only on the error path, and never log the request:
                    // the request carries the key.
                    val detail = errorMessage(response.body?.string().orEmpty(), code)
                    logger.warn("Nexus Reel speech call failed with $code: $detail")
                    if (code in FATAL_CODES) Outcome.Fatal(fatalReason(code, detail)) else Outcome.Skip(detail)
                }
            }
        } catch (e: java.io.IOException) {
            // A network that is down now will still be down for scene four.
            Outcome.Fatal("the narration service could not be reached (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            logger.warn("Nexus Reel speech call failed unexpectedly", e)
            Outcome.Skip(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun body(model: String, voice: String, input: String, withInstructions: Boolean): JsonObject =
        JsonObject().apply {
            addProperty("model", model)
            addProperty("voice", voice)
            addProperty("input", input)
            addProperty("response_format", FORMAT)
            if (withInstructions) {
                addProperty("instructions", if (voice == STAKEHOLDER_VOICE) STAKEHOLDER_STYLE else TECHNICAL_STYLE)
            } else {
                // The older tts models take a rate instead of a delivery note. The reel
                // is deliberately short, so the read has to move.
                addProperty("speed", NARRATION_SPEED)
            }
        }

    /** The `instructions` field belongs to the gpt-4o speech family; tts-1 rejects it. */
    private fun supportsInstructions(model: String): Boolean = model.startsWith("gpt-")

    private fun fatalReason(code: Int, detail: String): String = when (code) {
        401, 403 -> "the API key was rejected, so this cut plays silently."
        429 -> if (detail.contains("credit", ignoreCase = true)) {
            "the account has no credits left, so this cut plays silently."
        } else {
            "the narration service is rate limiting this key, so this cut plays silently."
        }
        else -> "the narration service returned $code, so this cut plays silently."
    }

    private fun errorMessage(body: String, code: Int): String = runCatching {
        JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("error")
            ?.get("message")?.asString
    }.getOrNull() ?: body.take(200).ifBlank { "HTTP $code" }

    private fun voiceFor(audience: String): String =
        if (audience == Audience.STAKEHOLDER) STAKEHOLDER_VOICE else TECHNICAL_VOICE

    private fun relativeUrl(hash: String): String = "$URL_PREFIX/$hash.$FORMAT"

    private fun cacheDir(projectPath: String): Path? = runCatching {
        val dir = Path.of(projectPath, IDEA_DIR, WORK_DIR, TTS_DIR)
        Files.createDirectories(dir)
        dir
    }.onFailure { logger.warn("Nexus Reel could not open the narration cache", it) }.getOrNull()

    /** A zero length or truncated file is a cache miss, not a clip. */
    private fun isUsable(file: Path): Boolean =
        runCatching { Files.isRegularFile(file) && Files.size(file) >= MIN_CLIP_BYTES }.getOrDefault(false)

    /**
     * Written aside and moved, because an IDE killed mid-write would otherwise leave a
     * truncated mp3 under a hash that says it is complete, and every later run would
     * serve that silence forever.
     */
    private fun write(file: Path, bytes: ByteArray): Boolean = runCatching {
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(temp, bytes)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        true
    }.onFailure { logger.warn("Nexus Reel could not store a narration clip", it) }.getOrDefault(false)

    private fun digest(model: String, voice: String, input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Separated by a byte that cannot occur in any of the three, so no pair of
        // different inputs can ever concatenate into the same string.
        md.update(model.toByteArray(Charsets.UTF_8))
        md.update(SEPARATOR)
        md.update(voice.toByteArray(Charsets.UTF_8))
        md.update(SEPARATOR)
        md.update(input.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Length of an mp3, by walking its frame headers.
     *
     * Worth the fifty lines: the director sizes scenes before any audio exists, so the
     * only way the player can know a scene is shorter than what is said over it is if
     * the clip carries its own duration. Anything unexpected returns null, and the
     * player then falls back to the browser's own metadata.
     */
    private fun durationMs(file: Path): Int? =
        runCatching { scanDuration(Files.readAllBytes(file)) }.getOrNull()

    private fun scanDuration(bytes: ByteArray): Int? {
        var offset = id3Length(bytes)
        var samples = 0L
        var rate = 0
        var frames = 0

        while (offset + 4 <= bytes.size) {
            val header = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

            val frame = frameOf(header)
            if (frame == null) {
                // Tolerate a little junk before the first frame, but a stream that is
                // not framed the way we expect is not worth guessing at.
                if (frames > 0) break
                offset++
                if (offset > MAX_SYNC_SCAN) return null
                continue
            }
            samples += frame.samples
            rate = frame.rate
            frames++
            offset += frame.bytes
        }

        return if (frames < 2 || rate <= 0) null else ((samples * 1000L) / rate).toInt()
    }

    private class Frame(val bytes: Int, val samples: Int, val rate: Int)

    private fun frameOf(header: Int): Frame? {
        if ((header ushr 21 and 0x7FF) != 0x7FF) return null
        val versionBits = header ushr 19 and 0x3
        val layerBits = header ushr 17 and 0x3
        // Layer III only: it is what every speech endpoint returns, and supporting the
        // other two would be guesswork we can never test.
        if (versionBits == 1 || layerBits != 1) return null

        val mpeg1 = versionBits == 3
        val bitrateIndex = header ushr 12 and 0xF
        val rateIndex = header ushr 10 and 0x3
        if (bitrateIndex == 0 || bitrateIndex == 0xF || rateIndex == 3) return null

        val bitrate = (if (mpeg1) BITRATES_V1_L3 else BITRATES_V2_L3)[bitrateIndex] * 1000
        val base = SAMPLE_RATES[if (mpeg1) 0 else if (versionBits == 2) 1 else 2][rateIndex]
        val samples = if (mpeg1) 1152 else 576
        val padding = header ushr 9 and 0x1
        val length = (samples / 8) * bitrate / base + padding
        return if (length < 8) null else Frame(length, samples, base)
    }

    /** ID3v2 tags sit in front of the audio and are not frames, so step over them. */
    private fun id3Length(bytes: ByteArray): Int {
        if (bytes.size < 10) return 0
        if (bytes[0] != TAG_I || bytes[1] != TAG_D || bytes[2] != TAG_3) return 0
        // Syncsafe: four 7 bit groups, so a size can never look like a frame sync.
        var size = 0
        for (i in 6..9) size = (size shl 7) or (bytes[i].toInt() and 0x7F)
        return (size + 10).coerceAtMost(bytes.size)
    }

    companion object {
        private const val SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        private const val FORMAT = "mp3"

        /**
         * Warm for the room that is being sold to, crisp for the room that is being
         * briefed. Constants because this is a taste decision, not a fixed fact.
         */
        const val STAKEHOLDER_VOICE = "nova"
        const val TECHNICAL_VOICE = "onyx"

        private const val STAKEHOLDER_STYLE =
            "Warm, confident and human. You are showing someone what was built and why it matters to them. " +
                "Keep it brisk and conversational, never salesy, never technical."
        private const val TECHNICAL_STYLE =
            "Crisp, precise and matter of fact. You are briefing an engineer who is short on time. " +
                "Even pace, no drama, let the facts carry it."

        /** The reel targets about a minute, so the read has to move. Only used by tts-1 class models. */
        private const val NARRATION_SPEED = 1.05

        /** Where clips live, and how the player addresses them. Both halves of one contract. */
        private const val IDEA_DIR = ".idea"
        private const val WORK_DIR = "yasin-reel"
        const val TTS_DIR = "tts"
        const val URL_PREFIX = "tts"

        /** The speech endpoint's own input ceiling. */
        private const val MAX_INPUT_CHARS = 4000

        /** Anything smaller than this is not an mp3, whatever the status code said. */
        private const val MIN_CLIP_BYTES = 512

        /** Retrying these would only fail the same way for every remaining scene. */
        private val FATAL_CODES = setOf(401, 402, 403, 429)

        /** How far into a file we will hunt for the first frame before giving up. */
        private const val MAX_SYNC_SCAN = 8192

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Cannot occur in a model id, a voice or narration, so no two inputs can collide. */
        private const val SEPARATOR: Byte = 0

        private val TAG_I = 'I'.code.toByte()
        private val TAG_D = 'D'.code.toByte()
        private val TAG_3 = '3'.code.toByte()

        private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        private val SAMPLE_RATES = arrayOf(
            intArrayOf(44100, 48000, 32000), // MPEG 1
            intArrayOf(22050, 24000, 16000), // MPEG 2
            intArrayOf(11025, 12000, 8000)   // MPEG 2.5
        )
    }
}
