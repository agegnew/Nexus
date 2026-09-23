package com.example.yasinreel.harvest

import com.example.GraphNode
import com.example.ProjectFlowAnalyzer
import com.example.ProjectGraph
import com.example.yasinreel.model.Chain
import com.example.yasinreel.model.ChainStep
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Finds paths through the system for the `flow-trace` scene.
 *
 * The scene is deliberately not HTTP-shaped, and this is why. A plugin, a CLI, a game
 * and a data pipeline all have flows worth watching, and none of them has a REST hop.
 * Nexus itself reports `calls=0, routes=0, matched=0`, so if HTTP were the only source
 * the feature would have nothing to say about the repo it was written in.
 *
 * Three sources, best first:
 *  - `http`, a matched frontend call to endpoint to handler triple from [ProjectFlowAnalyzer]
 *  - `bridge`, a cross-language handoff paired by the literal event name or global it carries
 *  - `entrypoint`, a plain call chain out of a known entry file, for repos with neither
 */
object ChainDetector {

    private val logger = Logger.getInstance(ChainDetector::class.java)

    private const val MAX_CHAINS = 6
    private const val MAX_FILE_SIZE = 1_000_000L
    private const val MAX_SCANNED_FILES = 800

    private val bridgeExtensions = setOf(
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "html", "vue", "svelte", "py", "cs", "swift", "c", "cpp", "mm"
    )

