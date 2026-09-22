package com.example.activity

/** One file touched by a commit. Binary files report no line counts, so [binary] flags them. */
data class FileChange(
    val path: String,
    val added: Int,
    val deleted: Int,
    val binary: Boolean = false,
    /** modified | added | deleted | renamed | copied — matching [PendingChange.status]. */
    val status: String = "modified"
)

data class Commit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val email: String,
    /** yyyy-MM-dd, the author date rendered in the user's own time zone. */
    val date: String,
    /** HH:mm in the same zone, so a row can say exactly when the work landed. */
    val time: String,
    /** Author date as absolute epoch seconds, which is what range filtering uses. */
    val timestamp: Long,
    val subject: String,
    val body: String,
    val merge: Boolean,
    val files: List<FileChange>
)

fun List<Commit>.added() = sumOf { c -> c.files.sumOf { it.added } }
fun List<Commit>.deleted() = sumOf { c -> c.files.sumOf { it.deleted } }

/**
 * What the user asked for. [scope] is "all", "frontend", "backend" or "module:<id>"; [authorEmail]
 * limits the history to one person and is null for "everyone".
 */
data class ActivityRequest(
    val since: String,
    val until: String,
    val scope: String = "all",
    val authorEmail: String? = null,
    /** Whether work in progress counts as part of "what I did". */
    val includeUncommitted: Boolean = true
) {
    companion object {
        const val ALL = "all"
        const val MODULE_PREFIX = "module:"
    }
}

/**
 * A change that is not committed yet. Its [date] comes from the file's modification time, since
 * there is no commit to take one from.
 */
data class PendingChange(
    val path: String,
    /** modified | added | deleted | renamed | untracked */
    val status: String,
    val added: Int,
    val deleted: Int,
    val staged: Boolean,
    /** yyyy-MM-dd in the user's time zone, or "" when the file is gone. */
    val date: String,
    /** HH:mm in the same zone, or "" when the file is gone. */
    val time: String,
    val timestamp: Long,
    /** True when the time is inferred from the parent directory rather than the file itself. */
    val approximate: Boolean = false
)

data class ActivityStats(
    val commits: Int,
    val files: Int,
    val added: Int,
    val deleted: Int,
    val authors: List<String>,
    val pendingFiles: Int = 0,
    val pendingAdded: Int = 0,
    val pendingDeleted: Int = 0
)

/** A group of related commits the model found, e.g. "Auth" or "Visualizer UI". */
data class Theme(
    val title: String,
    val summary: String,
    /** Module names the theme touched; empty when the model could not attribute it. */
    val scopes: List<String>,
    /** Short hashes, so the UI can expand a theme into its commits. */
    val commits: List<String>
)

data class ActivityReport(
    /** ready | empty | error */
    val status: String,
    val message: String = "",
    val headline: String = "",
    val themes: List<Theme> = emptyList(),
    val commits: List<Commit> = emptyList(),
    /** Work in progress in the range, newest first. */
    val uncommitted: List<PendingChange> = emptyList(),
    val stats: ActivityStats = ActivityStats(0, 0, 0, 0, emptyList()),
    val request: ActivityRequest? = null
) {
    companion object {
        fun error(message: String) = ActivityReport(status = "error", message = message)
        fun empty(request: ActivityRequest) =
            ActivityReport(status = "empty", message = "No commits in this range.", request = request)
    }
}
