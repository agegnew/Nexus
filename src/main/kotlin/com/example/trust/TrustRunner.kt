package com.example.trust

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.TimeUnit

/** A shell command that produces a coverage report, and where the plugin got it from. */
data class CoverageCommand(val command: String, val origin: String)

/**
 * The one button.
 *
 * Everything the Trust tab shows comes from a coverage report, and the report comes from
 * running the project with coverage on. Until now the plugin left that step to the user,
 * which is how a colleague can clone the repository, open the tab and see nothing, with no
 * idea what to do about it. This runs the command for them, in the IDE's own Run console so
 * the output is visible, and the tab redraws itself when the process ends.
 *
 * The command comes from `.nexus/trust.json` when the project says so, and is otherwise
 * guessed from the files at the root. The guess is shown before it runs, never hidden.
 */
object TrustRunner {

    private val logger = Logger.getInstance(TrustRunner::class.java)
    private val gson = Gson()

    fun commandFor(project: Project): CoverageCommand? {
        val base = project.basePath ?: return null
        return commandFor(File(base))
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun commandFor(root: File): CoverageCommand? = configured(root) ?: detect(root)

    /**
     * Runs the command and calls [onFinished] on the event thread when it exits.
     *
     * The report is re-read on exit rather than on some timer, because the exit is the one
     * moment the answer can have changed. A short delay covers tools that print "done" and
     * then flush the file.
     */
    fun run(project: Project, command: CoverageCommand, onFinished: () -> Unit) {
        val base = project.basePath ?: return

        val commandLine = if (SystemInfo.isWindows) {
            GeneralCommandLine("cmd", "/c", command.command)
        } else {
            GeneralCommandLine("/bin/sh", "-c", command.command)
        }.withWorkDirectory(base).withCharset(Charsets.UTF_8)

        val handler = runCatching { OSProcessHandler(commandLine) }
            .onFailure { logger.warn("Nexus Trust could not start: ${command.command}", it) }
            .getOrElse {
                onFinished()
                return
            }

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        console.print("$ ${command.command}\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        val descriptor = RunContentDescriptor(console, handler, console.component, "Trust: run with coverage")
        RunContentManager.getInstance(project)
            .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    if (project.isDisposed) return@schedule
                    TrustService.getInstance(project).refresh()
                    ApplicationManager.getApplication().invokeLater(onFinished, ModalityState.any(), project.disposed)
                }, 300, TimeUnit.MILLISECONDS)
            }
        })
        handler.startNotify()
    }

    private fun configured(root: File): CoverageCommand? {
        val file = File(root, FixtureExecutionSource.FIXTURE_PATH)
        if (!file.isFile) return null
        val payload = runCatching { gson.fromJson(file.readText(), Config::class.java) }
            .onFailure { logger.warn("Nexus Trust could not read ${file.path}", it) }
            .getOrNull()
        val command = payload?.command?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CoverageCommand(command, "from ${FixtureExecutionSource.FIXTURE_PATH}")
    }

    /**
     * A best guess from what is lying at the project root.
     *
     * Three ecosystems, because those are the three whose report formats this plugin reads.
     *
     * Gradle used to be left out on purpose, on the grounds that JaCoCo writes its own XML and
     * not Cobertura, so a button that ran for a minute and then changed nothing was worse than
     * a button that admitted it did not know. CoverageReportSource reads JaCoCo now, so that
     * reason has expired and the button can offer it.
     */
    internal fun detect(root: File): CoverageCommand? {
        val python = PYTHON_MARKERS.any { File(root, it).isFile }
        if (python) {
            val interpreter = VENV_PYTHONS.map { File(root, it) }.firstOrNull { it.canExecute() }?.path ?: "python3"
            return CoverageCommand(
                "$interpreter -m coverage run -m pytest -q && $interpreter -m coverage xml -o coverage.xml",
                "detected: Python project, pytest with coverage.py",
            )
        }

        val wrapper = GRADLE_WRAPPERS.map { File(root, it) }.firstOrNull { it.isFile }
        if (wrapper != null) {
            // jacocoTestReport needs the jacoco plugin applied. If the project has not applied
            // it the task is unknown, Gradle fails loudly in the console, and that is the right
            // outcome: the reason is on screen rather than being a tab that silently stays empty.
            val launcher = if (SystemInfo.isWindows) "gradlew.bat" else "./gradlew"
            return CoverageCommand(
                "$launcher test jacocoTestReport",
                "detected: Gradle project, JaCoCo",
            )
        }

        val pkg = File(root, "package.json")
        if (pkg.isFile) {
            val text = runCatching { pkg.readText() }.getOrDefault("")
            if (text.contains("\"vitest\"")) {
                return CoverageCommand(
                    "npx vitest run --coverage.enabled --coverage.reporter=lcov",
                    "detected: vitest",
                )
            }
            if (text.contains("\"jest\"")) {
                return CoverageCommand("npx jest --coverage --coverageReporters=lcov", "detected: jest")
            }
        }
        return null
    }

    private data class Config(val command: String?)

    private val PYTHON_MARKERS = listOf("pyproject.toml", "requirements.txt", "setup.py", "pytest.ini", "setup.cfg")
    private val VENV_PYTHONS = listOf(".venv/bin/python", "venv/bin/python")
    private val GRADLE_WRAPPERS = listOf("gradlew", "gradlew.bat")
}
