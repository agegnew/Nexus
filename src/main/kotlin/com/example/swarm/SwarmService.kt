package com.example.swarm

import com.example.activity.ActivitySettings
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Pure pieces of starting the swarm runner, kept apart from the IDE so they can be unit tested.
 *
 * The runner is `swarm/run.mjs`, a Node program: five Playwright browsers, the agents that drive
 * them, and a small loopback server the Swarm tab streams from. It announces itself with one
 * line on stdout, `NEXUS_SWARM_READY {"port":N,...}`.
 */
object SwarmLaunch {
    const val READY_PREFIX = "NEXUS_SWARM_READY "
    const val ERROR_PREFIX = "NEXUS_SWARM_ERROR "

    data class Ready(val port: Int, val brain: String)

    fun parseReady(line: String): Ready? {
        if (!line.startsWith(READY_PREFIX)) return null
        val json = runCatching { JsonParser.parseString(line.removePrefix(READY_PREFIX)).asJsonObject }.getOrNull() ?: return null
        val port = json.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        if (port !in 1..65535) return null
        return Ready(port, json.get("brain")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
    }

    /** The first candidate that actually holds the runner, in the order given. */
    fun findSwarmDir(candidates: List<File?>): File? =
        candidates.filterNotNull().map { it.absoluteFile.normalize() }.firstOrNull { File(it, "run.mjs").isFile }

    /** Playwright is the one dependency; without it the runner dies on its first import. */
    fun hasDependencies(dir: File): Boolean = File(dir, "node_modules/playwright").isDirectory

    /**
     * Where to look, most deliberate first: an explicit override, the checkout this plugin was
     * built from, then `swarm/` in the open project or any of its parents (the demo app lives
     * two folders below the repository root, so the parents matter).
     */
    fun candidates(override: String?, bundled: String?, projectBase: String?): List<File?> {
        val base = projectBase?.let(::File)
        val parents = generateSequence(base) { it.parentFile }.take(4).map { File(it, "swarm") }.toList()
        return listOf(override?.takeIf { it.isNotBlank() }?.let(::File), bundled?.takeIf { it.isNotBlank() }?.let(::File)) + parents
    }
}

/**
 * Owns the swarm runner process for one project: starts it on first use, hands out its port,
 * and stops it (with every browser it opened) when the project closes.
 */
@Service(Service.Level.PROJECT)
class SwarmService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SwarmService::class.java)

    @Volatile private var process: Process? = null
    @Volatile private var ready: SwarmLaunch.Ready? = null

    /** Blocks for up to [STARTUP_TIMEOUT_S] on first call, so never call it on the UI thread. */
    @Synchronized
    fun ensureRunning(): Result<SwarmLaunch.Ready> = runCatching {
        val alive = process?.isAlive == true
        ready?.takeIf { alive } ?: start().also { ready = it }
    }

    private fun start(): SwarmLaunch.Ready {
        stop()
        val dir = SwarmLaunch.findSwarmDir(
            SwarmLaunch.candidates(System.getenv(DIR_ENV), bundledSwarmDir(), project.basePath)
        ) ?: error("Could not find the swarm/ folder. Set $DIR_ENV to its path.")
        if (!SwarmLaunch.hasDependencies(dir)) {
            error("The swarm runner needs its packages. Run `npm install` in ${dir.path} once.")
        }
        val node = findNode() ?: error("The swarm runner needs Node.js 18 or newer, which is not on PATH.")
        val outDir = File(project.basePath ?: dir.path, ".idea/nexus-swarm")

        val command = GeneralCommandLine(
            node.absolutePath, "run.mjs", "serve",
            "--port", "0",
            "--out", outDir.absolutePath,
            "--exit-with-stdin"
        )
            .withWorkDirectory(dir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        // The same key and model the Activity tab uses, so there is one place to configure it.
        val settings = ActivitySettings.getInstance()
        settings.apiKey.takeIf { it.isNotBlank() }?.let { command.withEnvironment("OPENAI_API_KEY", it) }
        command.withEnvironment("NEXUS_SWARM_MODEL", settings.model)
        // The testers read this project's README, CLAUDE.md and docs before they plan.
        project.basePath?.let { command.withEnvironment("NEXUS_SWARM_PROJECT", it) }

        logger.info("Nexus Swarm: starting ${command.commandLineString} in ${dir.path}")
        val started = command.createProcess()
        val announced = CompletableFuture<SwarmLaunch.Ready>()
        val errors = ArrayDeque<String>()

        thread(name = "nexus-swarm-stdout", isDaemon = true) {
            started.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    SwarmLaunch.parseReady(line)?.let { announced.complete(it) }
                    if (line.startsWith(SwarmLaunch.ERROR_PREFIX)) synchronized(errors) { errors.addLast(line.removePrefix(SwarmLaunch.ERROR_PREFIX)) }
                    logger.info("Nexus Swarm: $line")
                }
            }
            announced.completeExceptionally(IllegalStateException(lastErrors(errors).ifBlank { "The swarm runner exited during startup." }))
        }
        thread(name = "nexus-swarm-stderr", isDaemon = true) {
            started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(errors) {
                        errors.addLast(line)
                        while (errors.size > 8) errors.removeFirst()
                    }
                    logger.info("Nexus Swarm (stderr): $line")
                }
            }
        }

        process = started
        return try {
            announced.get(STARTUP_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            stop()
            error("The swarm runner did not start within $STARTUP_TIMEOUT_S seconds.")
        } catch (e: ExecutionException) {
            stop()
            throw e.cause ?: e
        }
    }

    private fun lastErrors(errors: ArrayDeque<String>): String = synchronized(errors) { errors.joinToString(" ").take(400) }

    private fun findNode(): File? {
        val names = if (SystemInfo.isWindows) listOf("node.exe", "node") else listOf("node")
        names.forEach { name -> PathEnvironmentVariableUtil.findInPath(name)?.takeIf { it.canExecute() }?.let { return it } }
        val home = System.getProperty("user.home")
        return listOf(
            "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node",
            "$home/.volta/bin/node", "$home/.nvm/current/bin/node",
            "C:/Program Files/nodejs/node.exe"
        ).map(::File).firstOrNull { it.isFile && it.canExecute() }
    }

    /** Written into the jar by Gradle: the swarm/ folder of the checkout the plugin was built from. */
    private fun bundledSwarmDir(): String? = runCatching {
        javaClass.classLoader.getResourceAsStream(LOCATION_RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("swarmDir")
        }
    }.getOrNull()

    /** Kills the runner and, first, the browsers it launched, which would otherwise outlive it. */
    @Synchronized
    fun stop() {
        val running = process ?: return
        process = null
        ready = null
        runCatching { running.outputStream.close() }
        runCatching { running.toHandle().descendants().forEach { it.destroy() } }
        running.destroy()
        if (!runCatching { running.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) running.destroyForcibly()
    }

    override fun dispose() = stop()

    companion object {
        private const val DIR_ENV = "NEXUS_SWARM_DIR"
        private const val LOCATION_RESOURCE = "nexus-swarm.properties"
        private const val STARTUP_TIMEOUT_S = 25L

        fun getInstance(project: Project): SwarmService = project.service()
    }
}
