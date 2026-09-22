package com.example.yasinreel.render

import com.example.yasinreel.model.Scene
import com.example.yasinreel.model.Storyboard
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Writes a storyboard out as something that exists outside the IDE.
 *
 * Two shapes, one source of truth. Both are assembled from the very files the tool
 * window plays, read out of the plugin jar, so an exported film cannot drift from the
 * one the user just watched: there is no second copy of the CSS, the scene builders or
 * the timeline to keep in sync.
 *
 *  - [writeStandalone] is the layer that needs nothing installed. One `.html` file with
 *    every asset inlined, including narration audio as `data:` URIs. Double click it and
 *    it plays, offline, in any browser, forever.
 *  - [writeHyperFramesProject] is the input to the `.mp4` layer. Same film, laid out to
 *    satisfy the HyperFrames composition contract so the CLI can drive it frame by frame.
 *
 * Neither touches the network, at write time or at play time.
 */
object CompositionWriter {

    private val logger = Logger.getInstance(CompositionWriter::class.java)
    private val gson = Gson()

    /** The player's fixed coordinate space, mirrored from `runtime/timeline.js`. */
    private const val STAGE_W = 1200
    private const val STAGE_H = 675

    /** Mirrors `durationOf` in `runtime/timeline.js`, so exported timings match playback. */
    private const val MIN_SCENE_MS = 300
    private const val DEFAULT_SCENE_MS = 6000

    private const val RESOURCE_ROOT = "yasin-reel"

    /** `scene-07.mp3`, the name the voice stage stages its clips under. */
    private val SCENE_NUMBER = Regex("scene[-_ ]?(\\d+)")

    /**
     * Scene builders and the timeline, in dependency order. Listed rather than
     * discovered because a classloader cannot list a directory inside a jar.
     */
    private val FILM_SCRIPTS = listOf("runtime/icons.js", "runtime/scenes.js", "runtime/timeline.js")

    /** The transport. Only the standalone file needs it, a headless render has no UI. */
    private val PLAYER_SCRIPTS = listOf("runtime/player.js")

    /**
     * One self-contained HTML file.
     *
     * [audioFiles] map positionally onto [Storyboard.scenes]. An entry that does not
     * exist on disk means "this scene has no narration", and a list that is exactly as
     * long as the narrated scenes is taken to be in narration order instead, which is
     * the shape a per-line voice stage naturally produces.
     *
     * @return the written file, named `<project>-<audience>-<date>.html`
     */
    fun writeStandalone(storyboard: Storyboard, audioFiles: List<File>, outDir: File): File {
        val target = File(prepare(outDir), "${baseName(storyboard)}.html")
        val clips = pair(storyboard, audioFiles)

        val css = resourceText("reel.css")
        val scripts = (listOf("vendor/gsap.min.js") + FILM_SCRIPTS + PLAYER_SCRIPTS)
            .joinToString("\n") { "<script>\n${jsSafe(resourceText(it))}\n</script>" }

        // Assembled line by line rather than as one templated block: a multi line value
        // interpolated into a trimIndent literal drags the whole block's indentation
        // with it, and the inlined CSS and scripts are all multi line.
        val html = listOf(
            "<!doctype html>",
            "<html lang=\"en\">",
            "<head>",
            "<meta charset=\"UTF-8\"/>",
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>",
            "<title>${escapeHtml(projectName(storyboard))}, ${escapeHtml(audienceLabel(storyboard.audience))}</title>",
            "<style>",
            cssSafe(css),
            STANDALONE_CSS,
            "</style>",
            "</head>",
            "<body>",
            coverAndStage(storyboard, clips),
            audioTags(clips, inline = true),
            scripts,
            "<script>",
            "window.__REEL_STORYBOARD__ = ${jsonLiteral(storyboard)};",
            STANDALONE_BOOT,
            "</script>",
            "</body>",
            "</html>"
        ).joinToString("\n")

        target.writeText(html, StandardCharsets.UTF_8)
        logger.info(
            "Nexus Reel wrote a standalone film to ${target.absolutePath} " +
                "(${target.length() / 1024}kB, ${clips.count { it.file != null }} narration clips inlined)"
        )
        return target
    }

