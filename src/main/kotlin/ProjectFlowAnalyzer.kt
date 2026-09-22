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

    /**
     * How many calls reach the diagram.
     *
     * A cap is right: the diagram draws seven client rows and ten routes, and a graph of
     * ten thousand nodes helps nobody. Where it was wrong was being applied to the calls
     * and the routes separately, BEFORE matching. A repository with more routes than the
     * cap lost the ones past it, and then every call that pointed at a lost route was
     * drawn as an unresolved call to an external service. On one real project that turned
     * 210 matched calls into 127 and 10 genuinely unresolved ones into 73: the diagram was
     * not merely incomplete, it was accusing the project of calling APIs it does not call.
     *
     * Routes are cheap (one node pair each) and are what the calls are matched against, so
     * they are collected in full and only the calls are capped.
     */
    private const val MAX_RESULTS = 200
    private const val MAX_ENDPOINTS = 2_000

    /**
     * Never read as source, because it is not source.
     *
     * Without this the first thing the Client layer shows on this very repository is a
     * minified Vite bundle, named "Index D3y YGbge" after its content hash. A build output
     * is the project's own code already compiled, so every call in it is counted twice,
     * and a vendored dependency's calls are not the project's calls at all.
     */
    private val generatedDirs = setOf(
        "node_modules", "dist", "build", "out", "target", "vendor", "coverage", "bin", "obj",
        ".next", ".nuxt", ".svelte-kit", ".output", ".venv", "venv", "__pycache__", ".gradle",
        ".idea", ".git", ".kotlin", ".cache", "site-packages", ".turbo", ".claude",
        // Output this plugin and its neighbours write into the project they are reading.
        "yasin-reel", "yasin-deck", "yasin-shared", "brag-output", ".work"
    )
    private val generatedNamePattern = Regex("""(?i)(\.min\.|\.bundle\.|-[0-9a-f]{8}\.(js|ts)$|\.d\.ts$)""")

    private fun isGenerated(relative: String, name: String): Boolean =
        relative.split('/').any { it.lowercase() in generatedDirs } ||
            generatedNamePattern.containsMatchIn(name)

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
            .flatMap { file ->
                when (file.extension?.lowercase()) {
                    "py" -> findFastApiEndpoints(file, projectPath).asSequence()
                    // A JavaScript or TypeScript file can serve routes as well as call
                    // them. Skipping those was reporting one real project's 105 route
                    // handlers as zero endpoints, which drew every one of its own calls
                    // as a call to an external service it does not use.
                    in frontendExtensions -> findJsEndpoints(file, projectPath).asSequence()
                    else -> findSpringEndpoints(file, projectPath).asSequence()
                }
            }
            .take(MAX_ENDPOINTS)
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
        val root = project.basePath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory &&
                file.length <= MAX_FILE_SIZE &&
                file.extension?.lowercase() in frontendExtensions + backendExtensions &&
                !isGenerated(relativePath(file, root), file.name)
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

    /**
     * Routes served by JavaScript and TypeScript, which is most of them.
     *
     * Only Spring and FastAPI were recognised, so a Next.js or an Express application had
     * no backend at all as far as the diagram was concerned: its Service layer read "No
     * backend detected" and every one of its own API calls was drawn in the box for calls
     * to somebody else's service. Two shapes cover the overwhelming majority:
     *
     *  - **Express and Nest**, where the method and the path are in the call itself:
     *    `router.post('/users/:id', handler)`.
     *  - **Next.js App Router**, where the path is the FILE's location and only the method
     *    is in the source: `app/api/users/[id]/route.ts` exporting `GET`. Nothing inside
     *    the file names the route, so a text-only scan that ignores the path finds nothing.
     */
    private fun findJsEndpoints(file: VirtualFile, projectPath: String): List<BackendEndpoint> {
        val relativePath = relativePath(file, projectPath)
        val text = loadText(file) ?: return emptyList()
        val endpoints = mutableListOf<BackendEndpoint>()
        val controller = file.nameWithoutExtension

        val routeDir = nextRoutePath(relativePath)
        if (routeDir != null) {
            nextHandlerPattern.findAll(text).forEach { match ->
                endpoints.add(
                    BackendEndpoint(
                        method = match.groupValues[1].uppercase(),
                        path = routeDir,
                        controller = relativePath.substringBeforeLast('/').substringAfterLast('/').ifEmpty { controller },
                        handler = match.groupValues[1].uppercase(),
                        framework = "Next.js",
                        filePath = relativePath,
                        line = lineNumber(text, match.range.first)
                    )
                )
            }
        }

        // A file that mounts a router under a prefix: app.use('/api/users', usersRouter).
        val mounted = expressMountPattern.find(text)?.groupValues?.get(2).orEmpty()
        expressRoutePattern.findAll(text).forEach { match ->
            val method = match.groupValues[2].uppercase()
            if (method == "USE" || method == "ALL") return@forEach
            endpoints.add(
                BackendEndpoint(
                    method = method,
                    path = normalizeRoutePath(mounted, expressParams(match.groupValues[3])),
                    controller = controller,
                    handler = match.groupValues[1],
                    framework = "Express",
                    filePath = relativePath,
                    line = lineNumber(text, match.range.first)
                )
            )
        }
        return endpoints
    }

    /** `app/api/users/[id]/route.ts` is the route `/api/users/{id}`, declared by its location. */
    private fun nextRoutePath(relative: String): String? {
        val parts = relative.split('/')
        val file = parts.lastOrNull().orEmpty()
        if (!nextRouteFilePattern.matches(file)) return null
        val appIndex = parts.indexOfLast { it == "app" || it == "pages" }
        if (appIndex < 0 || appIndex == parts.lastIndex) return null
        val segments = parts.subList(appIndex + 1, parts.lastIndex)
            // A Next.js route group like (marketing) is organisation, not URL.
            .filterNot { it.startsWith("(") && it.endsWith(")") }
            .map { segment ->
                if (segment.startsWith("[") && segment.endsWith("]")) {
                    "{" + segment.trim('[', ']').removePrefix("...") + "}"
                } else {
                    segment
                }
            }
        return normalizePath(segments.joinToString("/"))
    }

    /** Express writes `:id` where everything else writes `{id}`. */
    private fun expressParams(path: String): String =
        Regex(""":(\w+)""").replace(path) { "{" + it.groupValues[1] + "}" }

    private val nextRouteFilePattern = Regex("""(?i)^(route|page)\.(js|jsx|ts|tsx)$""")
    private val nextHandlerPattern = Regex(
        """export\s+(?:async\s+)?(?:const|function)\s+(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b"""
    )
    private val expressRoutePattern = Regex(
        """\b(\w+)\s*\.\s*(get|post|put|patch|delete|head|options|all|use)\s*\(\s*[\"'`]([^\"'`]+)[\"'`]\s*,""",
        RegexOption.IGNORE_CASE
    )
    private val expressMountPattern = Regex(
        """\b(\w+)\s*\.\s*use\s*\(\s*[\"'`](/[^\"'`]*)[\"'`]\s*,"""
    )

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
                "No HTTP layer found. This project may not have one, which is normal. Recognised: fetch and axios calls, and routes declared by Spring, FastAPI, Express or Next.js."
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

    /**
     * The URL a call really asks for, with the parts that are not path removed.
     *
     * Two things here were making real projects look like they call nobody.
     *
     * A base URL held in a constant, which is how essentially every real front end is
     * written, was being turned into a path segment: `fetch(`${'$'}{API_URL}/api/status`)`
     * became the path `/{API_URL}/api/status`, and since only an ENDPOINT's `{}` segments
     * are wildcards, that could never match the endpoint `/api/status`. On one real project
     * this matched 0 of 31 calls against 21 of its own routes. A template at the very front
     * of a URL is a host, so it is dropped rather than kept.
     *
     * An absolute third-party URL was worse than unmatched, it was mangled: the template
     * expansion ran first, so `URI()` was handed a string containing a brace, threw, and the
     * fallback kept the scheme, giving the route `/https:/api.telegram.org/bot{token}/getMe`
     * on screen. The scheme and host are now stripped textually, which cannot throw.
     */
    private fun normalizeRequestPath(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        // A leading template is the host, not the first segment of the path.
        val withoutBase = LEADING_TEMPLATE.replace(trimmed, "")
        val expandedTemplate = TEMPLATE.replace(withoutBase) { match ->
            val expression = match.groupValues[1].trim()
            when {
                expression == "BASE" -> ""
                expression.matches(IDENTIFIER) -> "{${expression.substringAfterLast('.')}}"
                else -> ""
            }
        }
        val withoutQuery = expandedTemplate.substringBefore('?').substringBefore('#')
        // Textual rather than URI(), because the string may still hold a brace by now.
        val path = ABSOLUTE_URL.find(withoutQuery)?.let { withoutQuery.substring(it.range.last + 1) }
            ?: withoutQuery
        return normalizePath(path)
    }

    /** Whether a call was aimed at somebody else's service, which is never this project's route. */
    private fun isExternal(rawUrl: String): Boolean =
        ABSOLUTE_URL.containsMatchIn(LEADING_TEMPLATE.replace(rawUrl.trim(), ""))

    private val LEADING_TEMPLATE = Regex("""^\$\{[^}]+}""")
    private val TEMPLATE = Regex("""\$\{([^}]+)}""")
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
    /** Matches `https://host` or `//host` up to the first slash of the path. */
    private val ABSOLUTE_URL = Regex("""^(?:[a-zA-Z][a-zA-Z0-9+.-]*:)?//[^/]*""")

    private fun normalizeRoutePath(classPath: String, methodPath: String): String =
        normalizePath("${classPath.trimEnd('/')}/${methodPath.trimStart('/')}")

    private fun normalizePath(path: String): String {
        val normalized = "/${path.trim().trim('/')}".replace(Regex("/{2,}"), "/")
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }

    private fun routeKey(method: String, path: String): String = "${method.uppercase()}:$path"

    private fun methodsMatch(callMethod: String, endpointMethod: String): Boolean =
        endpointMethod == "ANY" || callMethod.equals(endpointMethod, ignoreCase = true)

    /**
     * Whether a call and a route are the same place.
     *
     * Exact first. Then, and only then, a suffix: a front end that prefixes every request
     * with a version or a gateway segment still calls the route the server declares, and
     * requiring the two strings to be equal reports a working application as one whose
     * calls all go nowhere. The suffix has to land on a segment boundary and the route has
     * to have a segment of its own, so `/` never swallows everything.
     */
    private fun pathsMatch(callPath: String, endpointPath: String): Boolean {
        if (segmentRegex(endpointPath).matches(callPath)) return true
        if (endpointPath == "/" || endpointPath.isEmpty()) return false
        return segmentRegex(endpointPath, anchorStart = false).matches(callPath)
    }

    private fun segmentRegex(endpointPath: String, anchorStart: Boolean = true): Regex {
        val body = endpointPath
            .split('/')
            .joinToString("/") { segment ->
                if (segment.startsWith('{') && segment.endsWith('}')) "[^/]+" else Regex.escape(segment)
            }
        return Regex(if (anchorStart) "^$body$" else "^(?:/[^/]+)*?$body$")
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
