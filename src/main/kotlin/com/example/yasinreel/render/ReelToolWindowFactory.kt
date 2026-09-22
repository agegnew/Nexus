package com.example.yasinreel.render

import com.example.yasinreel.harvest.ChangedFiles
import com.example.yasinreel.model.Audience
import com.example.yasinreel.model.ReelScope
import com.example.yasinreel.model.Storyboard
import com.example.yasinreel.settings.KeyDiagnosis
import com.example.yasinreel.settings.ReelSettings
import com.example.yasinreel.tts.TtsClient
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel

/**
 * Hosts the Nexus Reel player.
 *
 * Registered as its own tool window rather than as a second tab inside
 * `Code Visualizer`, so `MyToolWindowFactory.kt` is never edited and this work
 * cannot conflict with the rest of the team's.
 *
 * It also owns the bridge in both directions: the page asks for a cut, for a file
 * to be opened or for an export through a [JBCefJSQuery], and results are pushed back
 * as DOM events. Going through the query rather than a polled endpoint is what lets a
 * click on a path mid playback land in the editor at the right line.
 */
/**
 * [DumbAware] because the player is an HTML page in an embedded browser and needs no
 * indexes to draw. Without it the platform replaces the whole tab with "the view is not
 * available until indexes are built" for as long as a large project indexes, which hides
 * a UI that was perfectly capable of showing itself. Only the harvest needs indexes, and
 * ReelPipeline already gates that on smart mode.
 */
class ReelToolWindowFactory : ToolWindowFactory, DumbAware {

    private val logger = Logger.getInstance(ReelToolWindowFactory::class.java)
    private val gson = Gson()

    /**
     * The last storyboard delivered per audience, per project.
     *
     * The export message from the page carries only an audience, but [ReelExporter]
     * needs the object. One factory instance serves every open project, so the project
     * has to be part of the key or two windows would export each other's films.
     */
    private val delivered = ConcurrentHashMap<String, Storyboard>()

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        var disposer: Disposable? = null

        if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()

            // Created before the page is loaded: the message router is installed when the
            // browser is, so a query made later would never reach the running page.
            // The cast picks the JBCefBrowserBase overload, the JBCefBrowser one is deprecated.
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            query.addHandler { payload ->
                handleBridgeMessage(project, browser, payload)
                null
            }