    /**
     * A HyperFrames composition directory: `index.html` plus `assets/`.
     *
     * That really is the whole contract, confirmed against a completed render. No
     * `package.json` and no `hyperframes.json` are needed, which matters because it
     * means the export has nothing to install and nothing to resolve.
     *
     * What the CLI requires of the page is satisfied by the boot script below: a root
     * carrying `data-composition-id`, `data-width`, `data-height` and `data-duration`,
     * scenes carrying `data-start` and `data-duration`, exactly one paused timeline
     * registered at `window.__timelines[compositionId]`, GSAP vendored beside the page,
     * and no network request at render time.
     *
     * @return the composition directory, which is what the CLI is run from
     */
    fun writeHyperFramesProject(storyboard: Storyboard, audioFiles: List<File>, outDir: File): File {
        val dir = File(prepare(outDir), baseName(storyboard))
        val assets = File(dir, "assets")
        if (!assets.isDirectory && !assets.mkdirs()) {
            throw IOException("Nexus Reel could not create ${assets.absolutePath}")
        }

        copyResource("reel.css", File(assets, "reel.css"))
        copyResource("vendor/gsap.min.js", File(assets, "gsap.min.js"))
        FILM_SCRIPTS.forEach { copyResource(it, File(assets, it.substringAfterLast('/'))) }

        // Copied rather than referenced in place: a composition that points at files
        // elsewhere on the disk stops rendering the moment anything moves.
        val clips = pair(storyboard, audioFiles).mapIndexed { index, clip ->
            val source = clip.file ?: return@mapIndexed clip
            val copy = File(assets, "vo-%02d.%s".format(index + 1, source.extension.ifEmpty { "mp3" }))
            source.copyTo(copy, overwrite = true)
            clip.copy(file = copy, href = "assets/${copy.name}")
        }

        // Static, not set by the boot script: the CLI reads the composition's size and
        // length when it compiles the page, which is before any of our JavaScript has run.
        // Left to the boot alone, every render came out at the CLI's own default of
        // 1080x1920 with this 1200x675 stage pinned to a corner of it.
        val compositionId = storyboard.audience.ifBlank { "reel" }
        val totalSec = clips.sumOf { it.durationSec }
        val mount = "<div id=\"mount\" data-composition-id=\"${escapeHtml(compositionId)}\" " +
            "data-width=\"$STAGE_W\" data-height=\"$STAGE_H\" " +
            "data-duration=\"${"%.3f".format(Locale.ROOT, totalSec)}\"></div>"

        val html = listOf(
            "<!doctype html>",
            "<html lang=\"en\">",
            "<head>",
            "<meta charset=\"UTF-8\"/>",
            "<title>${escapeHtml(projectName(storyboard))}, ${escapeHtml(audienceLabel(storyboard.audience))}</title>",
            "<link rel=\"stylesheet\" href=\"assets/reel.css\"/>",
            "<style>",
            COMPOSITION_CSS,
            "</style>",
            "</head>",
            "<body>",
            mount,
            audioTags(clips, inline = false),
            "<script src=\"assets/gsap.min.js\"></script>",
            "<script src=\"assets/icons.js\"></script>",
            "<script src=\"assets/scenes.js\"></script>",
            "<script src=\"assets/timeline.js\"></script>",
            "<script>",
            "window.__REEL_STORYBOARD__ = ${jsonLiteral(storyboard)};",
            COMPOSITION_BOOT,
            "</script>",
            "</body>",
            "</html>"
        ).joinToString("\n")

        File(dir, "index.html").writeText(html, StandardCharsets.UTF_8)
        logger.info("Nexus Reel wrote a HyperFrames composition to ${dir.absolutePath}")
        return dir
    }

    /** `<project>-<audience>-<yyyy-MM-dd>`, safe on every filesystem we care about. */
    fun baseName(storyboard: Storyboard): String {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "${slug(projectName(storyboard))}-${slug(storyboard.audience.ifBlank { "cut" })}-$date"
    }

    // ---------------------------------------------------------------- audio pairing

    /** One narration clip, already placed on the timeline. */
    private data class Clip(
        val index: Int,
        val startSec: Double,
        val durationSec: Double,
        val file: File?,
        val href: String? = null
    )

