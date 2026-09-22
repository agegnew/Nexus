package com.example.yasinreel.render

import com.example.yasinreel.harvest.ChangedFiles
import com.example.yasinreel.harvest.EvidenceHarvester
import com.example.yasinreel.model.RecapFacts
import com.example.yasinreel.model.ReelScope
import com.example.yasinreel.llm.EvidenceTools
import com.example.yasinreel.llm.FallbackDirector
import com.example.yasinreel.llm.NarrativeEngine
import com.example.yasinreel.llm.OpenAiNarrativeEngine
import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.ProductModel
import com.example.yasinreel.model.Storyboard
import com.example.yasinreel.settings.KeyDiagnosis
import com.example.yasinreel.settings.ReelSettings
import com.example.yasinreel.tts.TtsClient
import com.example.yasinreel.validate.StoryboardValidator
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns one button press into a playable storyboard.
 *
 * Runs harvest, understanding, directing, checking and voicing in order, on a background
 * thread with a cancellable indicator, and reports each stage honestly rather than showing
 * a fake bar. Stages 1 and 2 go through [ProductModelCache], so a second click of the other
 * cut starts at directing.
 *
 * The rule that shapes the error handling: **the button never dead-ends.** No API key,
 * a thrown LLM call or a dead network all degrade to [FallbackDirector], which builds a
 * storyboard from harvested facts alone, and the caller is told which path was taken.
 * Only a failure to read the project itself is reported as an error, because there is
 * nothing left to make a film out of.
 *
 * All four callbacks are delivered on the EDT, so a caller can touch the browser or
 * the editor from them directly. `onNotice` is the honesty channel: it fires on every
 * successful path, including the cached and the offline one, and says whether the AI
 * actually wrote this cut. A silent fall back that still produces a nice video is the
 * most dangerous failure this product has, so the answer is always sent.
 */
@Service(Service.Level.PROJECT)
class ReelPipeline(private val project: Project) {

