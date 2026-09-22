package com.example.yasinreel

import com.example.yasinreel.llm.FallbackDirector
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.Gson
import com.google.gson.JsonElement
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Guards the failure that shipped: a stakeholder cut full of routes, package names and
 * line counts. It runs against a real harvest when one is on this machine, because the
 * synthetic cases never reproduced what the real repo did.
 *
 * The bad output that motivated this:
 *   journey step   "calls POST /connection/yas-producer/poster-human/upload-ref"
 *   capability     "Made possible by openai, torch, sentence-transformers."
 *   stat           "Lines written: 194,156"
 */
class StakeholderCutTest {

    private val gson = Gson()

    /** Any harvest left behind by a real run. Skipped when none exists. */
    private fun realHarvests(): List<File> =
        File(System.getProperty("user.home"), "Desktop")
            .listFiles()
            ?.mapNotNull { File(it, ".idea/yasin-reel/evidence.json").takeIf(File::isFile) }
            .orEmpty()

    @Test
    fun `stakeholder cut names no route, file, package or line count`() {
        val harvests = realHarvests()
        Assume.assumeTrue("no harvested evidence.json on this machine", harvests.isNotEmpty())

        harvests.forEach { file ->
            val evidence = gson.fromJson(file.readText(), Evidence::class.java)
            val cut = FallbackDirector.direct(evidence, Audience.STAKEHOLDER, 60_000)
            val report = StoryboardValidator.validate(cut, evidence, evidence.projectPath, 60_000)

            println("\n=== ${evidence.projectName} (${evidence.stats.totalFiles} files) ===")
            println(report.describe())
            cut.scenes.forEachIndexed { i, s ->
                println("  [$i] ${s.template} ${s.durationMs}ms  ${s.narration?.take(90)}")
                println("      ${s.slots}")
            }

            val text = visibleText(cut.scenes.map { it.slots } + emptyList()) +
                cut.scenes.mapNotNull { it.narration }.joinToString(" ")

            OFFENDERS.forEach { (label, pattern) ->
                val hit = pattern.find(text)
                assertTrue(
                    "${evidence.projectName} stakeholder cut leaked $label: '${hit?.value}'",
                    hit == null
                )
            }
            assertTrue("${evidence.projectName}: validator found ${report.violations}", report.ok)
        }
    }

    /**
     * Guards the second failure the user hit: a film that only talks in four of its nine
     * scenes, so the voice arrives as a caption over a title card and then stops. The
     * voiceover is meant to be one continuous spoken summary, which means every scene of
     * both cuts carries its share of it.
     */
    @Test
    fun `every scene of both cuts carries a spoken line`() {
        val harvests = realHarvests()
        Assume.assumeTrue("no harvested evidence.json on this machine", harvests.isNotEmpty())

        harvests.forEach { file ->
            val evidence = gson.fromJson(file.readText(), Evidence::class.java)
            listOf(Audience.STAKEHOLDER, Audience.TECHNICAL).forEach { audience ->
                val cut = FallbackDirector.direct(evidence, audience, 60_000)
                val script = cut.scenes.mapNotNull { it.narration?.trim()?.ifBlank { null } }

                println("\n=== ${evidence.projectName}, $audience, ${cut.scenes.size} scenes ===")
                println(script.joinToString(" "))

                val silent = cut.scenes.withIndex().filter { it.value.narration.isNullOrBlank() }
                assertTrue(
                    "${evidence.projectName} $audience cut is silent in scene(s) " +
                        silent.joinToString { "${it.index + 1} (${it.value.template})" },
                    silent.isEmpty()
                )
            }
        }
    }

    private fun visibleText(slots: List<JsonElement>): String = buildString {
        fun walk(e: JsonElement) {
            when {
                e.isJsonPrimitive && e.asJsonPrimitive.isString -> append(e.asString).append(' ')
                e.isJsonObject -> e.asJsonObject.entrySet().forEach { walk(it.value) }
                e.isJsonArray -> e.asJsonArray.forEach { walk(it) }
            }
        }
        slots.forEach(::walk)
    }

    private companion object {
        val OFFENDERS = listOf(
            "an HTTP verb" to Regex("""\b(GET|POST|PUT|PATCH|DELETE)\b"""),
            "a route" to Regex("""/[a-z0-9][\w-]*/[\w-]+"""),
            "a source file" to Regex("""\b[\w.-]+\.(kt|java|py|jsx?|tsx?|css|html|ya?ml|toml|json)\b""", RegexOption.IGNORE_CASE),
            "a line count" to Regex("""\b(lines?|files?)\s+(written|of\s+code)\b""", RegexOption.IGNORE_CASE),
            "a package name" to Regex("""\b(fastapi|httpx|sentence-transformers|torch|openai|uvicorn|pydantic|sqlalchemy)\b""", RegexOption.IGNORE_CASE),
        )
    }
}
