package com.example.yasinreel.harvest

import com.example.ProjectFlowAnalyzer
import com.example.ProjectGraph
import com.example.yasinreel.model.Chain
import com.example.yasinreel.model.Dependency
import com.example.yasinreel.model.DirFact
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.EvidenceStats
import com.example.yasinreel.model.FileRef
import com.example.yasinreel.model.LanguageStat
import com.example.yasinreel.model.NotableFile
import com.example.yasinreel.model.Palette
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Stage 1: everything the film later claims, read straight off disk.
 *
 * No model runs here and no network is touched, which is what lets the later stages be
 * checked. If a sentence in the finished video cannot be traced back into this object,
 * it is a hallucination and the validator throws it out.
 *
 * Runs inside the caller's `ReadAction.nonBlocking`, so it starts no read action of its
 * own. Every limit in here exists because this is the IDE's thread, not ours: a
 * hundred-thousand-file monorepo has to degrade to a partial answer, never to a freeze.
 */
object EvidenceHarvester {

    private val logger = Logger.getInstance(EvidenceHarvester::class.java)

    private const val MAX_FILE_SIZE = 1_000_000L
    private const val MAX_FILES = 20_000
    private const val MAX_TEXT_LOADS = 6_000
    private const val README_CHARS = 4_000
    private const val HEAD_LINES = 40
    private const val MAX_DEPENDENCIES = 40
    private const val MAX_ENTRY_POINTS = 10
    private const val MAX_LANGUAGES = 8
    private const val MAX_DIRS = 12
    private const val MAX_NOTABLE_FILES = 5
    private const val MAX_MANIFESTS = 24

