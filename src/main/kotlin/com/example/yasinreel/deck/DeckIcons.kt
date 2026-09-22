package com.example.yasinreel.deck

import com.intellij.openapi.diagnostic.Logger

/**
 * The sixteen icons, and the rule for choosing one.
 *
 * Deliberately the same artwork and the same keywords as the film's
 * `resources/yasin-reel/runtime/icons.js`, so a deck and a video generated from one
 * project put the same face on the same idea. The PNGs in `resources/yasin-deck/icons`
 * were rasterised from the data URIs in that very file rather than fetched again, so
 * they cannot be a different set that merely looks similar.
 *
 * The keyword table below is a port, which means it can drift. `DeckIconsTest` reads
 * `icons.js` out of the jar and fails if the two ever disagree, because a comment asking
 * people to keep two lists in step is not a mechanism.
 */
object DeckIcons {

    private val logger = Logger.getInstance(DeckIcons::class.java)

    /**
     * Order matters only for the deterministic fallback, which walks this list. It is
     * arranged so two neighbouring slides never land on icons that read alike.
     */
    val NAMES = listOf(
        "rocket", "search", "chart", "toolbox", "laptop", "bulb", "package", "plug",
        "files", "sparkles", "lock", "check", "compass", "palette", "bolt", "target"
    )

    /** Every word here is matched against text the harvester or the director wrote. */
    val KEYWORDS: Map<String, List<String>> = mapOf(
        "rocket" to listOf("launch", "ship", "shipped", "deploy", "release", "start", "begin", "startup", "go live", "publish", "fast", "takeoff", "accelerat"),
        "search" to listOf("search", "find", "analys", "analyz", "scan", "discover", "detect", "inspect", "read", "understand", "explore", "look", "index", "parse", "trace", "insight"),
        "chart" to listOf("metric", "stat", "number", "count", "data", "measure", "scale", "growth", "report", "volume", "size", "total", "dashboard", "analytic", "benchmark"),
        "toolbox" to listOf("build", "engine", "tool", "system", "process", "pipeline", "compile", "gradle", "maven", "assembl", "machinery", "worker", "job", "task", "run"),
        "laptop" to listOf("app", "screen", "ui", "interface", "editor", "ide", "view", "front", "desktop", "window", "panel", "browser", "display", "client", "web"),
        "bulb" to listOf("idea", "insight", "meaning", "explain", "learn", "clarity", "why", "understand", "knowledge", "concept", "think", "reason", "story", "narrat"),
        "package" to listOf("depend", "module", "librar", "bundle", "plugin", "package", "import", "artifact", "jar", "npm", "vendor", "component", "extension"),
        "plug" to listOf("connect", "integrat", "api", "bridge", "wire", "hook", "link", "endpoint", "request", "call", "route", "channel", "protocol", "between", "cross"),
        "files" to listOf("file", "folder", "code", "source", "repositor", "document", "structure", "director", "path", "tree", "project", "codebase", "script", "module tree"),
        "sparkles" to listOf("ai", "model", "generat", "smart", "language", "magic", "llm", "intelligen", "auto", "suggest", "predict", "gpt", "narration", "voice", "speech"),
        "lock" to listOf("secur", "safe", "privat", "auth", "secret", "key", "token", "credential", "protect", "permission", "scrub", "redact", "encrypt", "trust"),
        "check" to listOf("done", "verif", "test", "quality", "valid", "pass", "correct", "confirm", "check", "proof", "accurate", "review", "guarantee", "ensure", "honest"),
        "compass" to listOf("map", "navigat", "guide", "tour", "orient", "overview", "direction", "onboard", "where", "journey", "path", "architect", "layout", "blueprint"),
        "palette" to listOf("design", "visual", "colour", "color", "theme", "style", "render", "draw", "paint", "brand", "look", "animat", "film", "video", "frame", "scene"),
        "bolt" to listOf("fast", "speed", "perform", "instant", "live", "real time", "real-time", "power", "quick", "latency", "second", "efficien", "respons", "cache"),
        "target" to listOf("goal", "focus", "precis", "accurate", "aim", "outcome", "result", "objective", "impact", "value", "benefit", "solve", "problem", "need", "audience")
    )

    /**
     * Scores every icon against a blob of text and returns the best name.
     *
     * A longer keyword beats a shorter one so "endpoint" is a connection rather than a
     * point, and a word in [primary] beats the same word in [secondary] because the
     * title is what the icon is standing next to.
     *
     * [taken] is per slide, so a grid of six cards comes out wearing six different faces
     * rather than the same one repeated, which is what a keyword match across similar
     * titles would otherwise produce.
     */
    fun pick(primary: String?, secondary: String? = null, index: Int = 0, taken: MutableSet<String>? = null): String {
        val a = primary.orEmpty().lowercase()
        val b = secondary.orEmpty().lowercase()
        var best: String? = null
        var bestScore = 0
        for (name in NAMES) {
            if (taken != null && name in taken) continue
            var score = 0
            for (word in KEYWORDS.getValue(name)) {
                if (a.contains(word)) score += word.length * 3
                else if (b.contains(word)) score += word.length
            }
            if (score > bestScore) {
                bestScore = score
                best = name
            }
        }
        if (best != null) {
            taken?.add(best)
            return best
        }
        // Nothing matched. Walk the order from the caller's position and take the first
        // free slot, which is deterministic and so identical on every machine.
        val start = Math.floorMod(index, NAMES.size)
        for (k in NAMES.indices) {
            val candidate = NAMES[(start + k) % NAMES.size]
            if (taken == null || candidate !in taken) {
                taken?.add(candidate)
                return candidate
            }
        }
        return NAMES[start]
    }

    /** Every icon's PNG bytes, by name. Missing files are skipped rather than fatal. */
    fun load(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (name in NAMES) {
            val stream = javaClass.classLoader.getResourceAsStream("yasin-deck/icons/$name.png")
            if (stream == null) {
                logger.warn("Nexus Deck could not find the icon $name, slides will simply omit it")
                continue
            }
            stream.use { out[name] = it.readBytes() }
        }
        return out
    }
}
