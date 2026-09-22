package com.example.yasinreel.deck

import com.example.yasinreel.model.Audience
import com.example.yasinreel.render.ReelExporter
import com.example.yasinreel.render.ReelPipeline
import com.google.gson.GsonBuilder
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds one deck, end to end, and writes it where the user can find it.
 *
 * Stages 1 and 2 are not repeated here: they are [ReelPipeline.buildUnderstanding],
 * the same call the film makes, reading the same cache. That is the whole reason a deck
 * arrives in about a second on a project whose film has already been generated, and it
 * is also why the deck and the film can never disagree about what the project is.
 */
@Service(Service.Level.PROJECT)
class DeckPipeline(private val project: Project) {

    private val logger = Logger.getInstance(DeckPipeline::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val running = AtomicBoolean(false)

    data class Built(
        val deck: Deck,
        val file: File,
        val slides: Int,
        val byAi: Boolean,
        val cacheHit: Boolean,
        val issues: List<String>,
        val elapsedMs: Long
    )

    fun generate(
        audience: String,
        onProgress: (String) -> Unit,
        onDone: (Built) -> Unit,
        onError: (String) -> Unit
    ) {
        val progress: (String) -> Unit = { message -> onEdt { onProgress(message) } }
        val done: (Built) -> Unit = { built -> onEdt { onDone(built) } }
        val failed: (String) -> Unit = { message -> onEdt { onError(message) } }

        // One at a time, for the same reason the film allows one: two runs would fight
        // over the same working files and write over each other's output.
        if (!running.compareAndSet(false, true)) {
            failed("A deck is already being generated. Let that one finish, or cancel it in the status bar.")
            return
        }

        val task = object : Task.Backgroundable(project, "Building the $audience deck", true) {
            override fun run(indicator: ProgressIndicator) = build(audience, indicator, progress, done, failed)
            override fun onFinished() = running.set(false)
        }

        ApplicationManager.getApplication().invokeLater(
            Runnable {
                if (project.isDisposed) {
                    running.set(false)
                    return@Runnable
                }
                ProgressManager.getInstance().run(task)
            },
            ModalityState.any()
        )
    }

    private fun build(
        audience: String,
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit,
        onDone: (Built) -> Unit,
        onError: (String) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        indicator.isIndeterminate = false
        try {
            val understood = ReelPipeline.getInstance(project).buildUnderstanding(indicator, onProgress)

            step(indicator, onProgress, 0.72, "Laying out the $audience deck")
            val model = understood.model
            val composed = if (model != null) {
                DeckComposer.fromModel(model, understood.evidence, audience)
            } else {
                DeckComposer.fromEvidence(understood.evidence, audience)
            }

            step(indicator, onProgress, 0.82, "Checking every slide")
            val checked = DeckValidator.validate(composed, understood.evidence)
            writeWorkingFile("deck-${fileSafe(audience)}.json", checked.deck)

            step(indicator, onProgress, 0.9, "Drawing ${checked.deck.slides.size} slides")
            val art = DeckGeometry.render(checked.deck)

            step(indicator, onProgress, 0.96, "Writing the presentation")
            val target = File(exportDir(), "${baseName(audience)}.pptx")
            PptxWriter.write(
                slides = art,
                icons = DeckIcons.load(),
                title = "${checked.deck.title}, ${label(audience)}",
                author = "Nexus",
                target = target
            )

            val elapsed = System.currentTimeMillis() - startedAt
            step(indicator, onProgress, 1.0, "Ready: ${art.size} slides")
            logger.info(
                "Nexus Deck produced the $audience deck in ${elapsed}ms " +
                    "(cache ${if (understood.cacheHit) "hit" else "miss"}, " +
                    "model ${if (model != null) "yes" else "fallback"}, ${art.size} slides, " +
                    "${checked.issues.size} validation notes)"
            )
            checked.issues.forEach { logger.info("Nexus Deck: $it") }

            val built = Built(
                deck = checked.deck,
                file = target,
                slides = art.size,
                byAi = model != null,
                cacheHit = understood.cacheHit,
                issues = checked.issues,
                elapsedMs = elapsed
            )
            notifyDone(built, audience)
            onDone(built)
        } catch (e: ProcessCanceledException) {
            logger.info("Nexus Deck generation of the $audience deck was cancelled")
            onError("Cancelled.")
            throw e
        } catch (e: Exception) {
            logger.warn("Nexus Deck could not generate the $audience deck", e)
            onError(e.message ?: "The deck could not be built.")
        }
    }

    /**
     * Where the file lands, and it is the same folder the films export to.
     *
     * One place for everything Nexus produces beats a second folder that has to be
     * explained. The film's exporter already worked out where that should be, including
     * what to do on a machine with no Desktop, so this asks it rather than deciding again.
     */
    private fun exportDir(): File {
        val location = ReelExporter.getInstance(project).exportLocation()
        val dir = File(location)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Nexus could not create $location to write the deck into.")
        }
        return dir
    }

    private fun notifyDone(built: Built, audience: String) {
        val group = runCatching { NotificationGroupManager.getInstance() }.getOrNull()
            ?.takeIf { it.isGroupRegistered(GROUP_ID) }
            ?.getNotificationGroup(GROUP_ID)
            ?: return
        val notification = group.createNotification(
            "${label(audience)} ready",
            "${built.slides} slides, in ${built.file.parentFile.name}.",
            NotificationType.INFORMATION
        )
        if (RevealFileAction.isSupported()) {
            val name = runCatching { RevealFileAction.getActionName() }.getOrNull() ?: "Show in Finder"
            notification.addAction(NotificationAction.createSimpleExpiring(name) {
                RevealFileAction.openFile(built.file)
            })
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Open") {
            runCatching { com.intellij.ide.BrowserUtil.browse(built.file) }
                .onFailure { logger.warn("Nexus Deck could not open ${built.file}", it) }
        })
        notification.notify(project)
    }

    private fun step(indicator: ProgressIndicator, onProgress: (String) -> Unit, fraction: Double, message: String) {
        indicator.checkCanceled()
        indicator.fraction = fraction
        indicator.text = message
        indicator.text2 = ""
        logger.info("Nexus Deck: $message")
        onProgress(message)
    }

    /** Beside the film's own working files, so one folder holds everything about a run. */
    private fun writeWorkingFile(name: String, value: Any) {
        runCatching {
            val dir = File(File(project.basePath ?: return, ".idea"), "yasin-reel")
            if (!dir.isDirectory && !dir.mkdirs()) return
            File(dir, name).writeText(gson.toJson(value), StandardCharsets.UTF_8)
        }.onFailure { logger.debug("Nexus Deck could not write $name", it) }
    }

    private fun baseName(audience: String): String {
        val project = project.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "project" }
        val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH))
        return "$project-${fileSafe(audience)}-deck-$stamp"
    }

    private fun fileSafe(audience: String): String =
        audience.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "deck" }

    private fun label(audience: String): String =
        if (audience == Audience.STAKEHOLDER) "Stakeholder deck" else "Technical deck"

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) block() }, ModalityState.any())

    companion object {
        private const val GROUP_ID = "Nexus Reel"

        fun getInstance(project: Project): DeckPipeline = project.service()
    }
}
