package com.example.yasinreel.render

import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.ProductModel
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Holds the expensive half of the pipeline, stages 1 and 2, between clicks.
 *
 * Harvesting and understanding cost the same no matter which cut you asked for, and
 * neither depends on the audience. Caching them here is what makes the second click
 * land in a fraction of the time, and it is the claim the demo rests on, so every
 * hit and every miss is logged at INFO with the key that decided it.
 *
 * Stored under `<project>/.idea/yasin-reel/`, which the team's .gitignore already covers.
 */
@Service(Service.Level.PROJECT)
class ProductModelCache(private val project: Project) {

    /** What stages 1 and 2 produced together. Cached as one unit because stage 2 is meaningless without the evidence it cited. */
    data class Understanding(val evidence: Evidence, val model: ProductModel)

    private val logger = Logger.getInstance(ProductModelCache::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Where working files live, or null for a project with no base path (the default project). */
    fun workDir(): Path? {
        val base = project.basePath
        if (base == null) {
            logger.warn("Nexus Reel has no project base path, so nothing can be cached or written")
            return null
        }
        return Path.of(base, IDEA_DIR, WORK_DIR)
    }

    /**
     * A cheap fingerprint of the project's source: file count, total size, newest
     * timestamp and an order independent mix of paths.
     *
     * Deliberately not a content digest. Reading every byte would cost more than the
     * harvest it is meant to skip, and a stamp plus a size catches every edit a user
     * can make from the IDE.
     *
     * Must be called inside a read action: it walks the VFS.
     */
    fun contentHash(): String {
        var files = 0
        var bytes = 0L
        var newest = 0L
        var mix = 0L

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && isSourceLike(file)) {
                files++
                bytes += file.length
                val stamp = file.timeStamp
                if (stamp > newest) newest = stamp
                // Summed rather than folded, so VFS iteration order can never move the key.
                mix += file.path.hashCode().toLong() * 31L + stamp
            }
            // A bounded walk matters more than perfect fidelity on a repository this size;
            // past the cap the count alone still moves the key when files are added.
            files < FILE_SCAN_CAP
        }

        val raw = "$FORMAT_VERSION|$files|$bytes|$newest|$mix"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** The cached understanding for [hash], or null when the project has moved on since it was stored. */
    fun get(hash: String): Understanding? {
        val file = workDir()?.resolve(CACHE_FILE)
        if (file == null || !Files.isRegularFile(file)) {
            logger.info("Nexus Reel cache MISS key=$hash: nothing stored yet")
            return null
        }

        val stored = runCatching { readEnvelope(file) }
            .onFailure { logger.warn("Nexus Reel could not read its cache, treating it as a miss", it) }
            .getOrNull()

        if (stored == null) {
            logger.info("Nexus Reel cache MISS key=$hash: stored entry is unusable or from an older format")
            return null
        }
        if (stored.hash != hash) {
            logger.info("Nexus Reel cache MISS key=$hash: project changed since key=${stored.hash} was stored")
            return null
        }

        logger.info("Nexus Reel cache HIT key=$hash: skipping harvest and understanding entirely")
        return Understanding(stored.evidence, stored.productModel)
    }

    fun put(hash: String, evidence: Evidence, model: ProductModel) {
        val dir = workDir() ?: return
        runCatching {
            Files.createDirectories(dir)
            val target = dir.resolve(CACHE_FILE)
            val temp = dir.resolve("$CACHE_FILE.tmp")
            val text = gson.toJson(Envelope(FORMAT_VERSION, hash, System.currentTimeMillis(), evidence, model))
            // Written aside and moved, so an IDE kill mid-write cannot leave a half file
            // that would then have to be diagnosed as a mysterious cache miss.
            Files.writeString(temp, text, StandardCharsets.UTF_8)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }.onSuccess {
            logger.info("Nexus Reel cache STORE key=$hash: the next click starts at directing")
        }.onFailure {
            logger.warn("Nexus Reel could not write its cache, so the next run will re-analyse", it)
        }
    }

    /** Forces the next run through harvest and understanding again. */
    fun invalidate() {
        val file = workDir()?.resolve(CACHE_FILE) ?: return
        runCatching { Files.deleteIfExists(file) }
            .onSuccess { if (it) logger.info("Nexus Reel cache INVALIDATED by request") }
            .onFailure { logger.warn("Nexus Reel could not delete its cache", it) }
    }

    private fun readEnvelope(file: Path): Envelope? {
        val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)) as? JsonObject ?: return null
        // Gson binds by reflection and would happily hand back an object full of nulls,
        // so the shape is checked before anything downstream trusts the types.
        if (root.get("version")?.asInt != FORMAT_VERSION) return null
        if (!root.has("hash") || !root.has("evidence") || !root.has("productModel")) return null
        return gson.fromJson(root, Envelope::class.java)
    }

    private fun isSourceLike(file: VirtualFile): Boolean {
        val path = file.path
        // Our own cache lives under .idea/yasin-reel/, so writing it must not be the thing
        // that invalidates it. Generated output is skipped for the same reason.
        if (IGNORED_SEGMENTS.any { path.contains(it) }) return false
        val extension = file.extension?.lowercase() ?: return false
        return extension in SOURCE_EXTENSIONS
    }

    private data class Envelope(
        val version: Int,
        val hash: String,
        val createdAt: Long,
        val evidence: Evidence,
        val productModel: ProductModel
    )

    companion object {
        const val IDEA_DIR = ".idea"
        const val WORK_DIR = "yasin-reel"

        private const val CACHE_FILE = "product-model-cache.json"
        private const val FORMAT_VERSION = 1
        private const val FILE_SCAN_CAP = 20_000
        private const val KEY_BYTES = 8

        private val IGNORED_SEGMENTS = listOf(
            "/.idea/", "/.git/", "/node_modules/", "/build/", "/out/",
            "/dist/", "/target/", "/.gradle/", "/.venv/", "/__pycache__/"
        )

        private val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "vue", "svelte",
            "py", "go", "rb", "rs", "php", "cs", "swift", "c", "cc", "cpp", "h", "hpp",
            "scala", "dart", "sql", "gradle", "xml", "json", "yaml", "yml", "toml", "md",
            "html", "css", "scss"
        )

        fun getInstance(project: Project): ProductModelCache = project.service()
    }
}
