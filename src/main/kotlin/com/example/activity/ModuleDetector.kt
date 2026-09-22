package com.example.activity

import com.google.gson.JsonParser
import java.io.File

/** A part of the project the user can scope a summary to. */
data class ActivityModule(
    val id: String,
    val name: String,
    /** Directory relative to the repository root; "" for the root itself. */
    val path: String,
    /** frontend | backend | fullstack | library */
    val kind: String
)

/**
 * Works out what the project is made of by looking for build manifests on disk.
 *
 * This deliberately does not reuse the visualizer's analysis: the activity tab must keep working
 * whichever analyzer version the plugin is built against, and it needs an answer before any
 * analysis has run.
 */
object ModuleDetector {

    private const val MAX_DEPTH = 3

    private val ignored = setOf(
        "node_modules", "dist", "build", "out", "target", "coverage", ".next", ".nuxt", ".output",
        ".svelte-kit", ".angular", ".turbo", ".cache", ".gradle", ".idea", ".git", "venv", ".venv",
        "env", "__pycache__", "vendor", "site-packages", "bin", "obj", ".intellijPlatform"
    )

    private val jvmManifests = setOf("build.gradle", "build.gradle.kts", "pom.xml")
    private val pythonManifests = setOf("requirements.txt", "pyproject.toml", "setup.py", "manage.py", "Pipfile")
    private val otherManifests = setOf("go.mod", "Cargo.toml", "composer.json", "Gemfile")

    private val frontendMarkers = listOf(
        "react", "react-dom", "vue", "@angular/core", "svelte", "next", "nuxt", "solid-js",
        "preact", "@vitejs/plugin-react", "vite"
    )
    private val backendMarkers = listOf(
        "express", "koa", "fastify", "@nestjs/core", "hapi", "@hapi/hapi", "apollo-server",
        "mongoose", "prisma", "sequelize", "typeorm"
    )

    fun detect(root: File): List<ActivityModule> {
        if (!root.isDirectory) return emptyList()
        // Canonicalise first: a path like "project/." would otherwise name the root module ".".
        val base = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
        val found = mutableListOf<ActivityModule>()
        scan(base, base, 0, found)

        // Guarantee an owner for files outside any detected module (README, CI config, scripts).
        if (found.none { it.path.isEmpty() }) {
            found += ActivityModule("root", base.name, "", "library")
        }
        return found.sortedBy { it.path }
    }

    private fun scan(root: File, dir: File, depth: Int, found: MutableList<ActivityModule>) {
        val children = dir.listFiles() ?: return
        val names = children.filter { it.isFile }.map { it.name }.toSet()

        kindOf(dir, names)?.let { kind ->
            val path = dir.relativeTo(root).invariantSeparatorsPath.let { if (it == ".") "" else it }
            found += ActivityModule(
                id = if (path.isEmpty()) "root" else path.replace('/', '-'),
                name = if (path.isEmpty()) root.name else dir.name,
                path = path,
                kind = kind
            )
        }

        if (depth >= MAX_DEPTH) return
        children.asSequence()
            .filter { it.isDirectory && it.name !in ignored && !it.name.startsWith(".") }
            .forEach { scan(root, it, depth + 1, found) }
    }

    private fun kindOf(dir: File, names: Set<String>): String? = when {
        "package.json" in names -> packageJsonKind(File(dir, "package.json"))
        names.any { it in jvmManifests } -> "backend"
        names.any { it in pythonManifests } -> "backend"
        names.any { it in otherManifests } -> "backend"
        else -> null
    }

    /** A package's dependencies say more about its role than its name does. */
    private fun packageJsonKind(manifest: File): String {
        val dependencies = runCatching {
            val root = JsonParser.parseString(manifest.readText()).asJsonObject
            listOf("dependencies", "devDependencies", "peerDependencies")
                .mapNotNull { root.getAsJsonObject(it) }
                .flatMap { it.keySet() }
                .toSet()
        }.getOrDefault(emptySet())

        val frontend = dependencies.any { it in frontendMarkers }
        val backend = dependencies.any { it in backendMarkers }
        return when {
            frontend && backend -> "fullstack"
            frontend -> "frontend"
            backend -> "backend"
            else -> "library"
        }
    }
}
