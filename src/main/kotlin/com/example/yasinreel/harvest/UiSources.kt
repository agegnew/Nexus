package com.example.yasinreel.harvest

import com.example.yasinreel.model.ProductUi
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * One walk of the project for the two harvesters that read its interface.
 *
 * [DesignSystemHarvester] needs the stylesheets and the token modules; [UiStructureHarvester]
 * needs the components. Both are bounded, both skip the same places, and doing it once rather
 * than twice keeps the two from disagreeing about which files the project has.
 *
 * Deliberately not narrowed by a recap's date range. A progress update is about the last
 * three weeks of work, but the product it is about is the whole product, and a sidebar
 * redrawn from only the files that changed on Tuesday would be a different sidebar.
 */
object UiSources {

    private val logger = Logger.getInstance(UiSources::class.java)

    private const val MAX_FILE_SIZE = 512_000L
    private const val MAX_STYLE_FILES = 240
    private const val MAX_UI_FILES = 900

    private val styleExtensions = setOf("css", "scss", "sass", "less")
    private val uiExtensions = setOf("tsx", "jsx", "ts", "js", "mjs", "vue", "svelte", "html")

    private val tokenFileName = Regex(
        """(?i)^(tailwind\.config|theme|themes|tokens?|colou?rs|palette|variables|globals|brand|design[-_]?system)\.""" +
            """(css|scss|sass|less|js|ts|cjs|mjs|tsx|json)$"""
    )

    private val ignoredDirs = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage", "bin", "obj",
        ".next", ".nuxt", ".venv", "venv", "env", "__pycache__", ".gradle", ".idea", ".git",
        ".kotlin", ".intellijplatform", ".cache", "site-packages", "pods", "deriveddata",
        // Everything this plugin and its neighbours write into a project they are reading.
        // Without these, the second run harvests the first run's output as the product.
        "yasin-reel", "yasin-deck", "yasin-shared", ".work", "brag-output", ".claude", ".turbo"
    )

    /** Everything the two interface harvesters read, collected in one pass. */
    class Collected(
        val styles: List<DesignSystemHarvester.Source>,
        val ui: List<UiStructureHarvester.Source>
    )

    /**
     * Reads the project's design system and interface, or null when there is none.
     *
     * Null is a real answer and the common one: most repositories are not front ends.
     * Nothing downstream may treat it as a failure, because refusing to draw an interface
     * for a project that has none is the correct behaviour.
     */
    fun productUi(project: Project): ProductUi? =
        try {
            val collected = collect(project)
            val design = DesignSystemHarvester.from(collected.styles)
            val ui = UiStructureHarvester.from(collected.ui, design)
            logger.info(
                "Nexus read the interface of ${project.name}: ${ui.nav.size} rows from ${ui.source ?: "nowhere"}, " +
                    "${ui.stages.size} stages, design ${design.confidence}"
            )
            ui
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("Nexus could not read the interface of ${project.name}", failure)
            null
        }

    private fun collect(project: Project): Collected {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        val styles = mutableListOf<DesignSystemHarvester.Source>()
        val ui = mutableListOf<UiStructureHarvester.Source>()

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && file.length in 1..MAX_FILE_SIZE) {
                val relative = relativeOf(file, base)
                if (!ignored(relative)) {
                    val extension = file.extension?.lowercase().orEmpty()
                    val wanted = extension in styleExtensions || tokenFileName.containsMatchIn(file.name)
                    if (wanted && styles.size < MAX_STYLE_FILES) {
                        read(file)?.let { styles += DesignSystemHarvester.Source(relative, it) }
                    }
                    if (extension in uiExtensions && ui.size < MAX_UI_FILES && !file.name.contains(".min.")) {
                        read(file)?.let { ui += UiStructureHarvester.Source(relative, it) }
                    }
                }
            }
            styles.size < MAX_STYLE_FILES || ui.size < MAX_UI_FILES
        }
        return Collected(styles, ui)
    }

    private fun read(file: VirtualFile): String? =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.debug("Skipped ${file.name} while reading the interface", failure)
            null
        }

    private fun relativeOf(file: VirtualFile, base: String): String {
        val path = file.path.replace('\\', '/')
        return if (base.isNotEmpty() && path.startsWith("$base/")) path.removePrefix("$base/")
        else VfsUtilCore.getRelativePath(file, file.fileSystem.findFileByPath(base) ?: file) ?: path
    }

    private fun ignored(relative: String): Boolean =
        relative.split('/').any { it.lowercase() in ignoredDirs }
}
