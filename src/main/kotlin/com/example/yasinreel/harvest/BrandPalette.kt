package com.example.yasinreel.harvest

import com.example.yasinreel.model.Palette
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Finds the colours the project already wears, so the film looks like the product
 * rather than like a template.
 *
 * Colours are kept verbatim, hex or oklch as written, because the player drops them
 * straight into CSS custom properties and any conversion we did here would be a place
 * for us to be subtly wrong about someone else's brand.
 *
 * Nothing is invented. An empty result is an honest result and the caller supplies a
 * default, which keeps "these are your colours" a claim we can always back up.
 */
object BrandPalette {

    private val logger = Logger.getInstance(BrandPalette::class.java)

    private const val MAX_COLORS = 8
    private const val MAX_FILE_SIZE = 512_000L
    private const val MAX_CANDIDATES_PER_TIER = 40

    private val styleExtensions = setOf("css", "scss", "sass", "less")

    private val tailwindNames = setOf(
        "tailwind.config.js", "tailwind.config.ts", "tailwind.config.cjs", "tailwind.config.mjs"
    )

    private val tokenFileNamePattern =
        Regex("""(?i)^(theme|themes|tokens|colors|colours|palette|variables|globals|brand)\.(css|scss|sass|less|js|ts|json)$""")

    private val rootBlockPattern = Regex(""":root[^{]{0,80}\{([^}]{0,6000})}""")

    private val customPropertyPattern = Regex("""--([A-Za-z0-9_\-]+)\s*:\s*([^;\n]{1,120})""")

    private val hexPattern = Regex("""#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\b""")

    private val functionalColorPattern =
        Regex("""\b(?:oklch|oklab|lch|lab|hsla?|rgba?)\(\s*[^)\n]{1,60}\)""")

    /** Tailwind keeps its palette under `theme.colors` or `theme.extend.colors`. */
    private val tailwindColorsPattern = Regex("""colors\s*:\s*\{([\s\S]{0,4000}?)\n\s*}""")

    private val ignoredPathSegments = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "coverage",
        ".next", ".nuxt", ".venv", "venv", "__pycache__", ".gradle", ".idea",
        // This repository carries a fixture project so the analyzer has real calls and
        // real endpoints to match. It is not part of Nexus, and counting it makes Nexus
        // look like a FastAPI service. Named exactly, not as "demo", because a folder
        // called demo in somebody else's project is usually part of their product.
        "nexus-demo-app"
    )

    /**
     * The name of a custom property says what the colour is for, and the film wants the ones
     * a person would call the brand. Without this ranking a dark theme hands us eight greys
     * and none of its accents, because the greys are declared first.
     */
    private val accentNames = listOf(
        "brand", "primary", "accent", "secondary", "success", "warning", "danger", "error",
        "info", "focus", "highlight", "link", "frontend", "backend", "project", "chart"
    )

    private val chromeNames = listOf(
        "surface", "background", "bg", "border", "text", "shadow", "overlay", "muted",
        "neutral", "gray", "grey", "scrim", "divider", "disabled"
    )

    /** Neutrals are real, but they are never what makes a brand recognisable. */
    private val neutrals = setOf(
        "#fff", "#ffffff", "#000", "#000000", "#fefefe", "#010101", "transparent", "currentcolor"
    )

    fun detect(project: Project): Palette =
        try {
            detectPalette(project)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("Brand palette detection failed, the caller's default will be used", failure)
            Palette(emptyList(), null)
        }

    private fun detectPalette(project: Project): Palette {
        val projectPath = project.basePath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        val tiers = List(3) { mutableListOf<VirtualFile>() }

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory &&
                file.length in 1..MAX_FILE_SIZE &&
                !isIgnored(relativePath(file, projectPath))
            ) {
                val name = file.name.lowercase()
                when {
                    file.extension?.lowercase() in styleExtensions -> tiers[0].add(file)
                    name in tailwindNames -> tiers[1].add(file)
                    tokenFileNamePattern.matches(name) -> tiers[2].add(file)
                }
            }
            tiers.any { it.size < MAX_CANDIDATES_PER_TIER }
        }

        // One file wins outright, because Palette.source has to point somewhere real and a
        // palette stitched from four files is nobody's design system.
        var thin: Palette? = null
        tiers.forEachIndexed { tier, candidates ->
            for (file in candidates.sortedWith(compareBy<VirtualFile>({ depth(it.path) }, { it.path.length }))) {
                val text = loadText(file) ?: continue
                val colors = extract(tier, text)
                if (colors.isEmpty()) continue
                val palette = Palette(colors, relativePath(file, projectPath))
                if (colors.size >= 2) return palette
                if (thin == null) thin = palette
            }
        }
        return thin ?: Palette(emptyList(), null)
    }

    private fun extract(tier: Int, text: String): List<String> {
        val ranked: List<Pair<Int, String>> = when (tier) {
            0 -> rootBlockPattern.findAll(text).toList()
                .flatMap { block -> customPropertyPattern.findAll(block.groupValues[1]).toList() }
                .flatMap { declaration ->
                    val rank = rankOf(declaration.groupValues[1])
                    colorsIn(declaration.groupValues[2]).map { color -> rank to color }
                }
            1 -> colorsIn(tailwindColorsPattern.find(text)?.groupValues?.get(1) ?: text).map { 1 to it }
            else -> colorsIn(text).map { 1 to it }
        }

        val distinct = ranked.sortedBy { it.first }.map { it.second }.distinctBy { it.lowercase() }
        val branded = distinct.filterNot { it.lowercase() in neutrals }
        return (branded + distinct.filter { it.lowercase() in neutrals }).take(MAX_COLORS)
    }

    private fun colorsIn(text: String): List<String> {
        val found = LinkedHashSet<String>()
        hexPattern.findAll(text).forEach { found.add(it.value) }
        functionalColorPattern.findAll(text).forEach { match ->
            // `rgb(var(--brand))` resolves at runtime, so it tells the film nothing.
            if (!match.value.contains("var(")) found.add(match.value)
        }
        return found.toList()
    }

    private fun rankOf(property: String): Int {
        val lowered = property.lowercase()
        // Chrome wins the tie on purpose: `--text-primary` is text, not the primary colour.
        return when {
            chromeNames.any { lowered.contains(it) } -> 2
            accentNames.any { lowered.contains(it) } -> 0
            else -> 1
        }
    }

    private fun isIgnored(path: String): Boolean =
        path.split('/').any { it in ignoredPathSegments }

    private fun depth(path: String): Int = path.count { it == '/' }

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
}
