package com.example.yasinreel.model

/**
 * Stage 1 output: facts harvested from the project, with no interpretation.
 *
 * Everything here is something we literally read off disk. Nothing in this file
 * is a judgement, an inference or a guess. That is the point: this is the ground
 * truth the AI stages must cite, so that a claim in the finished film can always
 * be traced back to something real.
 *
 * Every string field has already passed through SecretScrubber.
 */
data class Evidence(
    val projectName: String,
    val projectPath: String,
    val readme: String?,
    val stats: EvidenceStats,
    val languages: List<LanguageStat>,
    val dependencies: List<Dependency>,
    val topLevelDirs: List<DirFact>,
    val entryPoints: List<FileRef>,
    val chains: List<Chain>,
    val palette: Palette,
    val notableFiles: List<NotableFile>,
    /**
     * The product's own interface, when it has one that can be read honestly.
     *
     * Null for every project that is not a front end, which is most of them, and null
     * whenever either half (the words or the colours) could not be read, because half a
     * recreation is a mock-up of a product that does not exist.
     *
     * Nullable rather than an empty default, because this file is also read back from a
     * cached `evidence.json`, and Gson fills a missing member by leaving the field alone
     * rather than by running the constructor. A default here would be a promise the
     * deserialiser does not keep: every harvest cached before this field existed comes
     * back with a null in a slot the type says cannot hold one, and the first call on it
     * throws inside a film that was otherwise fine.
     */
    val ui: ProductUi? = null,
    /**
     * Set only for a recap reel. Null means the evidence is the whole project, which is what
     * every existing caller and every cached evidence.json expects, so the default keeps them
     * reading correctly.
     */
    val recap: RecapFacts? = null
)

/**
 * What a recap reel is about: the work done in a date range, as read from git.
 *
 * It rides on [Evidence] rather than being threaded through the director signatures, so the
 * prompts, the offline director and the validator all see it without any of them changing shape.
 */
data class RecapFacts(
    /** yyyy-MM-dd bounds, inclusive. */
    val since: String,
    val until: String,
    /** "all", "frontend", "backend" or a module name. */
    val area: String,
    val commits: Int,
    val filesTouched: Int,
    val linesAdded: Int,
    val linesDeleted: Int,
    val uncommittedFiles: Int,
    val authors: List<String>,
    /** Commit subjects, newest first: the plainest statement of intent the history has. */
    val subjects: List<String>,
    /**
     * Where the work landed, counted rather than described.
     *
     * A period's numbers say how much changed. They do not say where, and "where" is the
     * first thing anyone asks when shown that four thousand lines moved. Derived from the
     * changed paths, so it is a fact about the period and not a judgement about it.
     */
    val areas: List<AreaChange> = emptyList(),
    /**
     * Total lines across the project before the period began.
     *
     * The harvester counts the project as it stands now, which is the after. The before is
     * that number with the period's own work taken back out of it, which is the only
     * honest before available without checking the old revision out.
     */
    val linesBefore: Int = 0
)

/** One part of the project and how much of the period landed in it. */
data class AreaChange(val name: String, val files: Int)

data class EvidenceStats(
    val totalFiles: Int,
    val totalLines: Int,
    val testFiles: Int,
    val httpCalls: Int,
    val httpRoutes: Int,
    val matchedCalls: Int
)

data class LanguageStat(val language: String, val files: Int, val lines: Int)

/** [category] is a coarse hint such as "ui", "payments", "database", "ai", or null. */
data class Dependency(val name: String, val ecosystem: String, val category: String?)

data class DirFact(val path: String, val fileCount: Int)

data class FileRef(val path: String, val line: Int?)

/**
 * A traced path through the system, and the source for the `flow-trace` scene.
 *
 * Deliberately not HTTP-specific. [kind] is "http" when it came from matching a
 * frontend call to a backend route, "bridge" when it crosses a language boundary
 * (executeJavaScript, postMessage, CustomEvent, JNI), or "entrypoint" for a plain
 * call chain. Nexus itself has zero HTTP calls, so on our own repo the only chains
 * are bridges, which is exactly why this is generic.
 */
data class Chain(
    val id: String,
    val name: String,
    val kind: String,
    val steps: List<ChainStep>
)

data class ChainStep(
    val label: String,
    val detail: String?,
    val file: String?,
    val line: Int?
)

/** [colors] are CSS colour strings exactly as found, hex or oklch. */
data class Palette(val colors: List<String>, val source: String?)

/** [head] is the first ~40 lines, scrubbed, for the `code-reveal` scene. */
data class NotableFile(val path: String, val lines: Int, val head: String)
