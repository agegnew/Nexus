package com.example

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI

data class ProjectGraph(
    val projectName: String,
    val projectPath: String,
    val status: String,
    val message: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val stats: GraphStats
)

data class GraphNode(
    val id: String,
    val kind: String,
    val label: String,
    val subtitle: String,
    val technology: String? = null,
    val filePath: String? = null,
    val line: Int? = null
)

data class GraphEdge(
    val id: String,
    val source: String,
    val target: String,
    val label: String
)

data class GraphStats(
    val scannedFiles: Int,
    val frontendCalls: Int,
    val backendEndpoints: Int,
    val matchedCalls: Int
)

object ProjectFlowAnalyzer {

    private const val MAX_FILE_SIZE = 1_000_000L
    private const val MAX_RESULTS = 200

    private val frontendExtensions = setOf("js", "jsx", "ts", "tsx")
    private val backendExtensions = setOf("java", "kt", "py")

    private val axiosCallPattern = Regex(
        """axios\s*\.\s*(get|post|put|patch|delete|head|options)\s*\(\s*([\"'`])(.+?)\2""",
        RegexOption.IGNORE_CASE
    )
    private val fetchCallPattern = Regex(
        """fetch\s*\(\s*([\"'`])(.+?)\1\s*(?:,\s*\{([\s\S]{0,400}?)})?""",
        RegexOption.IGNORE_CASE
    )
    private val httpWrapperPattern = Regex(
        """\bhttp(?:<[^>]+>)?\s*\(\s*([\"'`])(.+?)\1\s*(?:,\s*\{([\s\S]{0,400}?)})?""",
        RegexOption.IGNORE_CASE
    )
    private val fetchMethodPattern = Regex(
        """method\s*:\s*[\"'](GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)[\"']""",
        RegexOption.IGNORE_CASE
    )
    private val springClassPattern = Regex(
        """(?:@RequestMapping\s*\(\s*(?:(?:value|path)\s*=\s*)?[\"']([^\"']*)[\"'][^)]*\)\s*)?(?:public\s+)?(?:class|interface)\s+(\w+)"""
    )
    private val springEndpointPattern = Regex(
        """@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\s*(?:\(([^)]*)\))?\s*(?:public\s+|private\s+|protected\s+|suspend\s+|open\s+|final\s+|override\s+|static\s+|\s)*(?:fun\s+(\w+)|(?:[\w<>,.?\[\]]+\s+)+(\w+)\s*\()""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private val annotationPathPattern = Regex(
        """(?:(?:value|path)\s*=\s*)?[\"']([^\"']*)[\"']"""
    )
    private val requestMethodPattern = Regex(
        """RequestMethod\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)""",
        RegexOption.IGNORE_CASE
    )
    private val fastApiPrefixPattern = Regex(
        """APIRouter\s*\([\s\S]{0,300}?prefix\s*=\s*[\"']([^\"']+)[\"']"""
    )
    private val fastApiEndpointPattern = Regex(
        """@(router|app)\.(get|post|put|patch|delete|head|options)\s*\(\s*[\"']([^\"']*)[\"'][\s\S]{0,500}?\)\s*(?:async\s+)?def\s+(\w+)\s*\(""",
        RegexOption.IGNORE_CASE
    )

    fun analyze(project: Project): ProjectGraph {
        val projectPath = project.basePath ?: "Unavailable"
        val sourceFiles = collectSourceFiles(project)
        val frontendCalls = sourceFiles
            .asSequence()
            .filter { it.extension?.lowercase() in frontendExtensions }
            .flatMap { findFrontendCalls(it, projectPath).asSequence() }
            .take(MAX_RESULTS)
            .toList()
        val backendEndpoints = sourceFiles
            .asSequence()
            .filter { it.extension?.lowercase() in backendExtensions }
            .flatMap { file ->
                when (file.extension?.lowercase()) {
                    "py" -> findFastApiEndpoints(file, projectPath).asSequence()
                    else -> findSpringEndpoints(file, projectPath).asSequence()
                }
            }
            .take(MAX_RESULTS)
            .toList()

        return buildGraph(
            projectName = project.name,
            projectPath = projectPath,
            scannedFiles = sourceFiles.size,
            frontendCalls = frontendCalls,
            backendEndpoints = backendEndpoints
        )
    }

    private fun collectSourceFiles(project: Project): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory &&
                file.length <= MAX_FILE_SIZE &&
                file.extension?.lowercase() in frontendExtensions + backendExtensions
            ) {
                files.add(file)
            }
            true
        }
        return files
    }

    private fun findFrontendCalls(file: VirtualFile, projectPath: String): List<FrontendCall> {
        val text = loadText(file) ?: return emptyList()
        val relativePath = relativePath(file, projectPath)
        val calls = mutableListOf<FrontendCall>()

        axiosCallPattern.findAll(text).forEach { match ->
            val rawUrl = match.groupValues[3]
            calls.add(
                FrontendCall(
                    method = match.groupValues[1].uppercase(),
                    path = normalizeRequestPath(rawUrl),
                    rawUrl = rawUrl,
                    filePath = relativePath,
                    line = lineNumber(text, match.range.first)
                )
            )
        }

        fetchCallPattern.findAll(text).forEach { match ->
            val rawUrl = match.groupValues[2]
            val options = match.groupValues.getOrElse(3) { "" }
            val method = fetchMethodPattern.find(options)?.groupValues?.get(1)?.uppercase() ?: "GET"
            calls.add(
                FrontendCall(
                    method = method,
                    path = normalizeRequestPath(rawUrl),
                    rawUrl = rawUrl,
                    filePath = relativePath,
                    line = lineNumber(text, match.range.first)
                )
            )
        }

        httpWrapperPattern.findAll(text).forEach { match ->
            val rawUrl = match.groupValues[2]
            val options = match.groupValues.getOrElse(3) { "" }
            val method = fetchMethodPattern.find(options)?.groupValues?.get(1)?.uppercase() ?: "GET"
            calls.add(
                FrontendCall(
                    method = method,
                    path = normalizeRequestPath(rawUrl),
                    rawUrl = rawUrl,
                    filePath = relativePath,
                    line = lineNumber(text, match.range.first)
                )
            )
        }

        return calls.distinctBy { "${it.method}:${it.rawUrl}:${it.filePath}:${it.line}" }
    }

    private fun findSpringEndpoints(file: VirtualFile, projectPath: String): List<BackendEndpoint> {
        val text = loadText(file) ?: return emptyList()
        if (!text.contains("Mapping")) return emptyList()

        val relativePath = relativePath(file, projectPath)
        val classMatch = springClassPattern.find(text)
        val classPath = classMatch?.groupValues?.get(1).orEmpty()
        val className = classMatch?.groupValues?.get(2)?.ifBlank { file.nameWithoutExtension }
            ?: file.nameWithoutExtension

        return springEndpointPattern.findAll(text).map { match ->
            val annotation = match.groupValues[1]
            val arguments = match.groupValues[2]
            val methodName = match.groupValues[3].ifBlank { match.groupValues[4] }
            val method = when (annotation.lowercase()) {
                "getmapping" -> "GET"
                "postmapping" -> "POST"
                "putmapping" -> "PUT"
                "patchmapping" -> "PATCH"
                "deletemapping" -> "DELETE"
                else -> requestMethodPattern.find(arguments)?.groupValues?.get(1)?.uppercase() ?: "ANY"
            }
            val methodPath = annotationPathPattern.find(arguments)?.groupValues?.get(1).orEmpty()

            BackendEndpoint(
                method = method,
                path = normalizeRoutePath(classPath, methodPath),
                controller = className,
                handler = methodName.ifBlank { "handler" },
                framework = "Spring",
                filePath = relativePath,
                line = lineNumber(text, match.range.first)
            )
        }.distinctBy { "${it.method}:${it.path}:${it.filePath}:${it.line}" }.toList()
    }

    private fun findFastApiEndpoints(file: VirtualFile, projectPath: String): List<BackendEndpoint> {
        val text = loadText(file) ?: return emptyList()
        if (!text.contains("APIRouter") && !text.contains("@app.")) return emptyList()

        val relativePath = relativePath(file, projectPath)
        val routerPrefix = fastApiPrefixPattern.find(text)?.groupValues?.get(1).orEmpty()

        return fastApiEndpointPattern.findAll(text).map { match ->
            val receiver = match.groupValues[1].lowercase()
            val prefix = if (receiver == "router") routerPrefix else ""
            BackendEndpoint(
                method = match.groupValues[2].uppercase(),
                path = normalizeRoutePath(prefix, match.groupValues[3]),
                controller = file.nameWithoutExtension,
                handler = match.groupValues[4],
                framework = "FastAPI",
                filePath = relativePath,
                line = lineNumber(text, match.range.first)
            )
        }.distinctBy { "${it.method}:${it.path}:${it.filePath}:${it.line}" }.toList()
    }

    private fun buildGraph(
        projectName: String,
        projectPath: String,
        scannedFiles: Int,
        frontendCalls: List<FrontendCall>,
        backendEndpoints: List<BackendEndpoint>
    ): ProjectGraph {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val endpointNodeIds = mutableMapOf<String, String>()
        var matchedCalls = 0

        backendEndpoints.forEachIndexed { index, endpoint ->
            val routeKey = routeKey(endpoint.method, endpoint.path)
            val routeNodeId = "route-$index"
            val backendNodeId = "backend-$index"
            endpointNodeIds[routeKey] = routeNodeId
            nodes.add(
                GraphNode(
                    id = routeNodeId,
                    kind = "endpoint",
                    label = "${endpoint.method} ${endpoint.path}",
                    subtitle = "${endpoint.framework} endpoint",
                    technology = endpoint.framework
                )
            )
            nodes.add(
                GraphNode(
                    id = backendNodeId,
                    kind = "backend",
                    label = "${endpoint.controller}.${endpoint.handler}()",
                    subtitle = "${endpoint.filePath}:${endpoint.line}",
                    technology = endpoint.framework,
                    filePath = endpoint.filePath,
                    line = endpoint.line
                )
            )
            edges.add(
                GraphEdge(
                    id = "handles-$index",
                    source = routeNodeId,
                    target = backendNodeId,
                    label = "handles"
                )
            )
        }

        frontendCalls.forEachIndexed { index, call ->
            val frontendNodeId = "frontend-$index"
            val exactKey = routeKey(call.method, call.path)
            val matchedEndpoint = backendEndpoints.indexOfFirst {
                methodsMatch(call.method, it.method) && pathsMatch(call.path, it.path)
            }
            val routeNodeId = if (matchedEndpoint >= 0) {
                matchedCalls += 1
                endpointNodeIds[routeKey(backendEndpoints[matchedEndpoint].method, backendEndpoints[matchedEndpoint].path)]!!
            } else {
                val unmatchedNodeId = "unmatched-$index"
                nodes.add(
                    GraphNode(
                        id = unmatchedNodeId,
                        kind = "unmatched",
                        label = "${call.method} ${call.path}",
                        subtitle = "No matching backend endpoint"
                    )
                )
                unmatchedNodeId
            }

            nodes.add(
                GraphNode(
                    id = frontendNodeId,
                    kind = "frontend",
                    label = call.filePath.substringAfterLast('/'),
                    subtitle = "${call.filePath}:${call.line}",
                    filePath = call.filePath,
                    line = call.line
                )
            )
            edges.add(
                GraphEdge(
                    id = "requests-$index",
                    source = frontendNodeId,
                    target = routeNodeId,
                    label = call.method
                )
            )
        }

        val message = when {
            frontendCalls.isEmpty() && backendEndpoints.isEmpty() ->
                "No supported React API calls, FastAPI routes, or Spring endpoints were detected."
            matchedCalls == 0 && frontendCalls.isNotEmpty() && backendEndpoints.isNotEmpty() ->
                "Calls and endpoints were found, but none could be matched by method and path."
            else -> "Static analysis completed."
        }

        return ProjectGraph(
            projectName = projectName,
            projectPath = projectPath,
            status = if (nodes.isEmpty()) "empty" else "ready",
            message = message,
            nodes = nodes,
            edges = edges,
            stats = GraphStats(
                scannedFiles = scannedFiles,
                frontendCalls = frontendCalls.size,
                backendEndpoints = backendEndpoints.size,
                matchedCalls = matchedCalls
            )
        )
    }

    private fun loadText(file: VirtualFile): String? = runCatching {
        VfsUtilCore.loadText(file)
    }.getOrNull()

    private fun relativePath(file: VirtualFile, projectPath: String): String {
        val normalizedRoot = projectPath.replace('\\', '/').trimEnd('/')
        return file.path.removePrefix("$normalizedRoot/")
    }

    private fun normalizeRequestPath(rawUrl: String): String {
        val expandedTemplate = Regex("""\$\{([^}]+)}""").replace(rawUrl) { match ->
            val expression = match.groupValues[1].trim()
            when {
                expression == "BASE" -> ""
                expression.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) ->
                    "{${expression.substringAfterLast('.')}}"
                else -> ""
            }
        }
        val withoutQuery = expandedTemplate.substringBefore('?').substringBefore('#')
        val path = runCatching {
            if (withoutQuery.startsWith("http://") || withoutQuery.startsWith("https://")) {
                URI(withoutQuery).path
            } else {
                withoutQuery
            }
        }.getOrDefault(withoutQuery)
        return normalizePath(path)
    }

    private fun normalizeRoutePath(classPath: String, methodPath: String): String =
        normalizePath("${classPath.trimEnd('/')}/${methodPath.trimStart('/')}")

    private fun normalizePath(path: String): String {
        val normalized = "/${path.trim().trim('/')}".replace(Regex("/{2,}"), "/")
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }

    private fun routeKey(method: String, path: String): String = "${method.uppercase()}:$path"

    private fun methodsMatch(callMethod: String, endpointMethod: String): Boolean =
        endpointMethod == "ANY" || callMethod.equals(endpointMethod, ignoreCase = true)

    private fun pathsMatch(callPath: String, endpointPath: String): Boolean {
        val endpointRegex = endpointPath
            .split('/')
            .joinToString("/") { segment ->
                if (segment.startsWith('{') && segment.endsWith('}')) "[^/]+" else Regex.escape(segment)
            }
        return Regex("^$endpointRegex$").matches(callPath)
    }

    private fun lineNumber(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

    private data class FrontendCall(
        val method: String,
        val path: String,
        val rawUrl: String,
        val filePath: String,
        val line: Int
    )

    private data class BackendEndpoint(
        val method: String,
        val path: String,
        val controller: String,
        val handler: String,
        val framework: String,
        val filePath: String,
        val line: Int
    )
}
