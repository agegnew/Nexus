package com.example.yasinreel.llm

import com.example.ProjectFlowAnalyzer
import com.example.ProjectGraph
import com.example.yasinreel.harvest.SecretScrubber
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The four callbacks the understanding stage may invoke to pull more of the codebase in.
 *
 * This is the difference between a prompt wrapper and an agent: stage 2 does not get one
 * fixed context window, it gets to go and look. Which is also why every entry point here
 * is hostile to its own caller. The model chooses the arguments, so the model is untrusted
 * input, and three rules hold regardless of what it asks for:
 *
 *  - nothing outside the project root is readable, symlinks included
 *  - secret shaped files are refused by name before they are ever opened
 *  - every response is scrubbed and then capped, so one tool call cannot flood the context
 *    or smuggle a token out to the API
 *
 * Paths are accepted and returned relative to the project root, because that is what the
 * model sees in the evidence and asking it to juggle absolute paths only invites escapes.
 */
class EvidenceTools(private val project: Project) {

    private val logger = Logger.getInstance(EvidenceTools::class.java)

    private val root: Path? = project.basePath?.let {
        try {
            Paths.get(it).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            logger.warn("Nexus Reel got an unusable project base path", e)
            null
        }
    }

    /**
     * Reads a slice of one file, line numbered so the model can cite what it saw.
     *
     * [fromLine] and [toLine] are 1 based and inclusive; both null reads from the top.
     */
    fun readFile(path: String, fromLine: Int?, toLine: Int?): String {
        val resolved = resolve(path) ?: return refusal(path)
        if (!Files.isRegularFile(resolved)) return "ERROR: not a file: $path"
        if (Files.size(resolved) > MAX_FILE_BYTES) return "ERROR: file too large to read: $path"

        val lines = try {
            Files.readAllLines(resolved)
        } catch (e: IOException) {
            // A binary file or an odd encoding lands here; say so rather than throw,
            // so the model can pick something else and the loop continues.
            return "ERROR: could not read as text: $path"
        }

        val start = (fromLine ?: 1).coerceAtLeast(1)
        val end = (toLine ?: (start + DEFAULT_LINE_SPAN - 1)).coerceAtMost(lines.size)
        if (start > lines.size) return "ERROR: $path has only ${lines.size} lines"

        val body = buildString {
            append(relative(resolved)).append("  lines $start to $end of ${lines.size}\n")
            for (i in start..end) {
                append(i).append(": ").append(lines[i - 1]).append('\n')
            }
        }
        return respond(body)
    }

    /** One directory listing, directories first, so the model can navigate rather than guess. */
    fun listDir(path: String): String {
        val resolved = resolve(path.ifBlank { "." }) ?: return refusal(path)
        if (!Files.isDirectory(resolved)) return "ERROR: not a directory: $path"

        val entries = try {
            Files.newDirectoryStream(resolved).use { stream -> stream.toList() }
        } catch (e: IOException) {
            return "ERROR: could not list: $path"
        }

        val visible = entries
            .filter { !isIgnored(it.fileName.toString()) && !isSecretName(it.fileName.toString()) }
            .sortedWith(compareBy<Path>({ !Files.isDirectory(it) }, { it.fileName.toString().lowercase() }))
            .take(MAX_DIR_ENTRIES)

        val body = buildString {
            append(relative(resolved)).append("/\n")
            if (visible.isEmpty()) append("(empty, or everything in it is ignored)\n")
            for (entry in visible) {
                val name = entry.fileName.toString()
                if (Files.isDirectory(entry)) {
                    append("  ").append(name).append("/\n")
                } else {
                    val size = try {
                        Files.size(entry)
                    } catch (e: IOException) {
                        0L
                    }
                    append("  ").append(name).append("  ").append(size).append(" bytes\n")
                }
            }
        }
        return respond(body)
    }

    /**
     * Case insensitive regex across the project's content roots.
     *
     * Goes through [ProjectFileIndex] rather than walking the filesystem so that excluded
     * roots, `node_modules` and build output are skipped by the IDE's own definition of
     * what belongs to the project.
     */
    fun grep(pattern: String, maxResults: Int): String {
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return "ERROR: not a valid regular expression: $pattern"
        }
        val cap = maxResults.coerceIn(1, MAX_GREP_RESULTS)
        val hits = StringBuilder()
        var found = 0

