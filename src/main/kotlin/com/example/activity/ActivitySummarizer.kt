package com.example.activity

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns a range of commits into a short, readable account of what the developer did.
 *
 * Only commit messages and changed-file paths are sent to the model — never file contents or
 * diffs. That keeps the prompt around 40 tokens per commit, so a normal week fits in one call, and
 * it means source code never leaves the machine.
 */
class ActivitySummarizer(private val client: LlmClient) {

    fun summarize(
        commits: List<Commit>,
        modules: List<ActivityModule>,
        request: ActivityRequest,
        pending: List<PendingChange> = emptyList()
    ): ActivityReport {
        // Work in progress alone is worth summarising, so an empty history is not the end of it.
        if (commits.isEmpty() && pending.isEmpty()) return ActivityReport.empty(request)

        val chunks = commits.chunked(CHUNK_SIZE).ifEmpty { listOf(emptyList()) }
        val report = if (chunks.size == 1) {
            parseReport(client.completeJson(SYSTEM, userPrompt(chunks.first(), modules, request, pending)))
        } else {
            // Long ranges are summarised per chunk and then merged, so no single prompt gets huge.
            val partials = chunks.mapIndexed { index, chunk ->
                // Work in progress belongs with the most recent chunk only, so it is not repeated.
                val chunkPending = if (index == 0) pending else emptyList()
                themesOf(parseReport(client.completeJson(SYSTEM, userPrompt(chunk, modules, request, chunkPending))))
            }.flatten()
            merge(partials, request)
        }

        return report.copy(
            status = "ready",
            commits = commits,
            uncommitted = pending,
            stats = statsOf(commits, pending),
            request = request
        )
    }

    private fun themesOf(report: ActivityReport) = report.themes

    private fun merge(themes: List<Theme>, request: ActivityRequest): ActivityReport {
        if (themes.size <= MAX_THEMES) {
            return ActivityReport(status = "ready", headline = "", themes = themes)
        }
        val listing = themes.joinToString("\n") { t ->
            "- ${t.title} [${t.scopes.joinToString(", ")}] (${t.commits.joinToString(" ")}): ${t.summary}"
        }
        val prompt = """
            These partial summaries cover ${request.since} to ${request.until}. Merge overlapping
            entries into at most $MAX_THEMES themes, keeping every commit hash on the theme it
            belongs to, and write the overall headline.

            $listing
        """.trimIndent()
        return parseReport(client.completeJson(SYSTEM, prompt))
    }

    private fun userPrompt(
        commits: List<Commit>,
        modules: List<ActivityModule>,
        request: ActivityRequest,
        pending: List<PendingChange>
    ): String {
        val scopeLine = when {
            request.scope == ActivityRequest.ALL -> "the whole project"
            request.scope.startsWith(ActivityRequest.MODULE_PREFIX) -> {
                val id = request.scope.removePrefix(ActivityRequest.MODULE_PREFIX)
                modules.firstOrNull { it.id == id }?.name ?: request.scope
            }
            else -> request.scope
        }
        val moduleLine = modules.take(20).joinToString(", ") { "${it.name} (${it.kind})" }
        return buildString {
            appendLine("Range: ${request.since} to ${request.until}. Scope: $scopeLine.")
            if (moduleLine.isNotBlank()) appendLine("Project modules: $moduleLine.")
            if (pending.isNotEmpty()) {
                appendLine()
                appendLine("UNCOMMITTED WORK IN PROGRESS — ${pending.size} files changed on disk but not committed.")
                appendLine("This is the newest work and MUST get its own theme; see the rules.")
                pending.take(PENDING_LIMIT).forEach {
                    appendLine("  ${it.status} ${it.path} +${it.added} -${it.deleted}${if (it.date.isNotBlank()) " (last saved ${it.date} ${it.time})" else ""}")
                }
                if (pending.size > PENDING_LIMIT) appendLine("  ...and ${pending.size - PENDING_LIMIT} more files")
            }
            appendLine()
            appendLine("${commits.size} commits:")
            appendLine()
            commits.forEach { append(describe(it, modules)) }
        }
    }

    private fun describe(commit: Commit, modules: List<ActivityModule>): String = buildString {
        appendLine("[${commit.shortHash}] ${commit.date} ${commit.time} ${commit.subject}")
        if (commit.body.isNotBlank()) appendLine("  ${commit.body.take(BODY_LIMIT).replace('\n', ' ')}")
        val scopes = ScopeFilter.scopesOf(commit, modules)
        if (scopes.isNotEmpty()) appendLine("  modules: ${scopes.joinToString(", ")}")
        commit.files.take(FILE_LIMIT).forEach { appendLine("  ${it.status} ${it.path} +${it.added} -${it.deleted}") }
        if (commit.files.size > FILE_LIMIT) appendLine("  ...and ${commit.files.size - FILE_LIMIT} more files")
    }

    private fun statsOf(commits: List<Commit>, pending: List<PendingChange>) = ActivityStats(
        commits = commits.size,
        files = commits.flatMap { c -> c.files.map { it.path } }.distinct().size,
        added = commits.added(),
        deleted = commits.deleted(),
        authors = commits.map { it.author }.distinct().sorted(),
        pendingFiles = pending.size,
        pendingAdded = pending.sumOf { it.added },
        pendingDeleted = pending.sumOf { it.deleted }
    )

    /** The model is asked for JSON, but we stay tolerant about missing or oddly typed fields. */
    private fun parseReport(raw: String): ActivityReport {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: throw OpenAiClient.OpenAiException("Could not read the model's response")
        val themes = (root.get("themes") as? JsonArray).orEmpty().mapNotNull { element ->
            val theme = element as? JsonObject ?: return@mapNotNull null
            val title = theme.string("title") ?: return@mapNotNull null
            Theme(
                title = title,
                summary = theme.string("summary").orEmpty(),
                scopes = theme.strings("scopes"),
                commits = theme.strings("commits")
            )
        }
        return ActivityReport(status = "ready", headline = root.string("headline").orEmpty(), themes = themes)
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.strings(name: String): List<String> =
        (get(name) as? JsonArray).orEmpty().mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }

    private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()

    companion object {
        private const val CHUNK_SIZE = 80
        private const val FILE_LIMIT = 12
        private const val BODY_LIMIT = 200
        private const val MAX_THEMES = 6
        private const val PENDING_LIMIT = 40

        val SYSTEM = """
            You summarise a developer's git activity so they can recall what they worked on.

            Rules:
            - Group commits by feature or theme, never list them one by one. Aim for 2-$MAX_THEMES themes,
              most significant first.
            - Address the developer as "you": "You added JWT refresh", not "The developer added".
            - Be concrete and factual. Use only what the commit messages and file paths support;
              never invent work that is not evidenced there.
            - "headline" is one sentence covering the whole range.
            - Each theme's "summary" is 1-2 sentences.
            - "commits" lists the short hashes you put in that theme; every commit belongs to exactly one.
            - "scopes" names the modules the theme touched, using the module names given.
            - When the input has an UNCOMMITTED WORK IN PROGRESS section, you MUST emit a theme
              titled exactly "In progress" as the FIRST theme. Describe what those files show the
              developer is building, say plainly that it is not committed yet, and give it an empty
              "commits" array. Never omit it, and never merge it into another theme.
            - Mention the work in progress in the headline too when there is any.

            Reply with JSON only:
            {"headline": "...", "themes": [{"title": "...", "summary": "...", "scopes": ["..."], "commits": ["abc1234"]}]}
        """.trimIndent()
    }
}