    private val ignoredPathSegments = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage",
        ".next", ".nuxt", ".venv", "venv", "__pycache__", ".gradle", ".idea",
        // This repository carries a fixture project so the analyzer has real calls and
        // real endpoints to match. It is not part of Nexus, and counting it makes Nexus
        // look like a FastAPI service. Named exactly, not as "demo", because a folder
        // called demo in somebody else's project is usually part of their product.
        "nexus-demo-app"
    )

    // The lookbehind matters: without it `toolWindow.contentManager` reads as a window global.
    private val globalAssignPattern =
        Regex("""(?<![\w\$.])window\s*\.\s*([A-Za-z_\$][\w\$]{2,60})(?![\w\$])\s*=(?!=)""")
    private val globalReadPattern =
        Regex("""(?<![\w\$.])window\s*\.\s*([A-Za-z_\$][\w\$]{2,60})(?![\w\$])(?!\s*=[^=])""")
    private val customEventPattern = Regex("""new\s+CustomEvent\s*\(\s*["'`]([^"'`]{1,80})["'`]""")
    private val listenerPattern = Regex("""addEventListener\s*\(\s*["'`]([^"'`]{1,80})["'`]""")
    private val postMessagePattern = Regex("""\.\s*postMessage\s*\(""")
    private val jsQueryPattern = Regex("""JBCefJSQuery""")
    private val cefQueryPattern = Regex("""\bcefQuery\w*\s*\(""")
    private val nativeDeclarationPattern = Regex("""(?:external\s+fun|private\s+native|public\s+native|static\s+native)\s+[\w<>,.\[\]?\s]{0,60}?(\w+)\s*\(""")
    private val nativeImplementationPattern = Regex("""JNIEXPORT[\s\S]{0,120}?Java_[\w_]*?_(\w+)\s*\(""")

    /** Window properties every page has, so they say nothing about this codebase. */
    private val browserGlobals = setOf(
        "location", "document", "navigator", "console", "history", "localstorage", "sessionstorage",
        "innerwidth", "innerheight", "outerwidth", "outerheight", "screen", "parent", "top", "self",
        "opener", "frames", "performance", "crypto", "fetch", "settimeout", "setinterval", "alert",
        "requestanimationframe", "matchmedia", "getcomputedstyle", "scrollto", "addeventlistener",
        "removeeventlistener", "dispatchevent", "postmessage", "onload", "onmessage", "devicepixelratio"
    )

    /** Events the browser raises by itself, which are not a boundary anyone designed. */
    private val domEvents = setOf(
        "click", "dblclick", "load", "unload", "beforeunload", "domcontentloaded", "resize", "scroll",
        "keydown", "keyup", "keypress", "submit", "change", "input", "focus", "blur", "error",
        "mousemove", "mousedown", "mouseup", "mouseenter", "mouseleave", "wheel", "contextmenu",
        "touchstart", "touchend", "touchmove", "visibilitychange", "hashchange", "popstate", "drop"
    )

    private val entryNamePattern =
        Regex("""(?i)^(main|index|app|application|manage|cli|server|__main__)\.(kt|kts|java|py|js|jsx|ts|tsx|mjs|go|rs|rb|php|cs|swift)$""")
    private val applicationNamePattern = Regex("""(?i)^[A-Za-z0-9_]+Application\.(kt|java)$""")
    private val internalCallPattern = Regex("""\b([A-Z][A-Za-z0-9_]{2,40})\s*\.\s*([a-z][A-Za-z0-9_]{1,40})\s*\(""")

    fun detect(project: Project, httpGraph: Any?): List<Chain> {
        // The harvester already paid for the analysis, so reuse it when it is handed over.
        val graph = httpGraph as? ProjectGraph ?: try {
            ProjectFlowAnalyzer.analyze(project)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("Flow analysis unavailable, chains will come from bridges only", failure)
            null
        }

        val chains = mutableListOf<Chain>()
        chains += safely("http") { graph?.let { httpChains(it) }.orEmpty() }
        chains += safely("bridge") { bridgeChains(project) }
        // A single chain makes a thin scene, so the weakest source only fills a gap.
        if (chains.size < 2) chains += safely("entrypoint") { entryPointChains(project) }

        return chains.take(MAX_CHAINS)
    }

    private fun httpChains(graph: ProjectGraph): List<Chain> {
        val nodes = graph.nodes.associateBy { it.id }
        val handlerByEndpoint = graph.edges
            .filter { nodes[it.source]?.kind == "endpoint" && nodes[it.target]?.kind == "backend" }
            .associateBy({ it.source }, { it.target })

        return graph.edges.asSequence()
            .mapNotNull { edge ->
                val frontend = nodes[edge.source]?.takeIf { it.kind == "frontend" } ?: return@mapNotNull null
                val endpoint = nodes[edge.target]?.takeIf { it.kind == "endpoint" } ?: return@mapNotNull null
                val backend = handlerByEndpoint[endpoint.id]?.let { nodes[it] } ?: return@mapNotNull null
                Triple(frontend, endpoint, backend)
            }
            // One chain per endpoint, otherwise six near-identical chains crowd out the bridges.
            .distinctBy { it.second.id }
            .take(MAX_CHAINS)
            .mapIndexed { index, (frontend, endpoint, backend) ->
                Chain(
                    id = "http-${index + 1}",
                    name = "${frontend.label} to ${endpoint.label}",
                    kind = "http",
                    steps = listOf(
                        step(frontend.label, "calls ${endpoint.label}", frontend),
                        ChainStep(endpoint.label, endpoint.subtitle, null, null),
                        step(backend.label, backend.subtitle, backend)
                    )
                )
            }
            .toList()
    }

    private fun step(label: String, detail: String?, node: GraphNode) =
        ChainStep(label, detail, node.filePath, node.line)

    private fun bridgeChains(project: Project): List<Chain> {
        val projectPath = project.basePath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        val producers = mutableMapOf<String, MutableList<Signal>>()
        val consumers = mutableMapOf<String, MutableList<Signal>>()
        var scanned = 0

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory &&
                file.length in 1..MAX_FILE_SIZE &&
                file.extension?.lowercase() in bridgeExtensions
            ) {
                // Judged on the path inside the project, so a repo that lives in ~/build still scans.
                val relative = relativePath(file, projectPath)
                if (!isIgnored(relative)) {
                    scanned++
                    val text = loadText(file)
                    if (text != null && mentionsBridge(text)) {
                        collectSignals(text, relative, file.extension?.lowercase(), producers, consumers)
                    }
                }
            }
            scanned < MAX_SCANNED_FILES
        }

        val pairs = producers.mapNotNull { (key, produced) ->
            val consumed = consumers[key].orEmpty()
            produced.firstNotNullOfOrNull { producer ->
                consumed.firstOrNull { it.file != producer.file }?.let { consumer -> Pairing(key, producer, consumer) }
            }
        }

        // Two keys travelling between the same two files are one handoff, not two: our own
        // bridge sets a global and then fires an event, and the film should say that once.
        return pairs
            .groupBy { it.producer.file to it.consumer.file }
            .entries
            .sortedByDescending { entry -> entry.value.maxOf { it.score } }
            .take(MAX_CHAINS)
            .mapIndexed { index, entry -> bridgeChain(index + 1, entry.value) }
    }

    private fun bridgeChain(index: Int, pairings: List<Pairing>): Chain {
        val ordered = pairings.sortedBy { it.producer.line }
        val source = ordered.first().producer
        val target = ordered.last().consumer
        val middle = ordered.take(2).map { ChainStep(it.key, it.producer.mechanism, null, null) }
        return Chain(
            id = "bridge-$index",
            name = "${simpleName(source.file)} to ${simpleName(target.file)}",
            kind = "bridge",
            steps = listOf(ChainStep(simpleName(source.file), source.detail, source.file, source.line)) +
                middle +
                listOf(ChainStep(simpleName(target.file), target.detail, target.file, target.line))
        )
    }

    /** A cheap reject, so the expensive regex work only runs on files that could match. */
    private fun mentionsBridge(text: String): Boolean =
        text.contains("window.") ||
            text.contains("CustomEvent") ||
            text.contains("postMessage") ||
            text.contains("addEventListener") ||
            text.contains("JBCefJSQuery") ||
            text.contains("cefQuery") ||
            text.contains("native ") ||
            text.contains("external fun") ||
            text.contains("JNIEXPORT")

    private fun collectSignals(
        text: String,
        path: String,
        extension: String?,
        producers: MutableMap<String, MutableList<Signal>>,
        consumers: MutableMap<String, MutableList<Signal>>
    ) {
        val group = languageGroup(extension)
        fun produce(key: String, mechanism: String, detail: String, offset: Int) {
            producers.getOrPut(key) { mutableListOf() }
                .add(Signal(key, mechanism, detail, path, lineNumber(text, offset), group))
        }

        fun consume(key: String, mechanism: String, detail: String, offset: Int) {
            consumers.getOrPut(key) { mutableListOf() }
                .add(Signal(key, mechanism, detail, path, lineNumber(text, offset), group))
        }

        globalAssignPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (isInterestingGlobal(name)) {
                produce(name, "window global", "sets window.$name", match.range.first)
            }
        }
        globalReadPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (isInterestingGlobal(name)) {
                consume(name, "window global", "reads window.$name", match.range.first)
            }
        }
        customEventPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            produce(name, "CustomEvent", "dispatches $name", match.range.first)
        }
        listenerPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            when {
                name.equals("message", ignoreCase = true) ->
                    consume(POST_MESSAGE, "postMessage", "handles posted messages", match.range.first)
                name.lowercase() !in domEvents ->
                    consume(name, "CustomEvent", "listens for $name", match.range.first)
            }
        }
        postMessagePattern.find(text)?.let { match ->
            produce(POST_MESSAGE, "postMessage", "posts a message across the boundary", match.range.first)
        }
        jsQueryPattern.find(text)?.let { match ->
            produce(JS_QUERY, "JBCefJSQuery", "opens a JCEF query channel", match.range.first)
        }
        cefQueryPattern.find(text)?.let { match ->
            consume(JS_QUERY, "JBCefJSQuery", "calls back into the IDE", match.range.first)
        }
        nativeDeclarationPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            produce(name, "native binding", "declares native $name", match.range.first)
        }
        nativeImplementationPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            consume(name, "native binding", "implements native $name", match.range.first)
        }
    }

    /**
     * Last resort. No HTTP and no boundary crossing still leaves the shape of the program,
     * which beats an empty scene.
     */
    private fun entryPointChains(project: Project): List<Chain> {
        val projectPath = project.basePath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        val entries = mutableListOf<VirtualFile>()
        val filesByName = mutableMapOf<String, String>()

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && file.length in 1..MAX_FILE_SIZE) {
                val relative = relativePath(file, projectPath)
                if (!isIgnored(relative)) {
                    filesByName.putIfAbsent(file.nameWithoutExtension, relative)
                    if (entryNamePattern.matches(file.name) || applicationNamePattern.matches(file.name)) {
                        entries.add(file)
                    }
                }
            }
            true
        }

        return entries
            .sortedWith(compareBy<VirtualFile>({ it.path.count { char -> char == '/' } }, { it.path.length }))
            .take(3)
            .mapIndexedNotNull { index, file ->
                val text = loadText(file) ?: return@mapIndexedNotNull null
                val path = relativePath(file, projectPath)
                val calls = internalCallPattern.findAll(text)
                    .filter { filesByName.containsKey(it.groupValues[1]) }
                    .distinctBy { it.groupValues[1] + it.groupValues[2] }
                    .take(3)
                    .toList()
                if (calls.isEmpty()) return@mapIndexedNotNull null

                Chain(
                    id = "entrypoint-${index + 1}",
                    name = "${file.name} startup path",
                    kind = "entrypoint",
                    steps = listOf(ChainStep(file.name, "entry point", path, 1)) +
                        calls.map { match ->
                            ChainStep(
                                "${match.groupValues[1]}.${match.groupValues[2]}()",
                                "called from ${file.name}",
                                filesByName[match.groupValues[1]],
                                null
                            )
                        }
                )
            }
    }

    private fun isInterestingGlobal(name: String): Boolean =
        name.lowercase() !in browserGlobals && name.length >= 4

    private fun languageGroup(extension: String?): String = when (extension) {
        "kt", "kts", "java" -> "jvm"
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "html", "vue", "svelte" -> "web"
        "py" -> "python"
        "cs" -> "dotnet"
        else -> "native"
    }

    private fun isIgnored(path: String): Boolean = path.split('/').any { it in ignoredPathSegments }

    private fun simpleName(path: String): String = path.substringAfterLast('/')

    private fun lineNumber(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

    private fun loadText(file: VirtualFile): String? =
        try {
            VfsUtilCore.loadText(file)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.debug("Could not read ${file.name}", failure)
            null
        }

    private fun relativePath(file: VirtualFile, projectPath: String): String =
        if (projectPath.isEmpty()) file.name else file.path.removePrefix("$projectPath/")

    /** Cancellation keeps unwinding; anything else costs us that one source. */
    private fun <T> safely(source: String, block: () -> List<T>): List<T> =
        try {
            block()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("Chain source '$source' failed, continuing without it", failure)
            emptyList()
        }

    private const val POST_MESSAGE = "postMessage"
    private const val JS_QUERY = "JBCefJSQuery"

    private data class Signal(
        val key: String,
        val mechanism: String,
        val detail: String,
        val file: String,
        val line: Int,
        val group: String
    )

    private data class Pairing(val key: String, val producer: Signal, val consumer: Signal) {
        /** Crossing a language boundary is the whole point, so it outranks everything else. */
        val score: Int
            get() = (if (producer.group != consumer.group) 3 else 0) +
                (if (key.contains(':') || key.contains("__") || key.contains('-')) 1 else 0) +
                (if (producer.mechanism == "CustomEvent" || producer.mechanism == "window global") 1 else 0)
    }
}