            browser.jbCefClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                        if (!frame.isMain) return
                        injectBridge(cefBrowser, query)
                    }
                },
                browser.cefBrowser
            )

            val name = URLEncoder.encode(project.name, StandardCharsets.UTF_8)
            val path = URLEncoder.encode(project.basePath ?: "", StandardCharsets.UTF_8)
            val server = ReelServer.getInstance()
            // Narration lives in the project, not in the plugin jar, so the server is told
            // where to find it at the same moment the page learns where to ask.
            server.serveTtsFrom(project.basePath)
            val url = "${server.baseUrl()}/index.html?projectName=$name&projectPath=$path"

            logger.info("Nexus Reel loading player from $url")
            browser.loadURL(url)
            panel.add(browser.component, BorderLayout.CENTER)

            disposer = Disposable {
                delivered.keys.removeIf { it.startsWith(projectKey(project)) }
                // The query holds a native handler registered against the browser, so it
                // has to go first or disposal order leaves a dangling callback.
                Disposer.dispose(query)
                Disposer.dispose(browser)
            }
        } else {
            panel.add(
                JBLabel("JCEF is not supported in this IDE, so Nexus Reel cannot play here."),
                BorderLayout.CENTER
            )
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        disposer?.let(content::setDisposer)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Defines the single function the page calls to reach Kotlin.
     *
     * The body has to come from [JBCefJSQuery.inject] because it carries the query id
     * and port that CEF routes on, neither of which is known until the query is made.
     */
    private fun injectBridge(cefBrowser: CefBrowser, query: JBCefJSQuery) {
        val js = """
            window.__reelCallback = function (payload) {
                ${query.inject("payload")}
            };
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
        logger.info("Nexus Reel bridge injected into the player page")
    }

    private fun handleBridgeMessage(project: Project, browser: JBCefBrowser, payload: String?) {
        val message = runCatching { JsonParser.parseString(payload.orEmpty()) as? JsonObject }
            .onFailure { logger.warn("Nexus Reel could not parse a bridge message: $payload", it) }
            .getOrNull() ?: return

        when (val type = message.stringOrNull("type")) {
            "generate" -> generate(
                project,
                browser,
                message.stringOrNull("audience") ?: Audience.TECHNICAL,
                scopeOf(message)
            )
            "scopes" -> sendScopes(project, browser)
            "openFile" -> openInEditor(project, message.stringOrNull("file"), message.intOrNull("line") ?: 1)
            "export" -> export(
                project,
                browser,
                message.stringOrNull("audience") ?: Audience.TECHNICAL,
                message.stringOrNull("format") ?: FORMAT_HTML
            )
            "toolchain" -> toolchain(project, browser)
            "reveal" -> revealInFinder(message.stringOrNull("path"))
            "ready" -> logger.info("Nexus Reel player reported ready")
            else -> logger.warn("Nexus Reel ignored an unknown bridge message of type $type")
        }
    }

    /**
     * Opens the exported file in the OS file manager.
     *
     * The balloon notification already offers this, but balloons expire in a few seconds
     * and the first person to use export concluded it had silently failed. A button that
     * stays on screen next to the confirmation is the version that actually gets used.
     */
    private fun revealInFinder(path: String?) {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (!file.exists()) {
            logger.warn("Nexus Reel was asked to reveal a file that is not there: $path")
            return
        }
        ApplicationManager.getApplication().invokeLater { RevealFileAction.openFile(file) }
    }

    /**
     * Reads the recap controls off a generate message. Anything missing or unreadable falls back
     * to a launch reel, so an older player that knows nothing about ranges still works.
     */
    private fun scopeOf(message: JsonObject): ReelScope {
        val kind = message.stringOrNull("scope") ?: ReelScope.LAUNCH
        if (kind != ReelScope.RECAP) return ReelScope.launch()
        val since = message.stringOrNull("since").orEmpty()
        val until = message.stringOrNull("until").orEmpty()
        if (since.isBlank() || until.isBlank()) return ReelScope.launch()
        return ReelScope(
            kind = ReelScope.RECAP,
            since = since,
            until = until,
            area = message.stringOrNull("area") ?: "all",
            mine = message.boolOrNull("mine") ?: true,
            includeUncommitted = message.boolOrNull("uncommitted") ?: true
        )
    }

    /** The player asks for the scope list once it loads, so the area picker matches the project. */
    private fun sendScopes(project: Project, browser: JBCefBrowser) {
        val areas = runCatching { ChangedFiles.areas(project) }.getOrDefault(emptyList())
        val detail = gson.toJson(mapOf("areas" to areas))
        dispatch(browser, EVENT_SCOPES, detail)
    }

    private fun generate(project: Project, browser: JBCefBrowser, audience: String, scope: ReelScope) {
        logger.info("Nexus Reel generating the $audience cut (${scope.kind}) on request from the player")
        ReelPipeline.getInstance(project).generate(
            audience = audience,
            scope = scope,
            onProgress = { message -> dispatch(browser, EVENT_PROGRESS, detail(audience, message)) },
            onDone = { storyboard, clips -> deliver(project, browser, storyboard, clips) },
            onError = { message -> dispatch(browser, EVENT_ERROR, detail(audience, message)) },
            onNotice = { diagnosis, aiWrote -> notice(browser, audience, diagnosis, aiWrote) },
            targetMs = targetMs()
        )
    }

    /**
     * The configured run length, or the pipeline's own default.
     *
     * Read here rather than inside the pipeline because a settings service that fails
     * to load must cost a preference, never the reel.
     */
    private fun targetMs(): Int =
        runCatching { ReelSettings.getInstance().targetMs }
            .onFailure { logger.warn("Nexus Reel could not read the configured reel length", it) }
            .getOrDefault(ReelPipeline.DEFAULT_TARGET_MS)

    /**
     * Sends the film, with its narration attached.
     *
     * The audio list rides alongside `scenes` rather than inside them because
     * `Storyboard` is a frozen contract shared with the validator and the exporter, and
     * a per scene field would have had to go through all three.
     */
    private fun deliver(
        project: Project,
        browser: JBCefBrowser,
        storyboard: Storyboard,
        clips: List<TtsClient.SceneAudio>
    ) {
        logger.info(
            "Nexus Reel delivering the ${storyboard.audience} cut, ${storyboard.scenes.size} scenes, " +
                "${clips.size} narration clips"
        )
        delivered[key(project, storyboard.audience)] = storyboard

        val payload = gson.toJsonTree(storyboard).asJsonObject
        val audio = JsonArray()
        clips.forEach { clip ->
            audio.add(
                JsonObject().apply {
                    addProperty("sceneIndex", clip.sceneIndex)
                    addProperty("url", clip.relativeUrl)
                    clip.durationMs?.let { addProperty("durationMs", it) }
                }
            )
        }
        payload.add("audio", audio)
        dispatch(browser, EVENT_STORYBOARD, gson.toJson(payload))
    }

    /**
     * The honesty banner.
     *
     * [aiWrote] is the fact that matters, not [KeyDiagnosis.aiRan]: a run can find a
     * working key, understand the project with it and still fall back because directing
     * threw, and that reel is just as much a machine written one.
     */
    private fun notice(browser: JBCefBrowser, audience: String, diagnosis: KeyDiagnosis, aiWrote: Boolean) {
        val ran = aiWrote && diagnosis.aiRan

        // Three cases, and only the middle one needs words of its own. KeyDiagnosis can
        // only speak about the key, so when the key worked and the film still came from
        // the offline director its sentence ("The AI wrote this reel") would contradict
        // the headline above it. That contradiction is worse than no banner at all.
        val message: String
        val remedy: String
        when {
            ran -> {
                message = diagnosis.plainSentence()
                remedy = diagnosis.remedy()
            }
            diagnosis.aiRan -> {
                message = "The AI was reached, but what it sent back could not be used, " +
                    "so this reel was built from the code alone."
                remedy = "Build the cut again. That usually fixes it."
            }
            else -> {
                message = diagnosis.plainSentence()
                remedy = diagnosis.remedy()
            }
        }

        // diagnosis.lastError stays in the log. It is the provider's own wording, and the
        // page is the one place it could be seen by someone it was never written for.
        logger.info(
            "Nexus Reel $audience cut: ai wrote it: $ran, key worked: ${diagnosis.aiRan}, " +
                "sources tried: ${diagnosis.tried}"
        )
        dispatch(
            browser,
            EVENT_NOTICE,
            gson.toJson(
                mapOf(
                    "audience" to audience,
                    "aiRan" to ran,
                    "headline" to if (ran) diagnosis.headline() else "The AI did not write this cut",
                    "message" to message,
                    "remedy" to remedy,
                    "sources" to diagnosis.tried
                )
            )
        )
    }

    private fun export(project: Project, browser: JBCefBrowser, audience: String, format: String) {
        val storyboard = storyboardFor(project, audience)
        if (storyboard == null) {
            dispatch(browser, EVENT_EXPORT_ERROR, exportDetail(audience, format, "Build the $audience cut first, then export it."))
            return
        }

        val exporter = ReelExporter.getInstance(project)
        if (format == FORMAT_MP4) {
            exporter.exportMp4(
                storyboard,
                onProgress = { m -> dispatch(browser, EVENT_EXPORT_PROGRESS, exportDetail(audience, format, m)) },
                onDone = { file -> dispatch(browser, EVENT_EXPORT_DONE, exportDetail(audience, format, "Saved the video.", file)) },
                onError = { m -> dispatch(browser, EVENT_EXPORT_ERROR, exportDetail(audience, format, m)) }
            )
            return
        }

        dispatch(browser, EVENT_EXPORT_PROGRESS, exportDetail(audience, FORMAT_HTML, "Writing a standalone page."))
        exporter.exportHtml(
            storyboard,
            onDone = { file -> dispatch(browser, EVENT_EXPORT_DONE, exportDetail(audience, FORMAT_HTML, "Saved the page.", file)) },
            onError = { m -> dispatch(browser, EVENT_EXPORT_ERROR, exportDetail(audience, FORMAT_HTML, m)) }
        )
    }

    /**
     * Whether this machine can render an MP4 at all.
     *
     * The first call on the EDT deliberately returns an unsettled answer and refreshes in
     * the background, so the player asks again every time the menu is opened rather than
     * once at load.
     */
    private fun toolchain(project: Project, browser: JBCefBrowser) {
        val chain = runCatching { ReelExporter.getInstance(project).toolchain() }
            .onFailure { logger.warn("Nexus Reel could not inspect the export toolchain", it) }
            .getOrNull() ?: ReelExporter.Toolchain(
            node = false,
            ffmpeg = false,
            canMp4 = false,
            detail = "Video export is unavailable in this IDE."
        )
        dispatch(browser, EVENT_TOOLCHAIN, gson.toJson(chain))
    }

    /**
     * The cut the page wants to export.
     *
     * Falls back to the storyboard the pipeline wrote to disk, so a cut built before the
     * IDE was restarted is still exportable without rebuilding it.
     */
    private fun storyboardFor(project: Project, audience: String): Storyboard? {
        delivered[key(project, audience)]?.let { return it }

        val dir = ProductModelCache.getInstance(project).workDir() ?: return null
        val file = dir.resolve("storyboard-${fileSafe(audience)}.json").toFile()
        if (!file.isFile) return null
        // Gson builds the object without running the constructor, so a truncated file can
        // leave a null in a field Kotlin swore was not nullable. Reading a scene out of it
        // is what proves it survived, and that has to happen inside the catch.
        return runCatching {
            val parsed = gson.fromJson(file.readText(StandardCharsets.UTF_8), Storyboard::class.java)
            if (parsed.scenes.isEmpty() || parsed.audience.isBlank()) null else parsed
        }
            .onFailure { logger.warn("Nexus Reel could not read ${file.absolutePath}", it) }
            .getOrNull()
            ?.also { delivered[key(project, audience)] = it }
    }

    /**
     * Sends one event to the page.
     *
     * [detailJson] is always produced by Gson rather than string concatenation, so a
     * quote or a newline in a narration line cannot break out and corrupt the script.
     */
    private fun dispatch(browser: JBCefBrowser, event: String, detailJson: String) {
        ApplicationManager.getApplication().invokeLater(
            Runnable {
                val cefBrowser = browser.cefBrowser
                cefBrowser.executeJavaScript(
                    "window.dispatchEvent(new CustomEvent('$event', { detail: $detailJson }));",
                    cefBrowser.url,
                    0
                )
            },
            ModalityState.any()
        )
    }

    private fun openInEditor(project: Project, reference: String?, line: Int) {
        if (reference.isNullOrBlank()) return

        // Evidence records repository relative paths, but an absolute one is accepted too
        // so a hand written source ref still works.
        val candidate = File(reference)
        val absolute = if (candidate.isAbsolute) candidate else File(project.basePath ?: return, reference)
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(absolute.path))
        if (virtualFile == null) {
            logger.warn("Nexus Reel click to source found no file at ${absolute.path}")
            return
        }

        ApplicationManager.getApplication().invokeLater(
            Runnable {
                if (project.isDisposed) return@Runnable
                val descriptor = OpenFileDescriptor(project, virtualFile, (line - 1).coerceAtLeast(0), 0)
                val editors = FileEditorManager.getInstance(project)
                if (editors.openTextEditor(descriptor, true) == null) {
                    // Not every source ref points at text, an image or a binary still opens.
                    editors.openFile(virtualFile, true)
                }
                // The reel is playing in a tool window, so without raising the frame the
                // jump happens behind whatever the user is looking at.
                WindowManager.getInstance().getFrame(project)?.toFront()
            },
            // Opening an editor changes the model, so it runs under the caller's real
            // modality rather than any(), which is only safe for read only work.
            ModalityState.defaultModalityState()
        )
    }

    private fun detail(audience: String, message: String): String =
        gson.toJson(mapOf("audience" to audience, "message" to message))

    private fun exportDetail(audience: String, format: String, message: String, file: File? = null): String {
        val fields = linkedMapOf<String, String>(
            "audience" to audience,
            "format" to format,
            "message" to message
        )
        file?.let { fields["path"] = it.absolutePath }
        return gson.toJson(fields)
    }

    private fun projectKey(project: Project): String = project.locationHash + "|"

    private fun key(project: Project, audience: String): String = projectKey(project) + audience

    private fun fileSafe(audience: String): String =
        audience.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "cut" }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    private fun JsonObject.boolOrNull(name: String): Boolean? {
        val element = get(name) ?: return null
        return runCatching { element.asBoolean }.getOrNull()
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        val element = get(name) ?: return null
        return runCatching { element.asInt }.getOrNull()
    }

    private companion object {
        const val EVENT_STORYBOARD = "yasin-reel:storyboard"
        const val EVENT_PROGRESS = "yasin-reel:progress"
        const val EVENT_ERROR = "yasin-reel:error"

        /** The area list for the recap picker, sent when the player reports ready. */
        const val EVENT_SCOPES = "yasin-reel:scopes"
        const val EVENT_NOTICE = "yasin-reel:notice"
        const val EVENT_TOOLCHAIN = "yasin-reel:toolchain"
        const val EVENT_EXPORT_PROGRESS = "yasin-reel:export-progress"
        const val EVENT_EXPORT_DONE = "yasin-reel:export-done"
        const val EVENT_EXPORT_ERROR = "yasin-reel:export-error"

        const val FORMAT_HTML = "html"
        const val FORMAT_MP4 = "mp4"
    }
}
