package com.example.activity

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "what did I work on?" for one project: read the range from git, narrow it to the selected
 * scope, and have the model describe it. Summaries are cached until HEAD moves, so re-opening the
 * tab or switching back to a range already seen costs nothing.
 */
@Service(Service.Level.PROJECT)
class ActivityService(private val project: Project) {
    private val logger = Logger.getInstance(ActivityService::class.java)
    private val cache = ConcurrentHashMap<String, ActivityReport>()

    /** Never throws: every failure comes back as an error report the tool window can show. */
    fun report(request: ActivityRequest): ActivityReport {
        val root = repoRoot() ?: return ActivityReport.error("This project is not inside a git repository.")
        val settings = ActivitySettings.getInstance()
        if (!settings.hasApiKey()) {
            return ActivityReport.error("Add an OpenAI API key in Settings | Tools | Code Visualizer.")
        }

        val modules = ModuleDetector.detect(root)
        val git = GitActivitySource(root, settings.maxCommits)
        val range = DateRange.of(request.since, request.until)
            ?: return ActivityReport.error("That date range could not be read.")
        return try {
            val pending = if (request.includeUncommitted) {
                ScopeFilter.applyPending(git.pendingChanges(range), modules, request.scope)
            } else {
                emptyList()
            }

            // Uncommitted work changes without moving HEAD, so it is part of the cache identity.
            val key = cacheKey(request, git.head(), pending)
            cache[key]?.let { return it }

            val commits = ScopeFilter.apply(git.commits(request), modules, request.scope)
            if (commits.isEmpty() && pending.isEmpty()) return ActivityReport.empty(request)

            val report = ActivitySummarizer(settings.client()).summarize(commits, modules, request, pending)
            cache[key] = report
            report
        } catch (e: GitActivitySource.GitUnavailable) {
            logger.warn("Code Visualizer could not read git history", e)
            ActivityReport.error(e.message ?: "Could not read git history.")
        } catch (e: OpenAiClient.OpenAiException) {
            logger.warn("Code Visualizer could not summarise the range", e)
            ActivityReport.error(e.message ?: "The summary could not be generated.")
        } catch (e: Exception) {
            logger.warn("Code Visualizer activity failed", e)
            ActivityReport.error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The scope dropdown, derived from the build manifests found on disk. */
    fun scopes(): List<ScopeFilter.ScopeOption> =
        ScopeFilter.options(repoRoot()?.let(ModuleDetector::detect).orEmpty())

    /** Resolves "only my commits" without the UI ever handling the address. */
    fun currentUserEmail(): String = repoRoot()?.let { GitActivitySource(it).userEmail() }.orEmpty()

    fun isRepository(): Boolean = repoRoot() != null

    private fun repoRoot(): File? = project.basePath
        ?.let(::File)
        ?.takeIf { it.isDirectory && GitActivitySource.isRepository(it) }

    private fun cacheKey(request: ActivityRequest, head: String, pending: List<PendingChange>): String {
        val fingerprint = pending.joinToString(";") { "${it.path}:${it.timestamp}:${it.added}:${it.deleted}" }
        return "$head|${request.since}|${request.until}|${request.scope}|" +
            "${request.authorEmail.orEmpty()}|${fingerprint.hashCode()}"
    }

    companion object {
        fun getInstance(project: Project): ActivityService = project.service()
    }
}
