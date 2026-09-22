package com.example.yasinreel.render

import com.example.yasinreel.model.Storyboard
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Gets a finished reel out of the IDE and into something you can send someone.
 *
 * Layered on purpose, because export is the step most likely to meet a machine that
 * has nothing installed:
 *
 *  1. playing in the tool window needs nothing, and is handled elsewhere
 *  2. [exportHtml] needs nothing either, and is the layer that must never fail
 *  3. [exportMp4] needs Node, ffmpeg and ffprobe, and when they are missing or the render dies
 *     it degrades to layer 2 and says so, rather than leaving the user with nothing
 *
 * [toolchain] exists so the UI can grey the MP4 option out with a reason *before* a
 * click, which is the difference between a considered product and one that fails late.
 *
 * Callbacks are delivered on the EDT, matching [ReelPipeline], so a caller can touch
 * the browser from them directly.
 */
@Service(Service.Level.PROJECT)
class ReelExporter(private val project: Project) {

    /**
     * What this machine can actually do.
     *
     * [detail] is written to be shown to a human verbatim, because a disabled menu item
     * with no reason is worse than no menu item at all. It names only what is missing and
     * how to install it, never an optional extra the renderer does not touch.
     *
     * [ffprobe] is last and defaulted so that older callers constructing this by name keep
     * compiling. It ships with ffmpeg, so on almost every machine it follows [ffmpeg].
     */
    data class Toolchain(
        val node: Boolean,
        val ffmpeg: Boolean,
        val canMp4: Boolean,
        val detail: String,
        val ffprobe: Boolean = ffmpeg
    )

    private val logger = Logger.getInstance(ReelExporter::class.java)
    private val probed = AtomicReference<Toolchain>()
    private val probing = AtomicBoolean(false)
    private val exporting = AtomicBoolean(false)

    // ------------------------------------------------------------------ layer 2, HTML

