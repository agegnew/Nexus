package com.example.trust

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Redraws when a coverage report changes on disk, however it changed.
 *
 * Before this, only a run started from inside the IDE moved the picture. A `coverage run`
 * typed into a terminal wrote a new report that the tab never noticed, and the only way to
 * see it was a button that appeared to do nothing on the days the file had not changed.
 * Watching the file makes the source of the run irrelevant: terminal, CI artefact copied in,
 * the Run button, all the same.
 *
 * Debounced, because writing one report raises several events and the last one is the one
 * with the finished file in it.
 */
class TrustReportWatcher : BulkFileListener {

    private val pending = AtomicReference<ScheduledFuture<*>?>(null)

    override fun after(events: MutableList<out VFileEvent>) {
        val touched = events.mapNotNull { it.path }
            .filter { path -> CoverageReportSource.REPORT_NAMES.any { path.endsWith("/$it") } }
        if (touched.isEmpty()) return

        pending.getAndSet(
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ refreshOwners(touched) }, 400, TimeUnit.MILLISECONDS),
        )?.cancel(false)
    }

    private fun refreshOwners(paths: List<String>) {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val base = project.basePath ?: continue
            if (paths.any { it.startsWith("$base/") }) TrustService.getInstance(project).refresh()
        }
    }
}
