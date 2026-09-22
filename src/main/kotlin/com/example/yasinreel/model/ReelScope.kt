package com.example.yasinreel.model

import com.google.gson.JsonObject

/**
 * What a reel or a deck is about.
 *
 * [LAUNCH] is the original behaviour: the whole project, answering "what is this product".
 * [RECAP] narrows the evidence to the files touched in a date range, answering "what did we
 * build last week" — the same question the Activity tab answers in prose.
 *
 * This is deliberately separate from the audience. A recap can be told to engineers or to
 * stakeholders, so the two dimensions multiply rather than compete; folding recap into the
 * audience string would have made it render as a technical cut, because every consumer of
 * that string tests `== STAKEHOLDER` and treats everything else as technical.
 */
data class ReelScope(
    val kind: String = LAUNCH,
    /** yyyy-MM-dd, only meaningful for [RECAP]. */
    val since: String = "",
    val until: String = "",
    /** "all", "frontend", "backend" or "module:<id>", matching the Activity tab's scopes. */
    val area: String = "all",
    /** Whether to count only the current user's commits. */
    val mine: Boolean = true,
    /** Whether work that is not committed yet counts as part of the range. */
    val includeUncommitted: Boolean = true
) {
    val isRecap: Boolean get() = kind == RECAP

    /** A short phrase for narration and progress text, e.g. "between 2026-09-14 and 2026-09-20". */
    fun describe(): String = if (isRecap) "between $since and $until" else "the whole project"

    /** A file-name fragment for the range, e.g. "recap-2026-09-01-to-2026-09-22". */
    fun fileTag(): String = if (isRecap) "recap-$since-to-$until" else ""

    companion object {
        const val LAUNCH = "launch"
        const val RECAP = "recap"

        fun launch() = ReelScope(LAUNCH)

        /**
         * Reads the recap controls off a bridge message from either tab.
         *
         * Both tabs send the same object, because both fill it from the same
         * `/shared/scope.js`, so both read it the same way here. Anything missing or
         * unreadable falls back to a launch run, which keeps an older page that knows
         * nothing about ranges working exactly as it did.
         */
        fun from(message: JsonObject): ReelScope {
            if (string(message, "scope") != RECAP) return launch()
            val since = string(message, "since").orEmpty()
            val until = string(message, "until").orEmpty()
            // A range with an open end is not a range. Rather than guess at the missing
            // half, this is the whole project, which is the one answer that cannot be wrong.
            if (since.isBlank() || until.isBlank()) return launch()
            return ReelScope(
                kind = RECAP,
                since = since,
                until = until,
                area = string(message, "area") ?: "all",
                mine = bool(message, "mine") ?: true,
                includeUncommitted = bool(message, "uncommitted") ?: true
            )
        }

        private fun string(message: JsonObject, name: String): String? {
            val element = message.get(name) ?: return null
            return if (element.isJsonPrimitive) element.asString else null
        }

        private fun bool(message: JsonObject, name: String): Boolean? {
            val element = message.get(name) ?: return null
            return runCatching { element.asBoolean }.getOrNull()
        }
    }
}