    /**
     * Writes one self-contained `.html` file and notifies.
     *
     * Runs off the EDT because narration is inlined as base64, which is real work on a
     * film with a minute of voice in it.
     */
    fun exportHtml(storyboard: Storyboard, onDone: (File) -> Unit, onError: (String) -> Unit) {
        val done: (File) -> Unit = { file -> onEdt { onDone(file) } }
        val failed: (String) -> Unit = { message -> onEdt { onError(message) } }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = writeStandalone(storyboard)
                notify(
                    "The ${storyboard.audience} cut is ready to share",
                    "${file.name} plays in any browser, offline, with nothing installed.",
                    NotificationType.INFORMATION,
                    file
                )
                done(file)
            } catch (e: Exception) {
                logger.warn("Nexus Reel could not export the ${storyboard.audience} cut as HTML", e)
                failed(e.message ?: "The reel could not be written.")
            }
        }
    }

    // ------------------------------------------------------------------ layer 3, MP4

    /**
     * Renders an `.mp4` through the HyperFrames CLI, in the background and cancellable.
     *
     * A render is minutes of work on a machine without a hardware encoder, so every line
     * the CLI prints is forwarded to [onProgress] and the indicator: a silent ten minute
     * bar is indistinguishable from a hang.
     */
    fun exportMp4(
        storyboard: Storyboard,
        onProgress: (String) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val progress: (String) -> Unit = { message -> onEdt { onProgress(message) } }
        val done: (File) -> Unit = { file -> onEdt { onDone(file) } }
        val failed: (String) -> Unit = { message -> onEdt { onError(message) } }

        // Two renders at once would fight over the same composition directory and would
        // between them saturate every core on the machine.
        if (!exporting.compareAndSet(false, true)) {
            failed("A video is already being rendered. Let that one finish, or cancel it in the status bar.")
            return
        }

        val task = object : Task.Backgroundable(project, "Rendering the ${storyboard.audience} reel", true) {
            override fun run(indicator: ProgressIndicator) {
                runMp4(storyboard, indicator, progress, done, failed)
            }

            override fun onFinished() {
                exporting.set(false)
            }
        }

        ApplicationManager.getApplication().invokeLater(
            Runnable {
                if (project.isDisposed) {
                    exporting.set(false)
                    return@Runnable
                }
                ProgressManager.getInstance().run(task)
            },
            ModalityState.any()
        )
    }

    private fun runMp4(
        storyboard: Storyboard,
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        indicator.isIndeterminate = true
        try {
            val chain = toolchain()
            if (!chain.canMp4) {
                fallBackToHtml(storyboard, chain.detail, onProgress, onError)
                return
            }

            step(indicator, onProgress, "Laying out the composition")
            val composition = CompositionWriter.writeHyperFramesProject(
                storyboard,
                narrationFor(storyboard.audience),
                workDir()
            )

            step(indicator, onProgress, "Checking the render toolchain")
            val doctor = doctor(composition, indicator)
            if (!doctor.first) {
                fallBackToHtml(storyboard, doctor.second, onProgress, onError)
                return
            }

            val target = File(exportDir(), "${CompositionWriter.baseName(storyboard)}.mp4")
            step(indicator, onProgress, "Rendering. This takes minutes, and you can keep working.")
            val render = render(composition, target, indicator, onProgress)

            if (!render.first || !target.isFile || target.length() == 0L) {
                fallBackToHtml(storyboard, render.second, onProgress, onError)
                return
            }

            logger.info("Nexus Reel rendered ${target.absolutePath} (${target.length() / 1_000_000}MB)")
            notify(
                "The ${storyboard.audience} reel is rendered",
                "${target.name}, ${target.length() / 1_000_000} MB.",
                NotificationType.INFORMATION,
                target
            )
            onProgress("Rendered ${target.name}.")
            onDone(target)
        } catch (e: ProcessCanceledException) {
            logger.info("Nexus Reel cancelled the ${storyboard.audience} render")
            onError("Render cancelled.")
            throw e
        } catch (e: Exception) {
            logger.warn("Nexus Reel could not render the ${storyboard.audience} cut", e)
            fallBackToHtml(storyboard, e.message ?: "The renderer failed.", onProgress, onError)
        }
    }

    /**
     * Whether a render can actually run here, read from the individual doctor checks.
     *
     * Deliberately *not* read from the payload's aggregate `ok` field. That field is false
     * whenever any optional extra is absent, and a stock machine is always missing several
     * of them (whisper-cpp for transcription, Kokoro for local voice, MusicGen for local
     * music, Docker for deterministic renders). None of those are touched when a
     * composition is turned into video, so gating on `ok` refuses renders that would have
     * succeeded, which is exactly what this used to do.
     *
     * What a render genuinely needs is Node, ffmpeg and ffprobe. Chrome is looked at too
     * but never blocks, because the CLI downloads and manages its own copy on first use.
     *
     * The command always exits 0 whatever it finds, so its exit code carries no verdict
     * and is never consulted.
     */
    private fun doctor(composition: File, indicator: ProgressIndicator): Pair<Boolean, String> {
        val command = cli("doctor", "--json") ?: return false to CLI_MISSING
        command.withWorkDirectory(composition)

        val payload = capture(command, DOCTOR_TIMEOUT_MS, indicator)?.let { lastJsonObject(it) }
        payload?.let { readRequiredChecks(it) }?.let { return it }

        // Either the doctor did not answer, or it answered in a shape this plugin has never
        // seen. Refusing on that would ground a machine that renders perfectly well, so the
        // binaries that actually matter are asked directly instead.
        logger.info("Nexus Reel could not read the HyperFrames doctor checks, probing the binaries directly")
        return probeDirectly(indicator)
    }

    /**
     * The doctor's verdict, or null when its payload does not carry the checks we know how
     * to read, which is the caller's signal to probe the binaries itself.
     */
    private fun readRequiredChecks(payload: JsonObject): Pair<Boolean, String>? {
        val checks = payload.getAsJsonArray("checks") ?: return null

        val byRequirement = HashMap<String, JsonObject>()
        for (element in checks) {
            val check = element as? JsonObject ?: continue
            val name = check.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            REQUIREMENT_NAMES[name.trim().lowercase(Locale.ROOT)]?.let { byRequirement[it] = check }
        }
        // Not one recognisable check means a payload we should not be drawing conclusions from.
        if (byRequirement.keys.none { it in BLOCKING }) return null

        val passed: (String) -> Boolean = { key ->
            // A requirement this CLI version does not report is not a requirement it failed.
            val check = byRequirement[key]
            check == null || (check.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true)
        }

        val missing = buildList<String> {
            when {
                !passed(NODE) -> add(nodeAdvice(0))
                else -> {
                    val major = versionMajor(byRequirement[NODE])
                    if (major in 1 until MIN_NODE_MAJOR) add(nodeAdvice(major))
                }
            }
            if (!passed(FFMPEG)) add(ffmpegAdvice()) else if (!passed(FFPROBE)) add(ffprobeAdvice())
        }

        if (!passed(CHROME)) {
            // Not blocking: the CLI provisions its own headless Chrome, so the only cost of a
            // missing one is a download on the first render.
            logger.info("Nexus Reel: HyperFrames reports no cached Chrome, the first render will download one")
        }

        if (missing.isEmpty()) return true to "ready"
        return false to blocked(missing)
    }

    /**
     * The fallback when the doctor cannot be read: ask node, ffmpeg and ffprobe themselves.
     *
     * Each has to actually run and exit cleanly, because a file on PATH that cannot execute
     * is a far more confusing failure three minutes into a render than it is here.
     */
    private fun probeDirectly(indicator: ProgressIndicator): Pair<Boolean, String> {
        val node = findExecutable("node")
        val major = node?.let { nodeMajor(it) } ?: 0
        val missing = buildList<String> {
            when {
                node == null -> add(nodeAdvice(0))
                major in 1 until MIN_NODE_MAJOR -> add(nodeAdvice(major))
            }
            val ffmpeg = findExecutable("ffmpeg")
            val ffprobe = findExecutable("ffprobe")
            if (ffmpeg == null || !answers(ffmpeg, "-version", indicator)) add(ffmpegAdvice())
            else if (ffprobe == null || !answers(ffprobe, "-version", indicator)) add(ffprobeAdvice())
        }
        if (missing.isEmpty()) return true to "ready"
        return false to blocked(missing)
    }

    /** Render prints a human readable summary rather than JSON, so success is read from the exit code and the file. */
    private fun render(
        composition: File,
        target: File,
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit
    ): Pair<Boolean, String> {
        val command = cli("render", "--quality", "draft", "--output", target.absolutePath)
            ?: return false to CLI_MISSING
        // Run from inside the composition, which is how the CLI finds index.html and assets.
        command.withWorkDirectory(composition)

        val tail = ArrayDeque<String>()
        val handler = OSProcessHandler(command)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val line = event.text.trim()
                if (line.isEmpty()) return
                if (outputType == ProcessOutputTypes.STDERR) logger.info("hyperframes: $line")
                // Kept short so the last thing said before a failure is still in hand.
                tail.addLast(line)
                while (tail.size > TAIL_LINES) tail.removeFirst()
                if (interesting(line)) {
                    indicator.text2 = line
                    onProgress(line)
                }
            }
        })
        handler.startNotify()

        while (!handler.isProcessTerminated) {
            if (indicator.isCanceled) {
                handler.destroyProcess()
                throw ProcessCanceledException()
            }
            handler.waitFor(POLL_MS)
        }

        val exit = handler.exitCode ?: -1
        if (exit == 0) return true to "rendered"
        return false to "The renderer stopped with code $exit: ${tail.joinToString(" / ").take(400)}"
    }

    /**
     * Layer 3 failing is not the end of the road, and the user is told exactly that.
     *
     * [reason] is always written to be read by the person who clicked, naming the one thing
     * that was missing and how to install it: someone who asked for an MP4 and was handed an
     * HTML file is owed a straight answer about why, not a log line they never see.
     */
    private fun fallBackToHtml(
        storyboard: Storyboard,
        reason: String,
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        logger.info("Nexus Reel is falling back to the standalone file: $reason")
        onProgress(reason)
        val file = runCatching { writeStandalone(storyboard) }
            .onFailure { logger.warn("Nexus Reel could not write the fallback page either", it) }
            .getOrNull()

        if (file == null) {
            notify("The reel could not be exported", reason, NotificationType.ERROR, null)
            onError(reason)
            return
        }

        notify(
            "No MP4 this time, but the film is still yours",
            "$reason\n\nThe ${storyboard.audience} cut was written as ${file.name} instead, " +
                "which plays in any browser with nothing installed.",
            NotificationType.WARNING,
            file
        )
        onError("$reason The standalone page was written instead: ${file.name}")
    }

    // ------------------------------------------------------------------ toolchain

    /**
     * What this machine can do, cheaply enough to call while building a menu.
     *
     * Presence is a filesystem lookup, never a process. The Node version does need the
     * binary to answer, so that runs once on a pooled thread and is remembered for the
     * session: a menu is allowed to say "checking" for a moment, never to freeze.
     */
    fun toolchain(): Toolchain {
        probed.get()?.let { return it }

        val node = findExecutable("node")
        val ffmpeg = findExecutable("ffmpeg") != null
        val ffprobe = findExecutable("ffprobe") != null
        if (node == null || !ffmpeg || !ffprobe) {
            // A missing binary is a final answer and needs no process to confirm.
            val settled = describe(node = node != null, ffmpeg = ffmpeg, ffprobe = ffprobe, major = 0)
            probed.set(settled)
            return settled
        }

        if (ApplicationManager.getApplication().isDispatchThread) {
            probeInBackground(node)
            return Toolchain(
                node = true,
                ffmpeg = true,
                canMp4 = false,
                detail = "Checking the Node version. Try again in a moment.",
                ffprobe = true
            )
        }

        val settled = describe(node = true, ffmpeg = true, ffprobe = true, major = nodeMajor(node))
        probed.set(settled)
        return settled
    }

    private fun probeInBackground(node: File) {
        if (!probing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                probed.set(describe(node = true, ffmpeg = true, ffprobe = true, major = nodeMajor(node)))
            } finally {
                probing.set(false)
            }
        }
    }

    private fun describe(node: Boolean, ffmpeg: Boolean, ffprobe: Boolean, major: Int): Toolchain {
        val missing = buildList<String> {
            when {
                !node -> add(nodeAdvice(0))
                major in 1 until MIN_NODE_MAJOR -> add(nodeAdvice(major))
            }
            if (!ffmpeg) add(ffmpegAdvice()) else if (!ffprobe) add(ffprobeAdvice())
        }
        val can = missing.isEmpty()
        val detail = when {
            can && major == 0 -> "Node and ffmpeg are installed."
            can -> "Node $major and ffmpeg are installed."
            else -> missing.joinToString(" ") + " Everything else still works."
        }
        return Toolchain(node = node, ffmpeg = ffmpeg, canMp4 = can, detail = detail, ffprobe = ffprobe)
    }

    // ---------------------------------------------------------- what is missing, in plain words

    /**
     * One sentence a user can act on, never a list of optional extras.
     *
     * The install line is platform aware because "brew install ffmpeg" is useless advice on
     * the two thirds of machines that have no Homebrew.
     */
    private fun blocked(missing: List<String>): String =
        "An MP4 cannot be rendered on this machine yet. " + missing.joinToString(" ")

    private fun nodeAdvice(major: Int): String {
        val head = if (major in 1 until MIN_NODE_MAJOR) {
            "Rendering needs Node $MIN_NODE_MAJOR or newer, and this machine has Node $major."
        } else {
            "Rendering needs Node $MIN_NODE_MAJOR or newer, which is not installed."
        }
        return "$head ${installAdvice("node", "https://nodejs.org")}"
    }

    private fun ffmpegAdvice(): String =
        "Rendering needs ffmpeg, which is not installed. ${installAdvice("ffmpeg", "https://ffmpeg.org/download.html")}"

    private fun ffprobeAdvice(): String =
        "Rendering needs ffprobe, which is missing. It ships with ffmpeg, so reinstalling that brings it back. " +
            installAdvice("ffmpeg", "https://ffmpeg.org/download.html")

    private fun installAdvice(pkg: String, site: String): String = when {
        SystemInfo.isMac -> "Install it with: brew install $pkg (or from $site)."
        SystemInfo.isLinux -> "Install it with your package manager, for example: sudo apt install $pkg (or from $site)."
        else -> "Install it from $site."
    }

    /** 0 when there is no version to read, which is never treated as a block. */
    private fun versionMajor(check: JsonObject?): Int {
        val detail = check?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString ?: return 0
        return Regex("v?(\\d+)\\.\\d+").find(detail)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Whether a binary runs at all. A file on PATH that cannot execute is worth catching here, not mid render. */
    private fun answers(binary: File, argument: String, indicator: ProgressIndicator): Boolean {
        indicator.checkCanceled()
        val command = GeneralCommandLine(binary.absolutePath, argument).withCharset(StandardCharsets.UTF_8)
        applyPath(command)
        val output = runCatching { CapturingProcessHandler(command).runProcess(PROBE_TIMEOUT_MS, true) }
            .onFailure { logger.info("Nexus Reel could not run ${binary.name} $argument: ${it.message}") }
            .getOrNull() ?: return false
        return !output.isTimeout && output.exitCode == 0
    }

    /** 0 when the version could not be read, which is treated as "probably fine" rather than as a block. */
    private fun nodeMajor(node: File): Int {
        val command = GeneralCommandLine(node.absolutePath, "--version").withCharset(StandardCharsets.UTF_8)
        applyPath(command)
        val output = runCatching { CapturingProcessHandler(command).runProcess(VERSION_TIMEOUT_MS, true) }
            .onFailure { logger.info("Nexus Reel could not ask node for its version: ${it.message}") }
            .getOrNull() ?: return 0
        val text = (output.stdout + output.stderr).trim()
        return Regex("v?(\\d+)\\.").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ------------------------------------------------------------------ plumbing

    private fun writeStandalone(storyboard: Storyboard): File =
        CompositionWriter.writeStandalone(storyboard, narrationFor(storyboard.audience), exportDir())

    /**
     * Narration produced by the voice stage, in scene order.
     *
     * Deliberately forgiving about where it landed and what it is called, because a
     * reel with no sound is a far better outcome than an export that refuses to run
     * over a naming disagreement.
     */
    fun narrationFor(audience: String): List<File> {
        val work = ProductModelCache.getInstance(project).workDir()?.toFile() ?: return emptyList()
        for (relative in AUDIO_DIRS) {
            val dir = if (relative.isEmpty()) work else File(work, relative.replace("<audience>", audience))
            if (!dir.isDirectory) continue

            val all = dir.listFiles { file: File ->
                file.isFile && file.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS && file.length() > 0
            }?.toList().orEmpty()
            if (all.isEmpty()) continue

            // In a shared folder, both cuts' narration sits side by side.
            val scoped = all.filter { it.name.contains(audience, ignoreCase = true) }
            val chosen = if (scoped.isNotEmpty()) scoped else all
            return chosen.sortedWith(compareBy({ leadingNumber(it.name) }, { it.name.lowercase(Locale.ROOT) }))
        }
        return emptyList()
    }

    /** `scene-10.mp3` has to sort after `scene-9.mp3`, which a plain name sort gets wrong. */
    private fun leadingNumber(name: String): Int =
        Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * Where finished exports land: a plain folder on the Desktop.
     *
     * It used to write into the project as <project>/yasin-reel/, which is technically
     * tidy and practically useless. A finished film is something the user sends to
     * somebody, so it belongs where they already keep things they are about to send,
     * not buried in a repository they would never think to browse. The first person to
     * try it exported twice and concluded it had not worked, because nothing visible
     * happened.
     *
     * Falls back to the project folder only when there is no Desktop, which is the case
     * on some Linux setups and in CI.
     */
    private fun exportDir(): File {
        val dir = preferredExportDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Nexus Reel could not create ${dir.absolutePath}")
        }
        // Only meaningful for the in-project fallback, and harmless on the Desktop.
        if (dir.name == EXPORT_DIR) {
            val ignore = File(dir, ".gitignore")
            if (!ignore.exists()) {
                runCatching { ignore.writeText("# Nexus Reel exports, regenerated on demand.\n*\n") }
                    .onFailure { logger.info("Nexus Reel could not mark its export folder ignored: ${it.message}") }
            }
        }
        return dir
    }

    private fun preferredExportDir(): File {
        val desktop = File(System.getProperty("user.home"), "Desktop")
        if (desktop.isDirectory) return File(desktop, DESKTOP_EXPORT_DIR)
        val base = project.basePath
            ?: throw IllegalStateException("This project has no folder on disk to export into.")
        return File(base, EXPORT_DIR)
    }

    /** Shown to the user after an export so they know where to look without hunting. */
    fun exportLocation(): String = runCatching { preferredExportDir().absolutePath }
        .getOrDefault("(nowhere writable)")

    /** Intermediates, kept out of the export folder so the user only ever sees finished films. */
    private fun workDir(): File = File(exportDir(), ".work")

    private fun cli(vararg args: String): GeneralCommandLine? {
        val direct = findExecutable("hyperframes")
        val command = when {
            direct != null -> GeneralCommandLine(direct.absolutePath, *args)
            else -> {
                val npx = findExecutable("npx") ?: return null
                // --yes so a machine without the package cached does not stall on a prompt
                // that nobody can see from inside a background task.
                GeneralCommandLine(npx.absolutePath, "--yes", "hyperframes", *args)
            }
        }
        command.withCharset(StandardCharsets.UTF_8)
        applyPath(command)
        return command
    }

    private fun capture(command: GeneralCommandLine, timeoutMs: Int, indicator: ProgressIndicator): String? {
        indicator.checkCanceled()
        val output = runCatching { CapturingProcessHandler(command).runProcess(timeoutMs, true) }
            .onFailure { logger.warn("Nexus Reel could not run ${command.exePath}", it) }
            .getOrNull() ?: return null
        if (output.isTimeout) return null
        return output.stdout + "\n" + output.stderr
    }

    /**
     * The trailing JSON object in a command's output.
     *
     * Read from the end because a CLI is entitled to print a banner or a warning first,
     * and the payload is the last thing it says.
     */
    private fun lastJsonObject(output: String): JsonObject? {
        val end = output.lastIndexOf('}')
        if (end < 0) return null
        var depth = 0
        for (i in end downTo 0) {
            when (output[i]) {
                '}' -> depth++
                '{' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JsonParser.parseString(output.substring(i, end + 1)) as? JsonObject }
                            .getOrNull()
                    }
                }
            }
        }
        return null
    }

    /**
     * Finds a binary the way a terminal would, then in the usual install folders.
     *
     * The second half matters on macOS: an IDE started from the Dock inherits a login
     * PATH that has never heard of Homebrew, so Node is present and invisible.
     */
    private fun findExecutable(name: String): File? {
        PathEnvironmentVariableUtil.findInPath(name)?.let { if (it.canExecute()) return it }
        for (dir in EXTRA_BIN_DIRS) {
            val candidate = File(dir, if (SystemInfo.isWindows) "$name.cmd" else name)
            if (candidate.isFile && candidate.canExecute()) return candidate
            val plain = File(dir, name)
            if (plain.isFile && plain.canExecute()) return plain
        }
        return null
    }

    /** Hands the child the same PATH we searched, or it will not find its own siblings. */
    private fun applyPath(command: GeneralCommandLine) {
        val inherited = EnvironmentUtil.getValue("PATH").orEmpty()
        val separator = File.pathSeparator
        val merged = (EXTRA_BIN_DIRS.filter { File(it).isDirectory } + inherited.split(separator))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator)
        if (merged.isNotBlank()) command.withEnvironment("PATH", merged)
    }

    private fun step(indicator: ProgressIndicator, onProgress: (String) -> Unit, message: String) {
        indicator.checkCanceled()
        indicator.text = message
        indicator.text2 = ""
        logger.info("Nexus Reel export: $message")
        onProgress(message)
    }

    /** Progress lines worth showing. A render prints far more than a human wants to read. */
    private fun interesting(line: String): Boolean =
        line.contains('%') ||
            INTERESTING.any { line.contains(it, ignoreCase = true) }

    private fun notify(title: String, content: String, type: NotificationType, file: File?) {
        // Asked first, because getNotificationGroup logs an error for an unregistered id.
        // A missing line of plugin.xml should cost a balloon's grouping, never the
        // user's only feedback that their film is ready.
        val manager = runCatching { NotificationGroupManager.getInstance() }.getOrNull()
        val group = manager?.takeIf { it.isGroupRegistered(GROUP_ID) }?.getNotificationGroup(GROUP_ID)
        val notification = group?.createNotification(title, content, type)
            ?: Notification(GROUP_ID, title, content, type)

        if (file != null && RevealFileAction.isSupported()) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(revealLabel()) { RevealFileAction.openFile(file) }
            )
        }
        if (file != null) {
            notification.addAction(
                NotificationAction.createSimpleExpiring("Copy Path") {
                    CopyPasteManager.getInstance().setContents(StringSelection(file.absolutePath))
                }
            )
        }
        notification.notify(project)
    }

    /** The platform knows what the file manager is called here, and it is translated. */
    private fun revealLabel(): String = runCatching { RevealFileAction.getActionName() }.getOrNull()
        ?: when {
            SystemInfo.isMac -> "Reveal in Finder"
            SystemInfo.isWindows -> "Show in Explorer"
            else -> "Show in Files"
        }

    private fun onEdt(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDisposed) return
        application.invokeLater(
            Runnable { if (!project.isDisposed) block() },
            ModalityState.any()
        )
    }

    companion object {
        /** Beside the project, not inside `.idea/`, because an export is meant to be found. */
        const val EXPORT_DIR = "yasin-reel"

        /** A name a person recognises in Finder, sitting where they already look. */
        const val DESKTOP_EXPORT_DIR = "Nexus Reel"

        const val GROUP_ID = "Nexus Reel"

        private const val MIN_NODE_MAJOR = 22
        private const val DOCTOR_TIMEOUT_MS = 90_000
        private const val VERSION_TIMEOUT_MS = 4_000

        /** ffmpeg prints its whole build configuration for `-version`, which is not instant on a cold cache. */
        private const val PROBE_TIMEOUT_MS = 10_000
        private const val POLL_MS = 200L
        private const val TAIL_LINES = 12

        private const val CLI_MISSING =
            "An MP4 cannot be rendered on this machine yet. Neither hyperframes nor npx could be found, " +
                "so there is nothing to render with. Installing Node from https://nodejs.org brings npx with it."

        private const val NODE = "node"
        private const val FFMPEG = "ffmpeg"
        private const val FFPROBE = "ffprobe"
        private const val CHROME = "chrome"

        /**
         * Doctor check names, lowercased, mapped onto the requirement they report.
         *
         * Spelled out rather than matched loosely because the payload also carries checks
         * whose names contain these words but mean something else, and because the CLI has
         * renamed checks between versions. Anything not listed here (whisper-cpp, Kokoro,
         * MusicGen, Docker, disk, memory, telemetry) is an optional extra a render never
         * touches, and is ignored.
         */
        private val REQUIREMENT_NAMES = mapOf(
            "node.js" to NODE, "node" to NODE, "nodejs" to NODE,
            "ffmpeg" to FFMPEG,
            "ffprobe" to FFPROBE,
            "chrome" to CHROME, "chromium" to CHROME, "browser" to CHROME
        )

        /** Chrome is absent on purpose: the CLI downloads and manages its own copy. */
        private val BLOCKING = setOf(NODE, FFMPEG, FFPROBE)

        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "ogg", "opus", "aac")

        /** Searched in order. The first folder that holds audio at all wins. */
        private val AUDIO_DIRS = listOf(
            "audio/<audience>", "voice/<audience>", "narration/<audience>",
            "audio", "voice", "narration", ""
        )

        private val EXTRA_BIN_DIRS = listOf(
            "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin",
            System.getProperty("user.home") + "/.nvm/current/bin",
            System.getProperty("user.home") + "/.volta/bin",
            System.getProperty("user.home") + "/.local/bin"
        )

        private val INTERESTING = listOf(
            "frame", "render", "encod", "audio", "composition", "output", "done", "warn", "error", "fail"
        )

        fun getInstance(project: Project): ReelExporter = project.service()
    }
}
