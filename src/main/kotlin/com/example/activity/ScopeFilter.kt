package com.example.activity


/**
 * Maps commits onto the modules the analyzer already discovered, so the user can ask "what did I do
 * on the frontend?" without configuring anything. A file belongs to the module with the longest
 * matching path prefix; the root module ("") is the fallback owner.
 */
object ScopeFilter {

    /** An entry in the UI's scope dropdown. */
    data class ScopeOption(val id: String, val label: String, val kind: String)

    private const val FRONTEND = "frontend"
    private const val BACKEND = "backend"
    private const val FULLSTACK = "fullstack"

    fun options(modules: List<ActivityModule>): List<ScopeOption> = buildList {
        add(ScopeOption(ActivityRequest.ALL, "All", "all"))
        if (modules.any { it.kind == FRONTEND || it.kind == FULLSTACK }) {
            add(ScopeOption(FRONTEND, "Frontend", FRONTEND))
        }
        if (modules.any { it.kind == BACKEND || it.kind == FULLSTACK }) {
            add(ScopeOption(BACKEND, "Backend", BACKEND))
        }
        // Individual modules cover services the two buckets above do not describe well.
        modules.sortedBy { it.name }.forEach {
            add(ScopeOption("${ActivityRequest.MODULE_PREFIX}${it.id}", it.name, it.kind))
        }
    }

    /** The module owning [path], or null when no module (not even a root one) claims it. */
    fun owner(path: String, modules: List<ActivityModule>): ActivityModule? {
        val normalized = path.replace('\\', '/').removePrefix("./")
        return modules
            .filter { it.path.isEmpty() || normalized == it.path || normalized.startsWith(it.path.removeSuffix("/") + "/") }
            .maxByOrNull { it.path.length }
    }

    /** Module names a commit touched, for labelling themes in the report. */
    fun scopesOf(commit: Commit, modules: List<ActivityModule>): List<String> =
        commit.files.mapNotNull { owner(it.path, modules)?.name }.distinct()

    /**
     * Keeps only the commits that touched [scope], narrowing each commit's file list to the matching
     * files so the summary is not distracted by out-of-scope churn.
     */
    fun apply(commits: List<Commit>, modules: List<ActivityModule>, scope: String): List<Commit> {
        if (scope.isBlank() || scope == ActivityRequest.ALL) return commits
        val selected = selectedIds(modules, scope)
        if (selected.isEmpty()) return emptyList()
        return commits.mapNotNull { commit ->
            val files = commit.files.filter { owner(it.path, modules)?.id in selected }
            if (files.isEmpty()) null else commit.copy(files = files)
        }
    }

    /** The same scope rule applied to work in progress. */
    fun applyPending(
        pending: List<PendingChange>,
        modules: List<ActivityModule>,
        scope: String
    ): List<PendingChange> {
        if (scope.isBlank() || scope == ActivityRequest.ALL) return pending
        val selected = selectedIds(modules, scope)
        if (selected.isEmpty()) return emptyList()
        return pending.filter { owner(it.path, modules)?.id in selected }
    }

    private fun selectedIds(modules: List<ActivityModule>, scope: String): Set<String> = when {
        scope.startsWith(ActivityRequest.MODULE_PREFIX) -> {
            val id = scope.removePrefix(ActivityRequest.MODULE_PREFIX)
            modules.filter { it.id == id }.map { it.id }.toSet()
        }
        scope == FRONTEND -> modules.filter { it.kind == FRONTEND || it.kind == FULLSTACK }.map { it.id }.toSet()
        scope == BACKEND -> modules.filter { it.kind == BACKEND || it.kind == FULLSTACK }.map { it.id }.toSet()
        else -> emptySet()
    }
}
