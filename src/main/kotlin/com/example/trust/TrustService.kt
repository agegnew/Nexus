package com.example.trust

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The one place that knows the trust verdict for this project, and whether the paint is on.
 *
 * Off by default on purpose. A feature that colours somebody's code has to be asked for, and a
 * half-built one must never be able to spoil a teammate's demo: the switch is the difference
 * between "turn it off" and "revert the branch" when something misbehaves.
 */
@Service(Service.Level.PROJECT)
class TrustService(private val project: Project) {

    /** Swappable so a coverage-backed source can replace the fixture without any UI change. */
    private val source: ExecutionSource = FixtureExecutionSource()

    @Volatile
    private var explicit: Boolean? = null

    /**
     * Off unless the project asked otherwise, and then whatever the user last chose.
     *
     * The first read consults the source: a project carrying a `.nexus/trust.json` with
     * `"autoEnable": true` has opted in, so the paint is already on when its files are opened.
     * Any toggle after that is the user's word and wins for the rest of the session.
     */
    val enabled: Boolean
        get() = explicit ?: projectDefault()

    /** Returns the new state, so callers do not have to read it back. */
    fun toggle(): Boolean {
        val next = !enabled
        explicit = next
        return next
    }

    private fun projectDefault(): Boolean =
        (source as? FixtureExecutionSource)?.autoEnable(project) ?: false

    val sourceLabel: String get() = source.label

    /** Null when nothing is known about the file, which the UI shows differently from "all proven". */
    fun trustFor(file: VirtualFile): FileTrust? = source.trustFor(project, file)

    /** Every file the current source has an opinion about. Used for project-wide numbers. */
    fun allKnown(): Map<String, FileTrust> =
        (source as? FixtureExecutionSource)?.all(project) ?: emptyMap()

    companion object {
        fun getInstance(project: Project): TrustService = project.service()
    }
}