    private fun pair(storyboard: Storyboard, audioFiles: List<File>): List<Clip> {
        val usable = audioFiles.filter { it.isFile && it.length() > 0 }
        val narrated = storyboard.scenes.withIndex().filter { !it.value.narration.isNullOrBlank() }

        // A voice stage that emits one file per spoken line produces exactly as many
        // files as there are narrated scenes, and silent scenes leave no gap in its
        // list. Reading that case as narration order is what keeps audio on the right
        // picture instead of sliding forward by the number of silent scenes.
        val byNarration = usable.size == narrated.size && usable.size != storyboard.scenes.size
        val forScene = HashMap<Int, File>()
        val numbered = numbered(usable, storyboard.scenes.size)
        if (numbered != null) {
            forScene.putAll(numbered)
        } else if (byNarration) {
            narrated.forEachIndexed { position, indexed -> forScene[indexed.index] = usable[position] }
        } else {
            audioFiles.forEachIndexed { position, file ->
                if (file.isFile && file.length() > 0) forScene[position] = file
            }
        }

        var at = 0.0
        return storyboard.scenes.mapIndexed { index, scene ->
            val duration = durationOf(scene, storyboard)
            val clip = Clip(index, at, duration, forScene[index])
            at += duration
            clip
        }
    }

    /**
     * The scene each file was named for, when every one of them says so.
     *
     * The voice stage writes `scene-07.mp3` for the seventh scene, and that name is the
     * only thing that survives a scene whose recording failed. Counting positions instead
     * would slide every later line one scene forward from the gap, which is the whole film
     * out of sync with the picture from the middle onwards. Returns null unless every file
     * carries a number that lands on a real scene, so a folder of hand dropped clips still
     * falls through to the order based reading below.
     */
    private fun numbered(usable: List<File>, sceneCount: Int): Map<Int, File>? {
        if (usable.isEmpty()) return null
        val out = HashMap<Int, File>(usable.size)
        usable.forEach { file ->
            val spoken = SCENE_NUMBER.find(file.name.lowercase(Locale.ROOT))?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            // Named from one, indexed from zero.
            val index = spoken - 1
            if (index !in 0 until sceneCount) return null
            if (out.put(index, file) != null) return null
        }
        return out
    }

    /** Kept identical to `durationOf` in `runtime/timeline.js`, in seconds. */
    private fun durationOf(scene: Scene, storyboard: Storyboard): Double {
        if (scene.durationMs > MIN_SCENE_MS) return scene.durationMs / 1000.0
        val count = storyboard.scenes.size
        if (storyboard.totalMs > 0 && count > 0) return (storyboard.totalMs.toDouble() / count) / 1000.0
        return DEFAULT_SCENE_MS / 1000.0
    }

    private fun audioTags(clips: List<Clip>, inline: Boolean): String {
        val present = clips.filter { it.file != null }
        if (present.isEmpty()) return ""
        val tags = present.joinToString("\n") { clip ->
            val src = if (inline) dataUri(clip.file!!) else clip.href ?: "assets/${clip.file!!.name}"
            """<audio class="reel-vo" id="vo-${clip.index}" preload="auto" data-track-index="0" data-volume="1"
                 data-start="${"%.3f".format(Locale.ROOT, clip.startSec)}"
                 data-duration="${"%.3f".format(Locale.ROOT, clip.durationSec)}"
                 src="$src"></audio>"""
        }
        return "<div class=\"reel-vo-bay\" hidden>\n$tags\n</div>"
    }

    private fun dataUri(file: File): String {
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "wav" -> "audio/wav"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
    }

    // ---------------------------------------------------------------- page fragments

