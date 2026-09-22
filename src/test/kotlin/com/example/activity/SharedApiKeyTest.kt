package com.example.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Activity tab and the Reel each resolve an OpenAI key, and each now falls back to the
 * other's sources so a key only has to be set up once.
 *
 * That mutual fallback is one edit away from infinite recursion: if either aggregate called the
 * other's aggregate, the first key lookup would never return. The split is the invariant —
 * `ownKey`/`ownKeyCandidates` and `ownCandidates` consult their own sources only, and just the
 * aggregates reach across. Nothing else enforces it, so this does.
 */
class SharedApiKeyTest {

    private val repoRoot = File(".").absoluteFile
        .let { generateSequence(it) { parent -> parent.parentFile }.first { File(it, ".git").exists() } }

    private fun source(path: String) = File(repoRoot, path).readText()

    private val activitySettings = "src/main/kotlin/com/example/activity/ActivitySettings.kt"
    private val reelSettings = "src/main/kotlin/com/example/yasinreel/settings/ReelSettings.kt"

    /** The body of a function, from its signature to the next declaration at the same indent. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("Could not find `$signature` — was it renamed?", start >= 0)
        val rest = text.substring(start + signature.length)
        val end = Regex("""\n {4}(fun|val|var|private|internal|companion|})""").find(rest)?.range?.first
        return rest.substring(0, end ?: rest.length)
    }

    @Test
    fun `the activity side's own sources do not reach into the reel`() {
        val text = source(activitySettings)

        assertFalse(
            "ownKey() must not consult ReelSettings, or apiKey would recurse",
            body(text, "fun ownKey()").contains("ReelSettings")
        )
        assertFalse(
            "ownKeyCandidates() must not consult ReelSettings; the reel calls it",
            body(text, "fun ownKeyCandidates()").contains("ReelSettings")
        )
    }

    @Test
    fun `the reel's own sources do not reach into the activity settings`() {
        assertFalse(
            "ownCandidates() must not consult ActivitySettings; the activity side calls it",
            body(source(reelSettings), "fun ownCandidates()").contains("ActivitySettings")
        )
    }

    @Test
    fun `each aggregate does fall back to the other`() {
        assertTrue(
            "apiKey should fall back to the reel's sources",
            source(activitySettings).contains("ownKey().ifBlank { reelKey() }")
        )
        assertTrue(
            "apiKeyCandidates should append the activity sources",
            body(source(reelSettings), "fun apiKeyCandidates()").contains("activityCandidates()")
        )
    }

    @Test
    fun `the cross-feature lookups are guarded so one side cannot break the other`() {
        val activity = source(activitySettings)
        val reel = source(reelSettings)

        assertTrue(
            "reelKey() must swallow failures; a missing Reel service must not break Activity",
            body(activity, "private fun reelKey()").contains("runCatching")
        )
        assertTrue(
            "activityCandidates() must swallow failures; it must never stop a reel generating",
            body(reel, "private fun activityCandidates()").contains("runCatching")
        )
    }
}
