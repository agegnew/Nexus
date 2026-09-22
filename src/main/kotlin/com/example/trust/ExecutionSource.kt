package com.example.trust

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Where the answer to "has this code ever run" comes from.
 *
 * There is more than one possible answer and they arrive at different costs: a coverage run
 * knows exactly which lines executed, a test listener only knows which files were involved,
 * and a checked-in fixture knows whatever somebody wrote in it. They are kept behind this one
 * interface so the painting never has to care, and so a better source can replace a cruder one
 * without touching a single line of UI code.
 */
interface ExecutionSource {

    /** A short label for the status bar tooltip, so the user can tell where the verdict came from. */
    val label: String

    /** Null when this source has nothing to say about the file, which is different from "all clean". */
    fun trustFor(project: Project, file: VirtualFile): FileTrust?

    /** Everything this source knows about the project, keyed by project-relative path. */
    fun all(project: Project): Map<String, FileTrust>

    /** One sentence for the UI: where these numbers came from and how old they are. */
    fun describe(project: Project): String? = null
}

/**
 * Asks each source in turn and keeps the first that has an opinion.
 *
 * Order is the whole point: real coverage from a real run beats a fixture every time, and the
 * fixture only exists so the feature still shows something on a machine where nobody has run
 * the tests yet. When coverage is present the fixture is never consulted.
 */
class CompositeExecutionSource(private val sources: List<ExecutionSource>) : ExecutionSource {

    override val label: String
        get() = sources.firstOrNull { it.hasData }?.label ?: sources.first().label

    private val ExecutionSource.hasData: Boolean
        get() = this !is CoverageReportSource || reportPath != null

    override fun trustFor(project: Project, file: VirtualFile): FileTrust? =
        sources.firstNotNullOfOrNull { source ->
            source.all(project).takeIf { it.isNotEmpty() }?.let { source.trustFor(project, file) }
        }

    override fun all(project: Project): Map<String, FileTrust> =
        sources.firstNotNullOfOrNull { it.all(project).takeIf(Map<String, FileTrust>::isNotEmpty) }
            ?: emptyMap()

    override fun describe(project: Project): String? =
        sources.firstOrNull { it.all(project).isNotEmpty() }?.describe(project)
}

/**
 * Reads a verdict written to `.nexus/trust.json` at the project root.
 *
 * This exists so the editor painting can be built and demonstrated before the coverage reader
 * is finished, and so a demo can be scripted rather than depending on whatever the machine
 * happens to have executed that morning. It is also the format the coverage reader will write,
 * which means the file doubles as the contract between the two halves of this feature.
 *
 * ```json
 * {
 *   "files": [
 *     { "path": "backend/app/orders.py", "totalLines": 210, "unproven": [[40, 58], [77, 80]] }
 *   ]
 * }
 * ```
 */
class FixtureExecutionSource : ExecutionSource {

    override val label: String = ".nexus/trust.json"

    private val logger = Logger.getInstance(FixtureExecutionSource::class.java)
    private val gson = Gson()

    /** Parsed once per (project, file modification time) so scrolling never re-reads the disk. */
    private var cachedPath: String? = null
    private var cachedStamp: Long = -1
    private var cached: Map<String, FileTrust> = emptyMap()
    private var cachedAutoEnable: Boolean = false

    override fun trustFor(project: Project, file: VirtualFile): FileTrust? {
        val base = project.basePath ?: return null
        val relative = relativePath(base, file) ?: return null
        return load(base)[relative]
    }

    /** Everything the fixture knows, for callers that want project-wide numbers. */
    override fun all(project: Project): Map<String, FileTrust> {
        val base = project.basePath ?: return emptyMap()
        return load(base)
    }

    /**
     * Whether this project asked for the paint to start switched on.
     *
     * The global default stays off, because colouring code nobody asked to have coloured is how
     * a feature gets uninstalled. But a project that carries a `.nexus/trust.json` has opted in
     * by definition, so `"autoEnable": true` in that file is the project saying "yes, show me".
     */
    fun autoEnable(project: Project): Boolean {
        val base = project.basePath ?: return false
        load(base)
        return cachedAutoEnable
    }

    override fun describe(project: Project): String? =
        if (all(project).isEmpty()) null else "from $FIXTURE_PATH, placeholder data"

    private fun relativePath(base: String, file: VirtualFile): String? {
        val path = file.path
        if (!path.startsWith(base)) return null
        return path.removePrefix(base).trimStart('/')
    }

    @Synchronized
    private fun load(base: String): Map<String, FileTrust> {
        val fixture = File(base, FIXTURE_PATH)
        if (!fixture.isFile) {
            cachedPath = null
            cached = emptyMap()
            cachedAutoEnable = false
            return emptyMap()
        }

        val stamp = fixture.lastModified()
        if (fixture.path == cachedPath && stamp == cachedStamp) return cached

        cached = parse(fixture)
        cachedPath = fixture.path
        cachedStamp = stamp
        return cached
    }

    private fun parse(fixture: File): Map<String, FileTrust> = try {
        val payload = gson.fromJson(fixture.readText(), FixturePayload::class.java)
        cachedAutoEnable = payload?.autoEnable == true
        payload?.files.orEmpty()
            .mapNotNull { entry ->
                val path = entry.path?.trim().orEmpty()
                if (path.isEmpty()) null
                else path to FileTrust(
                    path = path,
                    unproven = LineRange.merge(entry.unproven.orEmpty().mapNotNull(::toRange)),
                    stale = LineRange.merge(entry.stale.orEmpty().mapNotNull(::toRange)),
                    totalLines = entry.totalLines ?: 0,
                )
            }
            .toMap()
            .also { logger.info("Nexus Trust read ${it.size} file(s) from ${fixture.path}") }
    } catch (e: JsonSyntaxException) {
        // A broken fixture must not break the editor: paint nothing and say why in the log.
        logger.warn("Nexus Trust could not parse ${fixture.path}", e)
        emptyMap()
    } catch (e: Exception) {
        logger.warn("Nexus Trust could not read ${fixture.path}", e)
        emptyMap()
    }

    /** `[start, end]` pairs, ignoring anything malformed rather than failing the whole file. */
    private fun toRange(pair: List<Int>?): LineRange? {
        if (pair == null || pair.size < 2) return null
        val start = pair[0]
        val end = pair[1]
        if (start < 1 || end < start) return null
        return LineRange(start, end)
    }

    private data class FixturePayload(val files: List<FixtureFile>?, val autoEnable: Boolean?)

    private data class FixtureFile(
        val path: String?,
        val totalLines: Int?,
        val unproven: List<List<Int>>?,
        val stale: List<List<Int>>?,
    )

    companion object {
        const val FIXTURE_PATH = ".nexus/trust.json"
    }
}
