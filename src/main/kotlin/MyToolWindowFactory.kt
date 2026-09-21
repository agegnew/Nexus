package com.example

import com.google.gson.Gson
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
import com.intellij.ui.jcef.JBCefBrowser
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
            val encodedProjectName = URLEncoder.encode(projectName, StandardCharsets.UTF_8)
            val encodedProjectPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8)
            val visualizerUrl =
                "http://localhost:5173?projectName=$encodedProjectName&projectPath=$encodedProjectPath"

            browser.jbCefClient.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(
                        cefBrowser: CefBrowser,
                        frame: CefFrame,
                        httpStatusCode: Int
                    ) {
                        if (!frame.isMain) return
                        analyzeProject(project, browser)
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