    private val logger = Logger.getInstance(ReelPipeline::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val running = AtomicBoolean(false)

    fun generate(
        audience: String,
        scope: ReelScope = ReelScope.launch(),
        onProgress: (String) -> Unit,
        onDone: (Storyboard, List<TtsClient.SceneAudio>) -> Unit,
        onError: (String) -> Unit,
        onNotice: (KeyDiagnosis, Boolean) -> Unit = { _, _ -> },
        targetMs: Int = DEFAULT_TARGET_MS
    ) {
        val progress: (String) -> Unit = { message -> onEdt { onProgress(message) } }
        val done: (Storyboard, List<TtsClient.SceneAudio>) -> Unit =
            { storyboard, clips -> onEdt { onDone(storyboard, clips) } }
        val failed: (String) -> Unit = { message -> onEdt { onError(message) } }
        val notice: (KeyDiagnosis, Boolean) -> Unit =
            { diagnosis, aiWrote -> onEdt { onNotice(diagnosis, aiWrote) } }

        // One run at a time. Two overlapping runs would fight over the same working
        // files and the second would report progress into a player already playing.
        if (!running.compareAndSet(false, true)) {
            failed("A reel is already being generated. Let that one finish, or cancel it in the status bar.")
            return
        }

        val task = object : Task.Backgroundable(project, "Generating the product reel", true) {
            override fun run(indicator: ProgressIndicator) {
                runPipeline(audience, scope, targetMs, indicator, progress, done, failed, notice)
            }

            override fun onFinished() {
                running.set(false)
            }
        }

        // Scheduling from the EDT keeps the asynchronous path, whichever thread the
        // browser bridge happened to call us on.
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

    private fun runPipeline(
        audience: String,
        scope: ReelScope,
        targetMs: Int,
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit,
        onDone: (Storyboard, List<TtsClient.SceneAudio>) -> Unit,
        onError: (String) -> Unit,
        onNotice: (KeyDiagnosis, Boolean) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        indicator.isIndeterminate = false

        try {
            val understood = buildUnderstanding(scope, indicator, onProgress)
            if (understood == null) {
                onError("Nothing changed ${scope.describe()}, so there is no recap to build.")
                return
            }
            val engine = understood.engine
            val evidence = understood.evidence
            val model = understood.model

            val cutLabel = if (scope.isRecap) "$audience recap" else "$audience launch"
            stage(indicator, onProgress, 0.7, "Directing the $cutLabel")
            val directed = directCut(engine, model, evidence, audience, targetMs, onProgress)

            stage(indicator, onProgress, 0.85, "Recording the narration")
            val clips = voice(directed.storyboard, onProgress)

            // After voicing, not before: the recordings are the only true measure of how long
            // each line takes, and the storyboard on disk has to be the film that was shipped.
            val storyboard = fitToNarration(directed.storyboard, clips)
            writeWorkingFile("storyboard-${fileSafe(audience)}.json", storyboard)

            stage(
                indicator, onProgress, 1.0,
                "Ready: ${storyboard.scenes.size} scenes, ${storyboard.totalMs / 1000}s"
            )
            val silent = storyboard.scenes.count { it.narration.isNullOrBlank() }
            logger.info(
                "Nexus Reel produced the $audience cut in ${System.currentTimeMillis() - startedAt}ms " +
                    "(cache ${if (understood.cacheHit) "hit" else "miss"}, model ${if (model != null) "yes" else "fallback"}, " +
                    "ai wrote it: ${directed.byAi}, ${clips.size} narration clips, " +
                    "${storyboard.scenes.size - silent} of ${storyboard.scenes.size} scenes speak)"
            )
            // The film is meant to be narrated end to end, so a hole in the voiceover is worth
            // seeing in the log rather than only hearing on the third viewing.
            if (silent > 0) {
                logger.warn("Nexus Reel $audience cut has $silent scene(s) with no narration")
            }

            // Before onDone on purpose: both go through the same invokeLater queue, so
            // sending the verdict first puts the banner on screen before playback starts
            // rather than over a running reel.
            onNotice(diagnosisOf(engine), directed.byAi)
            onDone(storyboard, clips)
        } catch (e: ProcessCanceledException) {
            logger.info("Nexus Reel generation of the $audience cut was cancelled")
            onError("Cancelled.")
            throw e
        } catch (e: Exception) {
            // Reached only when the project itself could not be read, since every AI
            // stage already degrades to the offline director below.
            logger.warn("Nexus Reel could not generate the $audience cut", e)
            onError(e.message ?: "Reel generation failed.")
        }
    }

    /**
     * The result of stages 1 and 2, which the deck needs exactly as much as the film does.
     */
    data class Understanding(
        val engine: OpenAiNarrativeEngine?,
        val evidence: Evidence,
        val model: ProductModel?,
        val cacheHit: Boolean
    )

    /**
     * Stages 1 and 2: harvest the project, then work out what it is.
     *
     * Public because [com.example.yasinreel.deck.DeckPipeline] runs the same two stages
     * and it would be absurd for it to read the same project a second time. It is also
     * why a deck lands almost immediately once a film has been made, and the other way
     * round: the expensive part is done once per project and cached, and everything
     * downstream is a different way of telling the same understanding.
     *
     * [scope] decides what is being understood: the whole product, or the work done in a
     * date range. A recap harvests a different set of files, so the range is folded into
     * the cache key rather than bypassing the cache.
     *
     * Must be called from a background thread with a progress indicator: it harvests
     * under a read action and then makes network calls.
     *
     * @return null when the caller asked for a recap of a range in which nothing changed
     */
    fun buildUnderstanding(
        scope: ReelScope,
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit
    ): Understanding? {
        val cache = ProductModelCache.getInstance(project)

        // Resolved once per run, so the offline path is announced a single time rather
        // than once per stage that notices the missing key. Held as the concrete type
        // because the diagnosis the banner needs is not on the interface.
        val engine = engineOrNull(onProgress)

        stage(indicator, onProgress, 0.05, "Checking what changed since the last run")

        // A recap is about the work in a range, so it harvests a different set of files and
        // must not reuse a model built from the whole project. The range is folded into the
        // cache key rather than skipping the cache, so re-running the same recap is still free.
        val changed = ChangedFiles.resolve(project, scope)
        if (scope.isRecap && changed == null) {
            onProgress("No history could be read for that range, so this is the whole project instead.")
        }
        // Null rather than an error string, because only the caller knows whether an empty
        // range is worth reporting: the deck asks for the whole project and can never see it.
        if (changed != null && changed.isEmpty) return null
        changed?.let {
            onProgress("Found ${it.paths.size} files changed ${scope.describe()} across ${it.commits} commits.")
        }

        val hash = inReadAction(indicator, onProgress, "content hash") { cache.contentHash() } +
            if (changed != null) ":${scope.kind}:${scope.since}:${scope.until}:${scope.area}:${changed.paths.size}" else ""
        val cached = cache.get(hash)

        if (cached != null) {
            stage(indicator, onProgress, 0.6, "Reusing what this project was already understood to be")
            writeWorkingFile("evidence.json", cached.evidence)
            writeWorkingFile("product-model.json", cached.model)
            return Understanding(engine, cached.evidence, cached.model, cacheHit = true)
        }

        stage(indicator, onProgress, 0.15, "Harvesting facts from the project")
        val harvested = inReadAction(indicator, onProgress, "harvest") {
            EvidenceHarvester.harvest(project, changed?.paths)
        }
        // The range facts travel with the evidence, so the director and the prompts get
        // them without any signature between here and there having to change.
        val evidence = if (changed == null) harvested else harvested.copy(
            recap = RecapFacts(
                since = scope.since,
                until = scope.until,
                area = scope.area,
                commits = changed.commits,
                filesTouched = changed.paths.size,
                linesAdded = changed.added,
                linesDeleted = changed.deleted,
                uncommittedFiles = changed.uncommitted,
                authors = changed.authors,
                subjects = changed.subjects.take(MAX_RECAP_SUBJECTS)
            )
        )
        stage(
            indicator, onProgress, 0.35,
            "Read ${evidence.stats.totalFiles} files and ${evidence.stats.totalLines} lines"
        )

        val model = understand(engine, evidence, indicator, onProgress)
        if (model != null) cache.put(hash, evidence, model)

        writeWorkingFile("evidence.json", evidence)
        model?.let { writeWorkingFile("product-model.json", it) }
        return Understanding(engine, evidence, model, cacheHit = false)
    }

    /** Stage 2. Returns null on any AI failure, which sends directing down the offline path. */
    private fun understand(
        engine: NarrativeEngine?,
        evidence: Evidence,
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit
    ): ProductModel? {
        if (engine == null) return null
        stage(indicator, onProgress, 0.45, "Working out what this project is")
        return try {
            // Outside any read action on purpose: this makes network calls and a read
            // action held across a 30 second request would block every write in the IDE.
            engine.understand(evidence, EvidenceTools(project)) { detail ->
                indicator.text2 = detail
                onProgress(detail)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Nexus Reel could not build a product model, falling back to the facts", e)
            onProgress("The model could not be reached, so the reel is being built from harvested facts alone.")
            null
        }
    }

    /** [byAi] is the single fact the honesty banner turns on, so it travels with the film. */
    private data class Directed(val storyboard: Storyboard, val byAi: Boolean)

    /**
     * [usedFallback] has to come back out of stage 4, not stay inside it.
     *
     * Stage 4 is allowed to throw a directed cut away and shoot the offline one instead,
     * and if that decision did not reach the banner the viewer would be told a model
     * wrote a film it never touched. That is the exact failure this banner exists for.
     */
    private data class Checked(val storyboard: Storyboard, val usedFallback: Boolean)

    /** Stage 3. Guaranteed to return something playable, or to throw only if the fallback itself is broken. */
    private fun directCut(
        engine: NarrativeEngine?,
        model: ProductModel?,
        evidence: Evidence,
        audience: String,
        targetMs: Int,
        onProgress: (String) -> Unit
    ): Directed {
        if (engine != null && model != null) {
            try {
                val directed = engine.direct(model, evidence, audience, targetMs)
                onProgress("Directed the $audience cut from the product model.")
                val result = checked(
                    directed, evidence, targetMs, onProgress,
                    fallback = { FallbackDirector.direct(evidence, audience, targetMs) }
                )
                return Directed(result.storyboard, byAi = !result.usedFallback)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Nexus Reel directing failed for the $audience cut, falling back", e)
                onProgress("Directing failed, so the built in director is taking over and the reel will still play.")
            }
        }
        val fallback = FallbackDirector.direct(evidence, audience, targetMs)
        onProgress("Built the $audience cut with the built in director.")
        // Checked on this path too. The offline director is what actually runs on a machine
        // with no credits, so an unchecked fallback is the cut most people would see.
        return Directed(checked(fallback, evidence, targetMs, onProgress, fallback = null).storyboard, byAi = false)
    }

    /**
     * Stage 4. Never throws: a storyboard that cannot be repaired is still playable.
     *
     * Deliberately outside any read action, because the source ref check touches the
     * file system and a read action held across that would block writes in the IDE.
     */
    private fun checked(
        storyboard: Storyboard,
        evidence: Evidence,
        targetMs: Int,
        onProgress: (String) -> Unit,
        fallback: (() -> Storyboard)?,
        tag: String = ""
    ): Checked {
        val name = fileSafe(storyboard.audience) + tag
        val report = StoryboardValidator.validate(storyboard, evidence, evidence.projectPath, targetMs)
        writeWorkingFile("validation-$name.json", report)
        if (report.ok) {
            onProgress("Checked the ${storyboard.audience} cut: no violations.")
            return Checked(storyboard, usedFallback = false)
        }

        onProgress("Found ${report.violations.size} problem(s) in the ${storyboard.audience} cut, repairing.")
        val repair = StoryboardValidator.repairDetailed(storyboard, report)
        val repaired = repair.storyboard
        val after = StoryboardValidator.validate(repaired, evidence, evidence.projectPath, targetMs)
        // The notes say what repair actually deleted and why. Carried onto the second report
        // rather than left in the log, because the report beside the storyboard is the only
        // record anyone reads when a cut comes back shorter than it was directed.
        writeWorkingFile("validation-$name-after.json", after.copy(notes = repair.notes))
        repair.notes.forEach { logger.info("Nexus Reel repair of the ${storyboard.audience} cut: $it") }

        // Repair deletes rather than rewrites, so a badly leaking model answer comes back
        // gutted: on the real 42-studio cut it dropped 6 scenes to 4 and 90s to 28s, which
        // is not a film. The second report is the honest test of whether anything was
        // saved, and the offline director is already clean, so it wins whenever it was not.
        if (fallback != null && (!after.ok || repaired.scenes.size < MIN_SCENES)) {
            onProgress("Too much of the directed cut failed the check, using the built in director.")
            // Checked in its own right, under its own name so the two reports do not
            // overwrite each other and it stays obvious which film actually shipped.
            return Checked(
                checked(fallback(), evidence, targetMs, onProgress, fallback = null, tag = "-fallback").storyboard,
                usedFallback = true
            )
        }
        return Checked(repaired, usedFallback = false)
    }

    /**
     * Stage 5. Narration, one network call per uncached scene, and never a reason to fail.
     *
     * [TtsClient] already swallows everything it can, so the extra runCatching here is
     * only for a settings service that will not load at all.
     */
    private fun voice(storyboard: Storyboard, onProgress: (String) -> Unit): List<TtsClient.SceneAudio> {
        // No folder means nowhere to cache clips, and an empty path would resolve .idea
        // against whatever directory the IDE happens to have been launched from.
        val base = project.basePath
        if (base.isNullOrBlank()) {
            onProgress("This project has no folder on disk, so the cut plays silently.")
            return emptyList()
        }

        val clips = runCatching {
            TtsClient(ReelSettings.getInstance()).synthesise(storyboard, base, onProgress)
        }.onFailure { logger.warn("Nexus Reel could not record narration", it) }.getOrDefault(emptyList())

        mirrorNarrationForExport(storyboard.audience, clips)
        return clips
    }

    /**
     * Gives every recorded line the room it actually takes, once the recordings exist.
     *
     * The director sizes a scene from an estimate (about 2.5 words a second) and the mp3 is
     * the only thing that knows the truth. Where the recording is longer, the player has no
     * choice but to cut the line off at the scene change, which is a syllable lost in the
     * middle of a sentence now that the voiceover runs across the whole film rather than
     * over four scenes of nine. So the picture waits for the voice.
     *
     * The time is taken back from the scenes whose own line finishes early, so a fitted film
     * is the same length as the one that was directed and validated. Only when there is no
     * slack anywhere does the total grow, because a couple of seconds over target is a much
     * smaller fault than a clipped word.
     */
    private fun fitToNarration(storyboard: Storyboard, clips: List<TtsClient.SceneAudio>): Storyboard {
        val measured = clips.mapNotNull { clip ->
            val ms = clip.durationMs ?: return@mapNotNull null
            if (clip.sceneIndex in storyboard.scenes.indices) clip.sceneIndex to ms else null
        }.toMap()
        if (measured.isEmpty()) return storyboard

        val durations = storyboard.scenes.map { it.durationMs }.toIntArray()
        // What a scene may never drop below: its own recording plus a moment to land on, so
        // the next line does not begin on the last syllable of this one. Capped, because a
        // recording several times longer than any scene means something upstream is wrong,
        // and stretching one scene to thirty seconds would wreck the film rather than save it.
        //
        // A scene also keeps the time its own screen needs to be read, or paying for a long
        // line somewhere else would quietly undo the pacing the director worked out. That
        // half is held at the duration the scene already has, never above it: this pass
        // lengthens a scene only for a recording that outruns it, never for its own text.
        val floors = storyboard.scenes.mapIndexed { index, scene ->
            val spoken = (measured[index]?.plus(CLIP_TAIL_MS) ?: 0).coerceAtMost(MAX_FITTED_SCENE_MS)
            val readable = runCatching { StoryboardValidator.readingFloorMs(scene.slots) }
                .getOrDefault(0)
                .coerceAtMost(durations[index])
            maxOf(spoken, readable)
        }

        val before = durations.sum()
        var grown = 0
        durations.indices.forEach { index ->
            if (durations[index] < floors[index]) {
                grown += floors[index] - durations[index]
                durations[index] = floors[index]
            }
        }
        if (grown == 0) return storyboard

        // Hand the borrowed milliseconds back, a slice at a time from whichever scenes still
        // have room above their own floor. Levelling rather than proportional, so no single
        // short scene is emptied to pay for a long one.
        var owed = grown
        var guard = 0
        while (owed > 0 && guard++ < FIT_PASSES) {
            val donors = durations.indices.filter { durations[it] > floors[it] }
            if (donors.isEmpty()) break
            val share = maxOf(1, owed / donors.size)
            var taken = 0
            for (index in donors) {
                if (taken >= owed) break
                val give = minOf(share, durations[index] - floors[index], owed - taken)
                durations[index] -= give
                taken += give
            }
            if (taken == 0) break
            owed -= taken
        }

        val total = durations.sum()
        logger.info(
            "Nexus Reel fitted the ${storyboard.audience} cut to its recordings: " +
                "${before}ms to ${total}ms, ${measured.size} clips measured, ${owed}ms could not be borrowed back"
        )
        return storyboard.copy(
            totalMs = total,
            scenes = storyboard.scenes.mapIndexed { index, scene ->
                if (scene.durationMs == durations[index]) scene else scene.copy(durationMs = durations[index])
            }
        )
    }

    /**
     * Copies the content addressed clips into the per audience, scene numbered layout
     * [ReelExporter.narrationFor] looks for.
     *
     * The two halves disagree on purpose: the cache has to be keyed by content so an
     * unchanged line is never paid for twice, and the exporter has to read an order it
     * can map onto scenes. Copying is cheap and keeps both right.
     */
    private fun mirrorNarrationForExport(audience: String, clips: List<TtsClient.SceneAudio>) {
        if (clips.isEmpty()) return
        val work = ProductModelCache.getInstance(project).workDir() ?: return
        runCatching {
            val dir = work.resolve(EXPORT_AUDIO_DIR).resolve(fileSafe(audience))
            Files.createDirectories(dir)
            // A previous run of this cut may have left clips for scenes that no longer
            // exist, and the exporter would happily line them up against the new film.
            Files.list(dir).use { entries ->
                entries.filter { it.fileName.toString().endsWith(".mp3") }.forEach { Files.deleteIfExists(it) }
            }
            clips.forEach { clip ->
                val source: Path = work.resolve(clip.relativeUrl)
                if (!Files.isRegularFile(source)) return@forEach
                val name = "scene-%02d.mp3".format(clip.sceneIndex + 1)
                Files.copy(source, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { logger.info("Nexus Reel could not stage narration for export: ${it.message}") }
    }

    private fun engineOrNull(onProgress: (String) -> Unit): OpenAiNarrativeEngine? {
        val settings = runCatching { ReelSettings.getInstance() }
            .onFailure { logger.warn("Nexus Reel settings are unavailable", it) }
            .getOrNull() ?: return null

        // Asked as a question rather than by fetching a key, because the engine now walks
        // every candidate itself and the first one is no longer the whole answer.
        if (!runCatching { settings.hasApiKey() }.getOrDefault(false)) {
            logger.info("Nexus Reel found no API key, so this run uses the built in director")
            onProgress("No API key was found, so the reel is built from the code alone.")
            return null
        }

        return runCatching { OpenAiNarrativeEngine(settings) }
            .onFailure { logger.warn("Nexus Reel could not start the narrative engine", it) }
            .getOrNull()
    }

    /** No engine means no key was found at all, which [KeyDiagnosis] words for itself. */
    private fun diagnosisOf(engine: OpenAiNarrativeEngine?): KeyDiagnosis =
        engine?.let { runCatching { it.diagnosis() }.getOrNull() }
            ?: KeyDiagnosis(emptyList(), null, null)

    private fun stage(
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit,
        fraction: Double,
        message: String
    ) {
        indicator.checkCanceled()
        indicator.fraction = fraction
        indicator.text = message
        indicator.text2 = ""
        logger.info("Nexus Reel: $message")
        onProgress(message)
    }

    /**
     * Runs [block] under a non blocking read action and waits on the pipeline thread.
     *
     * Polled rather than blocked outright so pressing Cancel is felt within [POLL_MS]
     * instead of at the end of a full harvest.
     */
    private fun <T : Any> inReadAction(
        indicator: ProgressIndicator,
        onProgress: (String) -> Unit,
        what: String,
        block: () -> T
    ): T {
        // inSmartMode parks the whole read action until indexing finishes, which on a
        // large project is minutes of apparent nothing. Say so, otherwise the first click
        // of a demo looks like a freeze.
        if (DumbService.getInstance(project).isDumb) {
            indicator.text = "Waiting for indexing to finish before $what"
            onProgress("Waiting for indexing to finish. This starts as soon as the IDE is ready.")
        }

        val promise: CancellablePromise<T> = ReadAction.nonBlocking<T>(Callable { block() })
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())

        while (true) {
            if (indicator.isCanceled) {
                promise.cancel(true)
                throw ProcessCanceledException()
            }
            try {
                return promise.get(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (timedOut: TimeoutException) {
                // Expected, and ignored: the wait is chopped into slices purely so that
                // pressing Cancel is noticed while the read action is still running.
                logger.trace("Nexus Reel is still waiting on $what: ${timedOut.javaClass.simpleName}")
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception) ?: IllegalStateException("Nexus Reel $what failed", e)
            } catch (e: CancellationException) {
                // expireWith fires this when the project closes underneath a long harvest.
                throw ProcessCanceledException(e)
            }
        }
    }

    /** Debug output beside the cache. Failing to write it must never fail a run. */
    private fun writeWorkingFile(name: String, value: Any) {
        val dir = ProductModelCache.getInstance(project).workDir() ?: return
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(name), gson.toJson(value), StandardCharsets.UTF_8)
        }.onFailure { logger.warn("Nexus Reel could not write $name", it) }
    }

    private fun fileSafe(audience: String): String =
        audience.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "cut" }

    private fun onEdt(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDisposed) return
        application.invokeLater(
            Runnable { if (!project.isDisposed) block() },
            ModalityState.any()
        )
    }

    companion object {
        /**
         * One definition of the target length, shared with the validator that enforces it.
         *
         * Only the fallback for a settings service that will not load, since
         * ReelToolWindowFactory reads ReelSettings.targetMs, but leaving the two
         * disagreeing would turn a settings failure into a silently longer film.
         */
        const val DEFAULT_TARGET_MS = StoryboardValidator.DEFAULT_TARGET_MS

        /** Where [mirrorNarrationForExport] stages clips, under the reel work directory. */
        private const val EXPORT_AUDIO_DIR = "audio"

        /** Below this a cut has lost its shape, whatever the remaining scenes still say. */
        private const val MIN_SCENES = 5

        /** The moment a line gets to land in before the next one starts. */
        private const val CLIP_TAIL_MS = 250

        /** A scene fitted to its recording may run over the directed maximum, but not wildly. */
        private const val MAX_FITTED_SCENE_MS = 12_000

        /** Enough levelling passes to settle; the loop also stops as soon as nothing moves. */
        private const val FIT_PASSES = 200

        private const val POLL_MS = 100L

        /** Enough commit subjects to show the shape of the work without flooding the prompt. */
        private const val MAX_RECAP_SUBJECTS = 40

        fun getInstance(project: Project): ReelPipeline = project.service()
    }
}
