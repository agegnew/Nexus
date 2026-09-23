package com.example.trust

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Re-reads the coverage report whenever a run finishes, so the colour moves while you watch.
 *
 * This is the part that makes the feature worth keeping on. A static picture of what has never
 * run is a report; a picture that turns green as your tests finish is feedback. Both the plain
 * run listener and the test listener are here because the two fire for different things: a test
 * suite through the test runner, a script or server through the ordinary runner.
 *
 * Both are cheap. Neither reads anything on the UI thread: the actual parse happens later, on
 * whichever thread asks the service for a verdict.
 */
class TrustRunListener : ExecutionListener {

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        // A report is written as the process exits, so give the file system a beat to catch up
        // rather than reading a half-written file and caching the result.
        scheduleRefresh(env.project)
    }
}

/**
 * Refreshes shortly after the run ends, twice.
 *
 * Coverage tools write their report after the last test prints, and how long that takes depends
 * on the tool. Two cheap attempts a second apart beat one that is reliably too early, and the
 * work is only a file timestamp check when nothing has changed.
 */
private fun scheduleRefresh(project: Project) {
    if (project.isDisposed) return
    val service = TrustService.getInstance(project)

    ApplicationManager.getApplication().executeOnPooledThread {
        repeat(2) { attempt ->
            Thread.sleep(if (attempt == 0) 400 else 1_500)
            if (project.isDisposed) return@executeOnPooledThread
            service.refresh()
        }
    }
}

/**
 * Repaints the editors and the status bar when the verdict changes.
 *
 * Separate from the tab's own subscription on purpose: the paint has to move even when the
 * Trust tab was never opened, which is the normal case for somebody who just wants the colour.
 */
class TrustRepaintListener(private val project: Project) : TrustListener {

    override fun trustChanged() {
        TrustPainter.refresh(project)
        TrustWidget.update(project)
    }
}
