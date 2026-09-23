package com.example

import com.example.activity.ActivityRequest
import com.example.activity.ActivityService
import com.example.trust.TrustGraphBridge
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {

    private val logger = Logger.getInstance(MyToolWindowFactory::class.java)
    private val gson = Gson()

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow
    ) {
        val projectName = project.name
        val projectPath = project.basePath ?: "Unavailable"

        logger.info("Project detected:\nName: $projectName\nPath: $projectPath")

        val panel = JPanel(BorderLayout())
        var browserToDispose: JBCefBrowser? = null

        if (JBCefApp.isSupported()) {

            val browser = JBCefBrowser()
            browserToDispose = browser

            // Nexus Reel and Nexus Deck are panels inside this page now, not tool window
            // tabs of their own, so the product has one row of views instead of two. They
            // attach their own bridge to this browser and everything they add lives in
            // NexusEmbed; nothing below this line knows or cares that they are there.
            // Must be before loadURL: CEF installs the message router with the browser.
            com.example.yasinreel.render.NexusEmbed.attach(project, browser)
            val encodedProjectName = URLEncoder.encode(projectName, StandardCharsets.UTF_8)
            val encodedProjectPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8)
            val visualizerUrl =
                "http://localhost:5173?projectName=$encodedProjectName&projectPath=$encodedProjectPath&host=intellij"

            // Lets the page call back into the IDE, which the activity tab needs to request a summary.
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            query.addHandler { request ->
                handleMessage(project, browser, request)
                null
            }

            browser.jbCefClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(
                        cefBrowser: CefBrowser,
                        frame: CefFrame,
                        httpStatusCode: Int
                    ) {
                        if (!frame.isMain) return
                        installBridge(browser, query)
                        pushActivityMeta(project, browser)
                        analyzeProject(project, browser)
                        // Colours the diagram by what has actually executed. Additive: the
                        // page ignores the event if it does not handle it.
                        TrustGraphBridge.attach(project, browser)
                    }
                },
                browser.cefBrowser
            )

            browser.loadURL(visualizerUrl)

            panel.add(
                browser.component,
                BorderLayout.CENTER
            )

        } else {

            panel.add(
                JBLabel("JCEF is not supported."),
                BorderLayout.CENTER
            )
        }

        val content = ContentFactory
            .getInstance()
            .createContent(panel, "", false)

        browserToDispose?.let(content::setDisposer)

        toolWindow.contentManager.addContent(content)
    }

    /** Exposes the post function the page uses to reach the IDE. */
    private fun installBridge(browser: JBCefBrowser, query: JBCefJSQuery) {
        browser.cefBrowser.executeJavaScript(
            """
            window.__CODE_VISUALIZER_HOST__ = { post: function (message) { ${query.inject("message")} } };
            window.dispatchEvent(new CustomEvent('code-visualizer:host-ready'));
            """.trimIndent(),
            browser.cefBrowser.url,
            0
        )
    }

    private fun handleMessage(project: Project, browser: JBCefBrowser, raw: String) {
        val message = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        when (message.get("type")?.asString) {
            "activity" -> summarizeActivity(
                project = project,
                browser = browser,
                since = message.get("since")?.asString ?: return,
                until = message.get("until")?.asString ?: return,
                scope = message.get("scope")?.takeIf { !it.isJsonNull }?.asString ?: ActivityRequest.ALL,
                mine = message.get("mine")?.asBoolean ?: true,
                includeUncommitted = message.get("uncommitted")?.asBoolean ?: true
            )
            "open" -> {
                val path = message.get("filePath")?.asString ?: return
                val line = message.get("line")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                ApplicationManager.getApplication().invokeLater({ openSource(project, path, line) }, ModalityState.any())
            }
        }
    }

    private fun openSource(project: Project, relativePath: String, line: Int) {
        if (project.isDisposed) return
        val base = project.basePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath("$base/$relativePath")
            ?: LocalFileSystem.getInstance().findFileByPath(relativePath)
            ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    /**
     * Reading git and calling the model both block, so they run off the UI thread. The result always
     * comes back, as a report or as an error report the tab can show.
     */
    private fun summarizeActivity(
        project: Project,
        browser: JBCefBrowser,
        since: String,
        until: String,
        scope: String,
        mine: Boolean,
        includeUncommitted: Boolean
    ) {
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed) return@execute
            val service = ActivityService.getInstance(project)
            val report = service.report(
                ActivityRequest(
                    since = since,
                    until = until,
                    scope = scope,
                    authorEmail = if (mine) service.currentUserEmail().takeIf { it.isNotBlank() } else null,
                    includeUncommitted = includeUncommitted
                )
            )
            val json = gson.toJson(report)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                browser.cefBrowser.executeJavaScript(
                    "window.dispatchEvent(new CustomEvent('code-visualizer:activity', { detail: $json }));",
                    browser.cefBrowser.url,
                    0
                )
            }, ModalityState.any())
        }
    }

    /** The scope dropdown is filled from the project layout, so it needs no configuration. */
    private fun pushActivityMeta(project: Project, browser: JBCefBrowser) {
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed) return@execute
            val service = ActivityService.getInstance(project)
            val detail = runCatching {
                gson.toJson(mapOf("scopes" to service.scopes(), "gitAvailable" to service.isRepository()))
            }.getOrElse { return@execute }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                browser.cefBrowser.executeJavaScript(
                    "window.__CODE_VISUALIZER_ACTIVITY_META__ = $detail;" +
                        "window.dispatchEvent(new CustomEvent('code-visualizer:activity-meta', { detail: $detail }));",
                    browser.cefBrowser.url,
                    0
                )
            }, ModalityState.any())
        }
    }

    private fun analyzeProject(project: Project, browser: JBCefBrowser) {
        ReadAction.nonBlocking<ProjectGraph> {
            runCatching { ProjectFlowAnalyzer.analyze(project) }
                .onFailure { logger.warn("Code Visualizer project analysis failed", it) }
                .getOrElse {
                    ProjectGraph(
                        projectName = project.name,
                        projectPath = project.basePath ?: "Unavailable",
                        status = "error",
                        message = it.message ?: "Project analysis failed.",
                        nodes = emptyList(),
                        edges = emptyList(),
                        stats = GraphStats(0, 0, 0, 0)
                    )
                }
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { graph ->
                logger.info(
                    "Code Visualizer analysis completed: " +
                        "files=${graph.stats.scannedFiles}, " +
                        "calls=${graph.stats.frontendCalls}, " +
                        "routes=${graph.stats.backendEndpoints}, " +
                        "matched=${graph.stats.matchedCalls}"
                )
                val graphJson = gson.toJson(graph)
                browser.cefBrowser.executeJavaScript(
                    """
                    window.__CODE_VISUALIZER_GRAPH__ = $graphJson;
                    window.dispatchEvent(new CustomEvent('code-visualizer:graph', { detail: $graphJson }));
                    """.trimIndent(),
                    browser.cefBrowser.url,
                    0
                )
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
