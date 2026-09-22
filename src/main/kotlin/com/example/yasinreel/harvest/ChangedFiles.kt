package com.example.yasinreel.harvest

import com.example.activity.ActivityRequest
import com.example.activity.ActivityService
import com.example.activity.DateRange
import com.example.activity.GitActivitySource
import com.example.activity.ModuleDetector
import com.example.activity.ScopeFilter
import com.example.yasinreel.model.ReelScope
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Turns a [ReelScope] into the set of project-relative paths a recap is allowed to talk about.
 *
 * The reel had no notion of history: it harvested the whole project every time. Rather than
 * teach it to read git, this reuses the Activity tab's reader, so both features agree on what
 * "last week" means — including the awkward parts, like filtering on the author date rather
 * than the committer date, and counting work that is not committed yet.
 */
object ChangedFiles {

    private val logger = Logger.getInstance(ChangedFiles::class.java)

    /** What a recap is about, with enough context to narrate it honestly. */
    data class Changed(
        val paths: Set<String>,
        val commits: Int,
        val added: Int,
        val deleted: Int,
        val uncommitted: Int,
        val authors: List<String>,
        val subjects: List<String>
    ) {
        val isEmpty: Boolean get() = paths.isEmpty()
    }

    /**
     * Returns null for a launch reel, for a project that is not a git repository, or when git
     * cannot be read — every one of which means "do not narrow the evidence", which keeps the
     * original whole-project behaviour as the safe default.
     */
    fun resolve(project: Project, scope: ReelScope): Changed? {
        if (!scope.isRecap) return null

        val root = project.basePath?.let(::File)?.takeIf { GitActivitySource.isRepository(it) }
            ?: return null
        val range = DateRange.of(scope.since, scope.until) ?: return null

        return try {
            val git = GitActivitySource(root)
            val modules = ModuleDetector.detect(root)
            val request = ActivityRequest(
                since = scope.since,
                until = scope.until,
                scope = scope.area,
                authorEmail = if (scope.mine) git.userEmail().takeIf { it.isNotBlank() } else null,
                includeUncommitted = scope.includeUncommitted
            )

            val commits = ScopeFilter.apply(git.commits(request), modules, scope.area)
            val pending = if (scope.includeUncommitted) {
                ScopeFilter.applyPending(git.pendingChanges(range), modules, scope.area)
            } else {
                emptyList()
            }

            val paths = buildSet {
                commits.forEach { commit -> commit.files.forEach { add(it.path) } }
                pending.forEach { add(it.path) }
            }

            Changed(
                paths = paths,
                commits = commits.size,
                added = commits.sumOf { c -> c.files.sumOf { it.added } } + pending.sumOf { it.added },
                deleted = commits.sumOf { c -> c.files.sumOf { it.deleted } } + pending.sumOf { it.deleted },
                uncommitted = pending.size,
                authors = commits.map { it.author }.distinct().sorted(),
                // Newest first: the most recent work is what a recap should lead with.
                subjects = commits.map { it.subject }
            )
        } catch (e: Exception) {
            logger.warn("Nexus Reel could not read the range ${scope.describe()}; falling back to the whole project", e)
            null
        }
    }

    /** The Activity tab's scope options, so the reel offers the same areas without duplicating them. */
    fun areas(project: Project): List<ScopeFilter.ScopeOption> =
        runCatching { ActivityService.getInstance(project).scopes() }.getOrDefault(emptyList())
}