    private fun coverAndStage(storyboard: Storyboard, clips: List<Clip>): String {
        val seconds = Math.round(clips.sumOf { it.durationSec }).toInt()
        val length = "${seconds / 60}:${"%02d".format(seconds % 60)}"
        val audience = audienceLabel(storyboard.audience)
        val kind = if (storyboard.audience == "stakeholder") "For everyone" else "For developers"

        return """
            <main class="reel reel--export" id="reel">
              <header class="reel__head">
                <span class="reel__eyebrow">Nexus Reel</span>
                <strong class="reel__project" id="project-name">${escapeHtml(projectName(storyboard))}</strong>
              </header>

              <section class="picker" id="picker" aria-label="Cover">
                <p class="picker__lede">
                  ${escapeHtml(projectName(storyboard))}, generated from the codebase itself.
                </p>
                <div class="picker__cuts picker__cuts--one">
                  <button class="cut" id="cover-play" type="button">
                    <span class="cut__kind">${escapeHtml(kind)}</span>
                    <strong class="cut__name">${escapeHtml(audience.replaceFirstChar(Char::uppercase))}</strong>
                    <small class="cut__blurb">Click to play the film.</small>
                    <span class="cut__state" data-state="ready">$length</span>
                  </button>
                </div>
                <p class="picker__foot" id="status"></p>
              </section>

              <section class="stage" id="stage" hidden aria-label="Player">
                <div class="stage__frame" id="stage-frame"></div>

                <div class="transport" id="transport" hidden>
                  <div class="tp__row">
                    <button class="tp__play" id="tp-play" type="button" data-mode="play" aria-label="Play">
                      <span class="tp__glyph"></span>
                    </button>

                    <div class="tp__scrub">
                      <div class="tp__track">
                        <div class="tp__fill" id="tp-fill"></div>
                        <div class="tp__ticks" id="tp-ticks"></div>
                      </div>
                      <input class="tp__range" id="tp-range" type="range"
                             min="0" max="1000" step="1" value="0" aria-label="Seek through the reel"/>
                    </div>

                    <span class="tp__time" id="tp-time">0:00 / 0:00</span>
                    <button class="tp__chip" id="tp-sound" type="button" hidden
                            title="Your browser blocked audio until you interact with the page">Sound off</button>
                    <button class="tp__chip" id="tp-cc" type="button" aria-pressed="true"
                            title="Show or hide the narration captions">CC</button>
                    <button class="tp__chip" id="tp-back" type="button">Back</button>
                  </div>

                  <p class="tp__now">
                    <span class="tp__chapter" id="tp-chapter"></span>
                    <span class="tp__note" id="tp-note"></span>
                  </p>
                  <p class="tp__line" id="tp-line"></p>
                </div>
              </section>
            </main>
        """.trimIndent()
    }

    /**
     * The exported page is a whole window rather than a tool window panel, and it has
     * no second cut to choose between, so it gets the room the IDE cannot give it.
     */
    private val STANDALONE_CSS = """
        html, body { height: 100%; }
        .reel--export { height: 100vh; }
        .picker__cuts--one { max-width: 420px; }
        .reel-vo-bay { display: none; }
    """.trimIndent()

    private val COMPOSITION_CSS = """
        html, body { margin: 0; padding: 0; overflow: hidden; background: var(--surface-base); }
        #mount { position: relative; width: ${STAGE_W}px; height: ${STAGE_H}px; }
        .reel-vo-bay { position: absolute; width: 0; height: 0; overflow: hidden; }
    """.trimIndent()

    /**
     * Drives narration from the playhead rather than from its own clock.
     *
     * The timeline is the only clock in the player, and audio has to obey the same rule
     * or a scrub would leave a voice talking over the wrong picture. Browsers refuse to
     * start audio before a gesture, so a blocked start surfaces a chip instead of failing
     * silently, and the film keeps playing either way.
     */
    private val STANDALONE_BOOT = """
        (function () {
          'use strict';
          var film = window.__REEL_STORYBOARD__;
          var status = document.getElementById('status');
          var cover = document.getElementById('cover-play');
          var soundChip = document.getElementById('tp-sound');
          var clips = Array.prototype.slice.call(document.querySelectorAll('audio.reel-vo'));
          var blocked = false;

          function play() {
            try {
              window.NexusReel.play(film);
            } catch (error) {
              status.textContent = 'This film could not be played: ' + (error && error.message ? error.message : error);
            }
          }

          cover.addEventListener('click', play);

          function flagBlocked() {
            if (blocked || !clips.length) return;
            blocked = true;
            soundChip.hidden = false;
          }

          soundChip.addEventListener('click', function () {
            // Unlocking has to happen inside the gesture itself, so every clip is
            // started and stopped here and the follow loop can take them from now on.
            for (var i = 0; i < clips.length; i++) {
              var attempt = clips[i].play();
              if (attempt && attempt.then) {
                attempt.then(function () { this.pause(); this.currentTime = 0; }.bind(clips[i]), function () {});
              }
            }
            blocked = false;
            soundChip.hidden = true;
          });

          function follow() {
            var comp = window.NexusReel.composition();
            if (comp && clips.length) {
              var now = comp.tl.time();
              var running = window.NexusReel.isPlaying();
              for (var i = 0; i < clips.length; i++) {
                var clip = clips[i];
                var start = Number(clip.getAttribute('data-start')) || 0;
                var span = Number(clip.getAttribute('data-duration')) || 0;
                var want = now - start;
                var inside = running && want >= -0.05 && want < span;
                if (!inside) {
                  if (!clip.paused) clip.pause();
                  continue;
                }
                if (clip.paused) {
                  // Narration shorter than its scene has simply finished, and restarting
                  // it would stutter the same line until the scene ends.
                  var finished = clip.ended && Math.abs(clip.currentTime - want) < 0.6;
                  var past = isFinite(clip.duration) && want >= clip.duration - 0.05;
                  if (finished || past) continue;
                  clip.currentTime = Math.max(0, want);
                  var started = clip.play();
                  if (started && started.catch) started.catch(flagBlocked);
                } else if (Math.abs(clip.currentTime - want) > 0.4) {
                  clip.currentTime = Math.max(0, want);
                }
              }
            }
            window.requestAnimationFrame(follow);
          }

          window.requestAnimationFrame(follow);
          play();
        })();
    """.trimIndent()