    private val languageByExtension = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin",
        "java" to "Java",
        "py" to "Python",
        "ts" to "TypeScript", "tsx" to "TypeScript React",
        "jsx" to "React/JSX",
        "js" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript",
        "vue" to "Vue", "svelte" to "Svelte",
        "css" to "CSS", "scss" to "CSS", "sass" to "CSS", "less" to "CSS",
        "html" to "HTML", "htm" to "HTML",
        "go" to "Go", "rs" to "Rust", "rb" to "Ruby", "php" to "PHP",
        "cs" to "C#", "swift" to "Swift", "m" to "Objective-C", "mm" to "Objective-C",
        "c" to "C", "h" to "C", "cpp" to "C++", "cc" to "C++", "hpp" to "C++",
        "dart" to "Dart", "scala" to "Scala", "sh" to "Shell", "bash" to "Shell",
        "sql" to "SQL", "lua" to "Lua", "ex" to "Elixir", "exs" to "Elixir"
    )

    /** Counted in the totals but never shown as a language: config and prose are not a stack. */
    private val countableExtras = setOf(
        "json", "yaml", "yml", "toml", "xml", "md", "txt", "gradle", "properties", "cfg", "ini"
    )

    private val lockFiles = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "poetry.lock",
        "composer.lock", "gemfile.lock", "go.sum"
    )

    private val ignoredDirs = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage", "bin", "obj",
        ".next", ".nuxt", ".venv", "venv", "env", "__pycache__", ".gradle", ".idea", ".git",
        ".kotlin", ".intellijplatform", ".cache", "site-packages", "pods", "deriveddata"
    )

    /**
     * Never opened, not even to count lines. The scrubber is the second line of defence,
     * and a file whose whole purpose is holding credentials should never reach it.
     */
    private val sensitiveNamePattern = Regex(
        """(?i)^(\.env.*|.*\.(pem|key|p12|pfx|jks|keystore|crt|cer|ppk)|id_rsa.*|id_ed25519.*|.*service[_\-]account.*\.json|.*credentials.*\.json|\.npmrc|\.netrc|secrets?\.(ya?ml|json|toml))$"""
    )

    private val readmePattern = Regex("""(?i)^readme(\.(md|markdown|txt|rst|adoc))?$""")

    private val manifestNames = setOf(
        "package.json", "requirements.txt", "pyproject.toml", "pom.xml", "build.gradle",
        "build.gradle.kts", "cargo.toml", "go.mod", "docker-compose.yml", "docker-compose.yaml",
        "compose.yml", "compose.yaml", "plugin.xml"
    )

    private val composeNames = setOf("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")

    private val entryNamePattern =
        Regex("""(?i)^(main|index|app|application|manage|cli|server|__main__)\.(kt|kts|java|py|js|jsx|ts|tsx|mjs|go|rs|rb|php|cs|swift)$""")
    private val applicationNamePattern = Regex("""(?i)^[A-Za-z0-9_]+Application\.(kt|java)$""")

    private val composeServicePattern = Regex("""^\s{1,4}([A-Za-z0-9._\-]+):\s*$""")
    private val pluginClassPattern = Regex("""(?:implementation|factoryClass|instance)\s*=\s*["']([\w.]+)["']""")

    private val gradleDependencyPattern = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|ksp|kapt|annotationProcessor)\s*[( ]\s*["']([^"']+)["']"""
    )
    /** Version catalogs hide the coordinate, so `implementation(libs.okhttp)` needs its own read. */
    private val gradleCatalogPattern = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|ksp|kapt|annotationProcessor)\s*\(\s*libs\.([A-Za-z0-9.\-]+)\s*\)"""
    )
    private val mavenDependencyPattern = Regex("""<dependency>([\s\S]{0,800}?)</dependency>""")
    private val mavenArtifactPattern = Regex("""<artifactId>\s*([^<\s]+)\s*</artifactId>""")
    private val tomlSectionPattern = Regex("""(?m)^\[[^\]\n]*dependencies[^\]\n]*]\s*\r?\n([\s\S]{0,4000}?)(?=^\[|\z)""")
    private val tomlKeyPattern = Regex("""(?m)^\s*([A-Za-z0-9_.\-]+)\s*=""")
    private val pep621Pattern = Regex("""dependencies\s*=\s*\[([\s\S]{0,3000}?)]""")
    private val quotedPattern = Regex("""["']([^"']+)["']""")
    private val goRequirePattern = Regex("""^(?:require\s+)?([A-Za-z0-9][\w.\-/]+)\s+v\d""")
    private val requirementSplitPattern = Regex("""[\s<>=!~;\[]""")

    /**
     * Ordered, because the first match wins and the specific names have to beat the generic
     * ones: `next-auth` is auth before it is `next`, and `firebase-auth` is auth before cloud.
     */
    private val categoryKeywords = listOf(
        "payments" to listOf("stripe", "paypal", "braintree", "adyen", "razorpay", "dodopayments", "lemonsqueezy"),
        "auth" to listOf("auth0", "next-auth", "better-auth", "passport", "keycloak", "clerk", "jwt", "oauth", "bcrypt", "firebase-auth"),
        "ai" to listOf("openai", "anthropic", "langchain", "llama", "huggingface", "transformers", "tensorflow", "pytorch", "torch", "cohere", "ollama", "gemini", "mistral"),
        "database" to listOf("prisma", "sqlalchemy", "hibernate", "mongoose", "postgres", "psycopg", "mysql", "sqlite", "redis", "typeorm", "drizzle", "exposed", "jooq", "mongodb", "dynamodb", "flyway", "liquibase"),
        "ui" to listOf("react", "vue", "svelte", "angular", "tailwind", "next", "nuxt", "chakra", "mui", "bootstrap", "shadcn", "framer-motion", "gsap", "reactflow", "d3"),
        "testing" to listOf("jest", "vitest", "pytest", "junit", "mocha", "chai", "cypress", "playwright", "testng", "mockk", "mockito"),
        "http" to listOf("axios", "okhttp", "retrofit", "requests", "httpx", "ktor", "urllib3", "superagent", "express", "fastapi", "flask", "django"),
        "cloud" to listOf("aws-sdk", "boto3", "azure", "google-cloud", "firebase", "vercel", "supabase", "kubernetes", "docker")
    )

    /**
     * [only] restricts the scan to a set of project-relative paths, which is how a recap reel is
     * kept to the work done in a date range. Null means the whole project, the original behaviour.
     *
     * This narrows the scan alone. Chain detection, the brand palette and the flow analysis walk
     * the project themselves and still see all of it, which is deliberate: a recap still has to
     * place the changed files inside the architecture that surrounds them.
     */
    fun harvest(project: Project, only: Set<String>? = null): Evidence {
        val projectPath = project.basePath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        val scan = section("scan", Scan()) { scanProject(project, projectPath, only) }
        val graph = section<ProjectGraph?>("flow-analysis", null) { ProjectFlowAnalyzer.analyze(project) }

        val evidence = Evidence(
            projectName = project.name,
            projectPath = projectPath,
            readme = section<String?>("readme", null) { readme(scan) },
            stats = EvidenceStats(
                totalFiles = scan.totalFiles,
                totalLines = scan.totalLines,
                testFiles = scan.testFiles,
                httpCalls = graph?.stats?.frontendCalls ?: 0,
                httpRoutes = graph?.stats?.backendEndpoints ?: 0,
                matchedCalls = graph?.stats?.matchedCalls ?: 0
            ),
            languages = section("languages", emptyList<LanguageStat>()) { languages(scan) },
            dependencies = section("dependencies", emptyList<Dependency>()) { dependencies(scan) },
            topLevelDirs = section("directories", emptyList<DirFact>()) { topLevelDirs(scan) },
            entryPoints = section("entry-points", emptyList<FileRef>()) { entryPoints(scan, projectPath) },
            chains = section("chains", emptyList<Chain>()) { ChainDetector.detect(project, graph) },
            palette = section("palette", Palette(emptyList(), null)) { BrandPalette.detect(project) },
            notableFiles = section("notable-files", emptyList<NotableFile>()) { notableFiles(scan) }
        )

        logger.info(
            "Nexus Reel harvest: files=${evidence.stats.totalFiles}, lines=${evidence.stats.totalLines}, " +
                "languages=${evidence.languages.size}, deps=${evidence.dependencies.size}, " +
                "chains=${evidence.chains.size}"
        )
        // Last thing stage 1 does, so nothing downstream can be handed an unscrubbed string.
        return scrubbed(evidence)
    }

    private fun scanProject(project: Project, projectPath: String, only: Set<String>?): Scan {
        val scan = Scan()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory) {
                val relative = relativePath(file, projectPath)
                // A recap narrows here, which constrains languages, directories, notable files,
                // entry points, stats and the manifests behind dependencies all at once.
                if (!isIgnored(relative) && !sensitiveNamePattern.matches(file.name) &&
                    (only == null || relative in only)
                ) {
                    // One unreadable file is not a failed harvest, so the guard sits per file.
                    try {
                        record(scan, file, relative)
                    } catch (cancelled: ProcessCanceledException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        logger.debug("Skipped ${file.name} during harvest", failure)
                    }
                }
            }
            scan.totalFiles < MAX_FILES
        }
        return scan
    }

    private fun record(scan: Scan, file: VirtualFile, relative: String) {
        scan.totalFiles++
        val name = file.name.lowercase()
        val extension = file.extension?.lowercase().orEmpty()
        val language = languageByExtension[extension]

        if (relative.contains('/')) {
            val top = relative.substringBefore('/')
            scan.dirCounts[top] = (scan.dirCounts[top] ?: 0) + 1
        }
        if (isTest(relative)) scan.testFiles++
        if (language != null) scan.languageFiles[language] = (scan.languageFiles[language] ?: 0) + 1
        if (name in manifestNames && scan.manifests.size < MAX_MANIFESTS) scan.manifests.add(file)
        if (entryNamePattern.matches(file.name) || applicationNamePattern.matches(file.name)) {
            scan.entryCandidates.add(file)
        }
        scan.filesByName.putIfAbsent(file.nameWithoutExtension, relative)

        val depth = relative.count { it == '/' }
        if (readmePattern.matches(file.name) && depth < scan.readmeDepth) {
            scan.readme = file
            scan.readmeDepth = depth
        }

        val countable = file.length <= MAX_FILE_SIZE &&
            (language != null || extension in countableExtras) &&
            name !in lockFiles &&
            !name.contains(".min.")
        if (!countable || scan.textLoads >= MAX_TEXT_LOADS) return

        scan.textLoads++
        val text = loadText(file) ?: return
        val lines = lineCount(text)
        scan.totalLines += lines
        if (language != null) {
            scan.languageLines[language] = (scan.languageLines[language] ?: 0) + lines
            scan.sourceFiles.add(SizedFile(file, relative, lines))
        }
    }

    private fun readme(scan: Scan): String? {
        val text = scan.readme?.let { loadText(it) } ?: return null
        return text.take(README_CHARS)
    }

    private fun languages(scan: Scan): List<LanguageStat> =
        scan.languageFiles
            .map { (language, files) -> LanguageStat(language, files, scan.languageLines[language] ?: 0) }
            .sortedWith(compareByDescending<LanguageStat> { it.lines }.thenByDescending { it.files })
            .take(MAX_LANGUAGES)

    private fun topLevelDirs(scan: Scan): List<DirFact> =
        scan.dirCounts
            // Build output and vendor folders are already gone; this drops the tooling dotfolders,
            // which are part of the repo but never part of the story.
            .filterNot { (path, _) -> path.startsWith(".") }
            .map { (path, count) -> DirFact(path, count) }
            .sortedByDescending { it.fileCount }
            .take(MAX_DIRS)

    private fun notableFiles(scan: Scan): List<NotableFile> =
        scan.sourceFiles
            .sortedByDescending { it.lines }
            .take(MAX_NOTABLE_FILES)
            .map { sized ->
                val head = loadText(sized.file)
                    ?.lineSequence()
                    ?.take(HEAD_LINES)
                    ?.joinToString("\n")
                    .orEmpty()
                NotableFile(sized.relative, sized.lines, head)
            }

    private fun dependencies(scan: Scan): List<Dependency> {
        val found = LinkedHashMap<String, Dependency>()
        for (file in scan.manifests) {
            if (found.size >= MAX_DEPENDENCIES) break
            val text = loadText(file) ?: continue
            // One broken manifest is normal in a real repo and must cost only its own entries.
            val parsed = try {
                parseManifest(file.name.lowercase(), text)
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("Could not parse ${file.name}", failure)
                emptyList()
            }
            parsed.forEach { dependency -> found.putIfAbsent(dependency.name, dependency) }
        }
        return found.values.take(MAX_DEPENDENCIES)
    }

    private fun parseManifest(name: String, text: String): List<Dependency> = when (name) {
        "package.json" -> npmDependencies(text)
        "requirements.txt" -> text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("-") }
            .map { it.split(requirementSplitPattern).first() }
            .filter { it.isNotBlank() }
            .map { dependency(it, "pypi") }
            .toList()
        "pyproject.toml" -> tomlDependencies(text, "pypi")
        "cargo.toml" -> tomlDependencies(text, "cargo")
        "pom.xml" -> mavenDependencyPattern.findAll(text)
            .mapNotNull { mavenArtifactPattern.find(it.groupValues[1])?.groupValues?.get(1) }
            .map { dependency(it, "maven") }
            .toList()
        "build.gradle", "build.gradle.kts" -> (
            gradleDependencyPattern.findAll(text)
                .map { it.groupValues[1] }
                .map { coordinate -> coordinate.split(':').let { if (it.size >= 2) it[1] else coordinate } } +
                gradleCatalogPattern.findAll(text).map { it.groupValues[1].replace('.', '-') }
            )
            .map { dependency(it, "maven") }
            .toList()
        "go.mod" -> text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("module ") && !it.startsWith("go ") && !it.startsWith("//") }
            .mapNotNull { goRequirePattern.find(it)?.groupValues?.get(1) }
            .map { dependency(it, "go") }
            .toList()
        else -> emptyList()
    }

    private fun npmDependencies(text: String): List<Dependency> {
        val root = JsonParser.parseString(text) as? JsonObject ?: return emptyList()
        return listOf("dependencies", "devDependencies")
            .mapNotNull { runCatching { root.getAsJsonObject(it) }.getOrNull() }
            .flatMap { it.keySet() }
            .map { dependency(it, "npm") }
    }

    /** Covers both `[tool.poetry.dependencies]` style tables and PEP 621 arrays. */
    private fun tomlDependencies(text: String, ecosystem: String): List<Dependency> {
        val tableNames = tomlSectionPattern.findAll(text)
            .flatMap { section -> tomlKeyPattern.findAll(section.groupValues[1]).map { it.groupValues[1] } }
            .filter { it != "python" && it != "version" }
        val arrayNames = pep621Pattern.findAll(text)
            .flatMap { array -> quotedPattern.findAll(array.groupValues[1]).map { it.groupValues[1] } }
            .map { it.split(requirementSplitPattern).first() }
        return (tableNames + arrayNames)
            .filter { it.isNotBlank() }
            .map { dependency(it, ecosystem) }
            .toList()
    }

    private fun dependency(name: String, ecosystem: String) =
        Dependency(name.trim(), ecosystem, categoryOf(name))

    private fun categoryOf(name: String): String? {
        val lowered = name.lowercase()
        return categoryKeywords.firstOrNull { (_, keywords) -> keywords.any { lowered.contains(it) } }?.first
    }

    private fun entryPoints(scan: Scan, projectPath: String): List<FileRef> {
        val fromSources = scan.entryCandidates
            .sortedWith(
                compareBy<VirtualFile>(
                    { entryRank(it.name) },
                    { it.path.count { char -> char == '/' } },
                    { it.path.length }
                )
            )
            .map { FileRef(relativePath(it, projectPath), null) }

        val fromManifests = scan.manifests.flatMap { file ->
            val name = file.name.lowercase()
            when {
                name in composeNames -> composeServices(file, projectPath)
                name == "plugin.xml" -> pluginEntryPoints(file, projectPath, scan)
                else -> emptyList()
            }
        }

        // A monorepo full of index.js files would otherwise bury the compose services and the
        // plugin factories, which say far more about how the product is actually started.
        val reserved = 3
        return (fromSources.take(MAX_ENTRY_POINTS - reserved) + fromManifests + fromSources)
            .distinctBy { "${it.path}:${it.line}" }
            .take(MAX_ENTRY_POINTS)
    }

    /** A compose service is an entry point into the product even when no source file is. */
    private fun composeServices(file: VirtualFile, projectPath: String): List<FileRef> {
        val text = loadText(file) ?: return emptyList()
        val path = relativePath(file, projectPath)
        val refs = mutableListOf<FileRef>()
        var inServices = false
        text.lines().forEachIndexed { index, line ->
            when {
                line.trimEnd() == "services:" -> inServices = true
                !inServices -> Unit
                line.isNotBlank() && !line.first().isWhitespace() -> inServices = false
                else -> composeServicePattern.find(line)?.let { refs.add(FileRef(path, index + 1)) }
            }
        }
        return refs
    }

    /**
     * In a plugin the real entry point is the factory class, not the XML, so we point at the
     * source file when it is in this project and fall back to the declaration when it is not.
     */
    private fun pluginEntryPoints(file: VirtualFile, projectPath: String, scan: Scan): List<FileRef> {
        val text = loadText(file) ?: return emptyList()
        val path = relativePath(file, projectPath)
        return pluginClassPattern.findAll(text)
            .map { match ->
                val simpleName = match.groupValues[1].substringAfterLast('.')
                val source = scan.filesByName[simpleName]
                if (source != null) FileRef(source, null) else FileRef(path, lineNumber(text, match.range.first))
            }
            .toList()
    }

    private fun entryRank(name: String): Int =
        if (name.startsWith("index", true) || name.startsWith("app.", true)) 1 else 0

    private fun scrubbed(evidence: Evidence): Evidence = evidence.copy(
        projectName = SecretScrubber.scrub(evidence.projectName),
        projectPath = SecretScrubber.scrub(evidence.projectPath),
        readme = evidence.readme?.let(SecretScrubber::scrub),
        languages = evidence.languages.map { it.copy(language = SecretScrubber.scrub(it.language)) },
        dependencies = evidence.dependencies.map { it.copy(name = SecretScrubber.scrub(it.name)) },
        topLevelDirs = evidence.topLevelDirs.map { it.copy(path = SecretScrubber.scrub(it.path)) },
        entryPoints = evidence.entryPoints.map { it.copy(path = SecretScrubber.scrub(it.path)) },
        chains = evidence.chains.map { chain ->
            chain.copy(
                name = SecretScrubber.scrub(chain.name),
                steps = chain.steps.map { step ->
                    step.copy(
                        label = SecretScrubber.scrub(step.label),
                        detail = step.detail?.let(SecretScrubber::scrub),
                        file = step.file?.let(SecretScrubber::scrub)
                    )
                }
            )
        },
        palette = evidence.palette.copy(
            colors = evidence.palette.colors.map(SecretScrubber::scrub),
            source = evidence.palette.source?.let(SecretScrubber::scrub)
        ),
        notableFiles = evidence.notableFiles.map {
            it.copy(path = SecretScrubber.scrub(it.path), head = SecretScrubber.scrub(it.head))
        }
    )

    /**
     * One failed section costs that field only. A half-empty Evidence still makes a film.
     *
     * Cancellation is the exception to that: the platform cancels this read action whenever the
     * user edits, and swallowing it would leave us computing an answer nobody is waiting for.
     */
    private fun <T> section(name: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("Nexus Reel harvest section '$name' failed", failure)
            fallback
        }

    private fun isIgnored(relative: String): Boolean =
        relative.split('/').any { it.lowercase() in ignoredDirs }

    private fun isTest(relative: String): Boolean {
        val lowered = relative.lowercase()
        return lowered.contains("test") || lowered.contains("spec")
    }

    private fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

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

    private class Scan {
        var totalFiles = 0
        var totalLines = 0
        var testFiles = 0
        var textLoads = 0
        var readme: VirtualFile? = null
        var readmeDepth = Int.MAX_VALUE
        val languageFiles = mutableMapOf<String, Int>()
        val languageLines = mutableMapOf<String, Int>()
        val dirCounts = mutableMapOf<String, Int>()
        val sourceFiles = mutableListOf<SizedFile>()
        val manifests = mutableListOf<VirtualFile>()
        val entryCandidates = mutableListOf<VirtualFile>()
        val filesByName = mutableMapOf<String, String>()
    }

    private class SizedFile(val file: VirtualFile, val relative: String, val lines: Int)
}
