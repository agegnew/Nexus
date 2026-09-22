package com.example.yasinreel.harvest

import com.intellij.openapi.diagnostic.Logger

/**
 * The safety boundary of stage 1.
 *
 * Harvested text leaves the machine twice: once to a third-party model, once onto a
 * screen that gets recorded. A token that slips through is therefore leaked twice, so
 * this runs over every string the harvester emits, as the last thing stage 1 does. No
 * later stage can bypass what it cannot see.
 *
 * It fails closed. If a pattern ever blows up on pathological input we drop the string
 * rather than let it through unscrubbed.
 */
object SecretScrubber {

    private val logger = Logger.getInstance(SecretScrubber::class.java)

    private const val MARKER = "[redacted]"

    /** Shapes that are a secret whatever surrounds them, so they are matched first. */
    private val tokenPatterns = listOf(
        // Bounded rather than greedy, so a BEGIN with no END cannot walk the whole file.
        Regex("""(?i)-----BEGIN[A-Z ]{0,40}PRIVATE KEY-----[\s\S]{0,4000}?-----END[A-Z ]{0,40}PRIVATE KEY-----"""),
        Regex("""\bsk-[A-Za-z0-9_\-]{12,}"""),
        Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{16,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bglpat-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{12,}"""),
        Regex("""\bAIza[A-Za-z0-9_\-]{20,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9\-]{10,}"""),
        Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{4,}""")
    )

    private val bearerPattern = Regex("""(?i)\b(bearer)\s+([A-Za-z0-9._\-]{8,})""")

    /**
     * Keeps the key name and eats the value, because `OPENAI_API_KEY` is useful evidence
     * about what the product does and its value never is.
     */
    private val assignmentPattern = Regex(
        """(?i)\b(api[_\-]?key|apikey|api[_\-]?secret|client[_\-]?secret|access[_\-]?token|auth[_\-]?token|secret[_\-]?key|secret|password|passwd|pwd|token|authorization)(["']?\s*[:=]{1,2}\s*)(["'`]?)([^\s"'`,;)}\]]{6,})\3"""
    )

    private val emailPattern = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,24}""")

    private val privateIpPattern = Regex(
        """\b(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})\b"""
    )

    private val privateHostPattern = Regex(
        """\b[A-Za-z0-9][A-Za-z0-9\-]{0,62}(?:\.[A-Za-z0-9\-]{1,63}){0,4}\.(?:internal|local)\b"""
    )

    /**
     * Values that are obviously not secrets. Anything not on this list is redacted even
     * when it looks like a harmless identifier: a wrong redaction costs one dull line of
     * narration, a missed one costs a credential.
     */
    private val placeholders = setOf(
        "null", "none", "nil", "true", "false", "undefined", "empty", "string", "str", "int",
        "bool", "boolean", "required", "optional", "todo", "changeme", "example", "value",
        "your_api_key", "your-api-key", "xxxxxx", "secret", "password"
    )

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        return runCatching { redact(text) }.getOrElse { failure ->
            logger.warn("Scrub failed, dropping the text rather than risking a leak", failure)
            MARKER
        }
    }

    private fun redact(text: String): String {
        var out = text
        tokenPatterns.forEach { pattern -> out = pattern.replace(out, MARKER) }
        out = bearerPattern.replace(out) { match ->
            match.groupValues[1] + " " + MARKER
        }
        out = assignmentPattern.replace(out) { match ->
            val quote = match.groupValues[3]
            val value = match.groupValues[4]
            if (isPlaceholder(value)) {
                match.value
            } else {
                match.groupValues[1] + match.groupValues[2] + quote + MARKER + quote
            }
        }
        out = emailPattern.replace(out, MARKER)
        out = privateIpPattern.replace(out, MARKER)
        out = privateHostPattern.replace(out, MARKER)
        return out
    }

    /** An environment lookup is a reference to a secret, not the secret itself. */
    private fun isPlaceholder(value: String): Boolean {
        val lowered = value.lowercase()
        return lowered in placeholders ||
            lowered.startsWith("process.env") ||
            lowered.startsWith("import.meta.env") ||
            lowered.startsWith("os.environ") ||
            lowered.startsWith("os.getenv") ||
            lowered.startsWith("system.getenv") ||
            lowered.startsWith("env.") ||
            lowered.startsWith("<") ||
            lowered.startsWith("{") ||
            value.all { it == '*' || it == '.' || it == '#' }
    }
}