        ReadAction.run<RuntimeException> {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (found >= cap) return@iterateContent false
                if (file.isDirectory) return@iterateContent true
                if (file.length > MAX_FILE_BYTES) return@iterateContent true
                if (isSecretName(file.name)) return@iterateContent true
                val extension = file.extension?.lowercase() ?: return@iterateContent true
                if (extension !in TEXT_EXTENSIONS) return@iterateContent true

                val text = try {
                    VfsUtilCore.loadText(file)
                } catch (e: IOException) {
                    return@iterateContent true
                }
                val path = file.path.removePrefix(root?.toString().orEmpty()).trimStart('/')
                text.lineSequence().forEachIndexed { index, line ->
                    if (found < cap && regex.containsMatchIn(line)) {
                        found++
                        hits.append(path).append(':').append(index + 1).append(": ")
                            .append(line.trim().take(MAX_GREP_LINE)).append('\n')
                    }
                }
                true
            }
        }

        if (found == 0) return "No match for $pattern"
        return respond("$found match(es) for $pattern\n$hits")
    }

    /**
     * The HTTP surface, from the team's analyzer rather than a second implementation of it.
     *
     * On a project with no HTTP at all (Nexus itself is one) this honestly reports zero,
     * which is exactly the kind of gap stage 2 is told to surface instead of inventing.
     */
    fun readRoutes(): String {
        val graph = try {
            ReadAction.compute<ProjectGraph, RuntimeException> {
                ProjectFlowAnalyzer.analyze(project)
            }
        } catch (e: Exception) {
            logger.warn("Nexus Reel route analysis failed", e)
            return "ERROR: route analysis failed"
        }

        val body = buildString {
            append("scanned ").append(graph.stats.scannedFiles).append(" files, ")
                .append(graph.stats.backendEndpoints).append(" routes, ")
                .append(graph.stats.frontendCalls).append(" calls, ")
                .append(graph.stats.matchedCalls).append(" matched\n")

            val routes = graph.nodes.filter { it.kind == "endpoint" }.take(MAX_ROUTES)
            if (routes.isEmpty()) {
                append("No HTTP routes found. This project does not expose an HTTP surface.\n")
            } else {
                append("\nROUTES\n")
                for (route in routes) append("  ").append(route.label).append("  ").append(route.subtitle).append('\n')
            }

            val handlers = graph.nodes.filter { it.kind == "backend" }.take(MAX_ROUTES)
            if (handlers.isNotEmpty()) {
                append("\nHANDLERS\n")
                for (handler in handlers) {
                    append("  ").append(handler.label).append("  ").append(handler.subtitle).append('\n')
                }
            }

            val unmatched = graph.nodes.filter { it.kind == "unmatched" }.take(MAX_ROUTES)
            if (unmatched.isNotEmpty()) {
                append("\nCALLS WITH NO MATCHING ROUTE\n")
                for (call in unmatched) append("  ").append(call.label).append('\n')
            }
        }
        return respond(body)
    }

    /**
     * Single entry point for the tool loop. Arguments arrive as whatever JSON the model
     * produced, so a malformed call has to come back as text the model can recover from,
     * never as an exception that ends the run.
     */
    fun execute(name: String, argumentsJson: String): String {
        val args = try {
            JsonParser.parseString(argumentsJson.ifBlank { "{}" }).asJsonObject
        } catch (e: Exception) {
            return "ERROR: arguments were not a JSON object"
        }
        return try {
            when (name) {
                READ_FILE -> readFile(
                    args.string("path").orEmpty(),
                    args.int("from_line"),
                    args.int("to_line")
                )
                LIST_DIR -> listDir(args.string("path").orEmpty())
                GREP -> grep(args.string("pattern").orEmpty(), args.int("max_results") ?: 20)
                READ_ROUTES -> readRoutes()
                else -> "ERROR: unknown tool $name"
            }
        } catch (e: Exception) {
            logger.warn("Nexus Reel tool $name failed", e)
            "ERROR: $name failed: ${e.message}"
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            try {
                it.asInt
            } catch (e: Exception) {
                null
            }
        }

    /** Null means refused: outside the project, secret shaped, or unparseable. */
    private fun resolve(path: String): Path? {
        val base = root ?: return null
        val candidate = try {
            val raw = Paths.get(path.trim())
            if (raw.isAbsolute) raw.normalize() else base.resolve(raw).normalize()
        } catch (e: InvalidPathException) {
            return null
        }
        if (!candidate.startsWith(base)) return null
        // A symlink inside the project can still point outside it, so the real path is
        // what actually decides. Missing files fall through to the normalized check above.
        val real = try {
            candidate.toRealPath()
        } catch (e: IOException) {
            candidate
        }
        if (!real.startsWith(base.toRealPathOrSelf())) return null
        if (real.any { isSecretName(it.toString()) }) return null
        return real
    }

    private fun Path.toRealPathOrSelf(): Path = try {
        toRealPath()
    } catch (e: IOException) {
        this
    }

    private fun relative(path: Path): String =
        root?.let { runCatching { it.relativize(path).toString() }.getOrNull() } ?: path.toString()

    private fun refusal(path: String): String =
        "ERROR: refused. $path is outside the project or is a protected file."

    /** Scrub first, cap second, so a truncated response can never end mid redaction. */
    private fun respond(text: String): String {
        val scrubbed = SecretScrubber.scrub(text)
        return if (scrubbed.length <= MAX_RESPONSE_CHARS) {
            scrubbed
        } else {
            scrubbed.take(MAX_RESPONSE_CHARS) + "\n... truncated at $MAX_RESPONSE_CHARS characters"
        }
    }

    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase()
        // .env.example is allowed by the harvester for key names only; the tools do not
        // need that nuance, so they refuse the whole family.
        if (lower == ".env" || lower.startsWith(".env.")) return true
        if (lower.startsWith("id_rsa") || lower.startsWith("id_ed25519") || lower.startsWith("id_dsa")) return true
        if (lower == ".git-credentials" || lower == ".netrc" || lower == ".npmrc" || lower == ".pypirc") return true
        if (SECRET_SUFFIXES.any { lower.endsWith(it) }) return true
        if (lower.endsWith(".json") && SECRET_JSON_HINTS.any { lower.contains(it) }) return true
        return false
    }

    private fun isIgnored(name: String): Boolean = name in IGNORED_DIRS

    companion object {
        const val READ_FILE = "read_file"
        const val LIST_DIR = "list_dir"
        const val GREP = "grep"
        const val READ_ROUTES = "read_routes"

        private const val MAX_RESPONSE_CHARS = 8 * 1024
        private const val MAX_FILE_BYTES = 512L * 1024
        private const val DEFAULT_LINE_SPAN = 200
        private const val MAX_DIR_ENTRIES = 120
        private const val MAX_GREP_RESULTS = 60
        private const val MAX_GREP_LINE = 200
        private const val MAX_ROUTES = 40

        private val SECRET_SUFFIXES = listOf(
            ".pem", ".key", ".p12", ".pfx", ".keystore", ".jks", ".crt", ".cer", ".ppk"
        )
        private val SECRET_JSON_HINTS = listOf(
            "service-account", "service_account", "serviceaccount", "credential", "secret"
        )
        private val IGNORED_DIRS = setOf(
            ".git", ".idea", ".gradle", "node_modules", "build", "dist", "out", "target",
            "venv", ".venv", "__pycache__", ".next", ".nuxt", "coverage", ".DS_Store"
        )
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rb", "rs", "php",
            "cs", "swift", "c", "h", "cpp", "hpp", "m", "mm", "scala", "sh", "sql", "json",
            "yml", "yaml", "toml", "xml", "html", "css", "scss", "md", "gradle", "properties",
            "txt", "vue", "svelte", "tf", "proto", "graphql"
        )

        /**
         * The same four tools in OpenAI's `tools` wire format.
         *
         * Built here, next to the implementations, so a renamed parameter cannot drift out
         * of sync with the dispatcher in [execute].
         */
        fun toolDefinitions(): JsonArray {
            val tools = JsonArray()
            tools.add(
                tool(
                    READ_FILE,
                    "Read a file from the project, line numbered. Use it to check what a file actually does.",
                    JsonObject().apply {
                        add("path", param("string", "Path relative to the project root, for example src/main/kotlin/App.kt"))
                        add("from_line", param("integer", "First line to read, 1 based. Defaults to 1."))
                        add("to_line", param("integer", "Last line to read, inclusive. Defaults to 200 lines."))
                    },
                    listOf("path")
                )
            )
            tools.add(
                tool(
                    LIST_DIR,
                    "List one directory of the project so you can navigate instead of guessing at paths.",
                    JsonObject().apply {
                        add("path", param("string", "Directory relative to the project root. Use \".\" for the root."))
                    },
                    listOf("path")
                )
            )
            tools.add(
                tool(
                    GREP,
                    "Search the project with a case insensitive regular expression. Returns path:line: text.",
                    JsonObject().apply {
                        add("pattern", param("string", "Regular expression, for example \"class .*Service\""))
                        add("max_results", param("integer", "Maximum matches to return, 1 to 60. Defaults to 20."))
                    },
                    listOf("pattern")
                )
            )
            tools.add(
                tool(
                    READ_ROUTES,
                    "List the HTTP routes, their handlers, and any calls with no matching route.",
                    JsonObject(),
                    emptyList()
                )
            )
            return tools
        }

        private fun tool(name: String, description: String, properties: JsonObject, required: List<String>): JsonObject {
            val parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", properties)
                add("required", JsonArray().also { array -> required.forEach(array::add) })
            }
            val function = JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parameters)
            }
            return JsonObject().apply {
                addProperty("type", "function")
                add("function", function)
            }
        }

        private fun param(type: String, description: String): JsonObject = JsonObject().apply {
            addProperty("type", type)
            addProperty("description", description)
        }
    }
}