    /**
     * Builds the film and then states its shape in the DOM, which is the only thing
     * the renderer reads. Run synchronously at the end of the body so the attributes
     * exist before the CLI ever looks for them.
     */
    private val COMPOSITION_BOOT = """
        (function () {
          'use strict';
          var size = window.ReelTimeline.SIZE;
          var mount = document.getElementById('mount');
          var comp = window.ReelTimeline.build(window.__REEL_STORYBOARD__, mount);
          var root = comp.root;

          // #mount already carries these in the served markup, which is what the CLI reads
          // at compile time. Restated here only so the built film and the static answer can
          // never disagree, and taken off the stage inside it so exactly one element in the
          // document claims to be the composition.
          mount.setAttribute('data-width', String(size.w));
          mount.setAttribute('data-height', String(size.h));
          mount.setAttribute('data-duration', comp.total.toFixed(3));
          root.removeAttribute('data-composition-id');

          var scenes = root.querySelectorAll('.scene');
          for (var i = 0; i < scenes.length && i < comp.chapters.length; i++) {
            scenes[i].classList.add('clip');
            scenes[i].setAttribute('data-start', comp.chapters[i].start.toFixed(3));
            scenes[i].setAttribute('data-duration', comp.chapters[i].duration.toFixed(3));
          }

          // Narration belongs to the composition, not to the page around it.
          var voice = document.querySelectorAll('audio.reel-vo');
          for (var v = 0; v < voice.length; v++) root.appendChild(voice[v]);

          // Paused at zero and left there: the renderer owns the playhead from here.
          comp.tl.pause(0);
          document.documentElement.setAttribute('data-reel-ready', 'true');
        })();
    """.trimIndent()

    // ---------------------------------------------------------------- plumbing

    private fun prepare(outDir: File): File {
        if (!outDir.isDirectory && !outDir.mkdirs()) {
            throw IOException("Nexus Reel could not create ${outDir.absolutePath}")
        }
        return outDir
    }

    private fun resourceText(path: String): String {
        val stream = CompositionWriter::class.java.classLoader.getResourceAsStream("$RESOURCE_ROOT/$path")
            ?: throw IOException("Nexus Reel is missing its own resource $path")
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private fun copyResource(path: String, target: File) {
        val stream = CompositionWriter::class.java.classLoader.getResourceAsStream("$RESOURCE_ROOT/$path")
            ?: throw IOException("Nexus Reel is missing its own resource $path")
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun projectName(storyboard: Storyboard): String =
        storyboard.theme.projectName.ifBlank { "This project" }

    private fun audienceLabel(audience: String): String =
        if (audience.isBlank()) "reel" else "$audience cut"

    private fun slug(value: String): String =
        value.trim().lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .ifEmpty { "reel" }

    /**
     * Serialises the storyboard as a JavaScript literal.
     *
     * `</` is broken up because a narration line containing `</script>` would otherwise
     * end the script block early, and the two line separators are escaped because they
     * are whitespace to JSON and a statement terminator to JavaScript.
     */
    private fun jsonLiteral(storyboard: Storyboard): String =
        gson.toJson(storyboard)
            .replace("</", "<\\/")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

    /** The same guard for code we inline verbatim, where `</script` can only sit inside a string. */
    private fun jsSafe(source: String): String = source.replace("</script", "<\\/script")

    private fun cssSafe(source: String): String = source.replace("</style", "<\\/style")

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
