package com.example.trust

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

/** Fired whenever the verdict may have changed, so the paint and the tab redraw together. */
interface TrustListener {
    fun trustChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<TrustListener> = Topic.create("nexus trust changed", TrustListener::class.java)
    }
}

/**
 * The one place that knows the trust verdict for this project, and whether the paint is on.
 *
 * Off by default unless the project asked otherwise. A feature that colours somebody's code has
 * to be asked for, and a half-built one must never be able to spoil a teammate's demo: the
 * switch is the difference between "turn it off" and "revert the branch" when something misbehaves.
 */
@Service(Service.Level.PROJECT)
class TrustService(private val project: Project) {

    private val coverage = CoverageReportSource()

    private val references = ReferenceScanner(project)

    /**
     * Real coverage first, the fixture only when there is none.
     *
     * This ordering is the whole honesty of the feature: the moment somebody runs their tests
     * with coverage, the placeholder stops being consulted and never comes back.
     */
    private val source: ExecutionSource = CompositeExecutionSource(
        listOf(coverage, FixtureExecutionSource()),
    )

    @Volatile
    private var explicit: Boolean? = null

    /** Cleared by [refresh], so a finished run re-asks both questions and not just one. */
    @Volatile
    private var enriched: Map<String, FileTrust>? = null

    /**
     * Off unless the project asked otherwise, and then whatever the user last chose.
     *
     * The first read consults the fixture: a project carrying a `.nexus/trust.json` with
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

    val sourceLabel: String get() = source.label

    /** "from backend/coverage.xml, 4 minute(s) ago", or null when nothing is known. */
    fun describeSource(): String? = source.describe(project)

    /** True while the numbers are placeholder data, so the UI can say so out loud. */
    fun isPlaceholder(): Boolean = coverage.reportPath == null && allKnown().isNotEmpty()

    /** Null when nothing is known about the file, which the UI shows differently from "all proven". */
    fun trustFor(file: VirtualFile): FileTrust? = source.trustFor(project, file)

    /** Every file the current source has an opinion about. Cheap: no index lookups. */
    fun allKnown(): Map<String, FileTrust> = source.all(project)

    /**
     * The same verdict with dead code marked, which costs index lookups.
     *
     * **Call this off the event thread.** It searches the project for every file that never ran
     * at all, and doing that on the UI thread would freeze the IDE for as long as it takes.
     * The painter deliberately uses [allKnown] instead: whether a line ran is all it needs, and
     * whether anything imports the file has no bearing on what colour to wash it.
     */
    fun allKnownDetailed(): Map<String, FileTrust> {
        enriched?.let { return it }

        val raw = allKnown()
        val marks = references.classify(raw.values)
        val result = raw.mapValues { (path, trust) ->
            marks[path]?.let { trust.copy(reachability = it) } ?: trust
        }
        enriched = result
        return result
    }

    /**
     * Re-reads the report and tells everyone. Called when a run finishes, which is the moment
     * the answer can actually have changed, and the moment the colour is supposed to move.
     */
    fun refresh() {
        coverage.invalidate()
        references.invalidate()
        enriched = null
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(TrustListener.TOPIC).trustChanged()
        }
    }

    private fun projectDefault(): Boolean = FIXTURE.autoEnable(project)

    companion object {
        private val FIXTURE = FixtureExecutionSource()

        fun getInstance(project: Project): TrustService = project.service()
    }
}
