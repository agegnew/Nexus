package com.example.yasinreel.model

/**
 * What a reel is about.
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

    companion object {
        const val LAUNCH = "launch"
        const val RECAP = "recap"

        fun launch() = ReelScope(LAUNCH)
    }
}
