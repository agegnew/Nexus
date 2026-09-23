package com.example.trust

import com.intellij.openapi.project.Project

/**
 * The Trust verdict, laid out and ready to draw, for the page that draws it.
 *
 * Trust used to be a Swing tab of its own, sitting beside the one page that holds every other
 * view. That put one view on a different shelf from the other four for a reason that was
 * true of the implementation and meaningless to the person using it. It is a panel in the row
 * now, which means it is a web page, which means this: the model, and the geometry, as JSON.
 *
 * ### Why the rectangles are computed here and not in the page
 *
 * The obvious alternative is to send the tree and squarify it in JavaScript. That would save a
 * round trip on resize and cost a second implementation of [Squarify], which is a tested piece
 * of published geometry that already decides where every box goes. Two of them is two answers
 * to "where is this file", and the first time they disagree the picture is lying about
 * something. So the layout stays in Kotlin, the page asks for the size it has, and the reply is
 * rectangles. It is a loopback request measured in single milliseconds.
 */
object TrustPayload {

    /** A folder's box keeps a strip across the top for its name. */
    private const val HEADER = 15.0

    /** Below this a box has no room for a name or a click, so the caption names it instead. */
    private const val MIN_VISIBLE = 14.0

    /**
     * Everything the page needs for one render.
     *
     * [width] and [height] are the pixel box the page has to fill. They arrive from the page
     * because only the page knows them, and a layout computed against a guess would be redrawn
     * at the wrong scale the moment it arrived.
     *
     * **Not for the event thread.** [TrustService.allKnownDetailed] searches the project for
     * every file that never ran, which is the whole point of it and is also why it must not be
     * called where it can freeze the IDE. The HTTP handler threads this runs on are not the EDT.
     */
    fun of(
        project: Project,
        width: Double,
        height: Double,
        deadOnly: Boolean = false,
    ): Map<String, Any?> {
        val service = TrustService.getInstance(project)
        val known = service.allKnownDetailed()
        val command = TrustRunner.commandFor(project)

        val base = mutableMapOf<String, Any?>(
            "ready" to known.isNotEmpty(),
            "placeholder" to service.isPlaceholder(),
            "source" to service.describeSource(),
            "paint" to service.enabled,
            "command" to command?.let { mapOf("text" to it.command, "origin" to it.origin) },
        )

        if (known.isEmpty()) return base

        val all = known.values
        val visible = if (deadOnly) all.filter { it.isDead } else all.toList()
        // Named against everything known, never against the subset being drawn. A folder called
        // app/tools in one mode and tools in the next is a name nobody trusts, which is why
        // TrustLayers takes the basis separately.
        val layers = TrustLayers.of(visible, namingBasis = all)

        base["totals"] = totals(all, visible, layers)
        // The scale lives in TrustColors and is sent already resolved. Retyping it in CSS is
        // how the map's bar and this picture would end up measuring the same thing in two
        // different reds, and the eye stops believing either of them the moment they differ.
        base["scale"] = listOf(0, 25, 50, 75, 100).map { mapOf("percent" to it, "colour" to hex(it)) }
        base["layers"] = layers.map {
            mapOf(
                "name" to it.name,
                "folder" to it.folder,
                "lines" to it.totalLines,
                "neverRun" to it.unprovenLines,
                "percent" to it.percentUnproven(),
                "colour" to hex(it.percentUnproven()),
                "deadFiles" to it.deadFiles,
            )
        }

        base["files"] = visible.sortedByDescending { it.unprovenLines }.map {
            mapOf(
                "path" to it.path,
                "name" to it.fileName,
                "folder" to it.folder,
                "lines" to it.totalLines,
                "neverRun" to it.unprovenLines,
                "percent" to it.percentUnproven(),
                "dead" to it.isDead,
                "changed" to it.changedSinceRun,
            )
        }

        val geometry = layout(layers, width, height)
        base["cells"] = geometry.cells
        base["groups"] = geometry.groups
        base["hidden"] = geometry.hidden
        return base
    }

    /**
     * The counts the headline is built from.
     *
     * [visible] is what is being drawn and [all] is everything known, and they are different in
     * dead-only mode. The headline needs both: the size of the pile it is showing, and the
     * counts that tie it back to the whole project, because "40 files" above a list of 34 with
     * nothing connecting them reads as a bug.
     */
    private fun totals(
        all: Collection<FileTrust>,
        visible: Collection<FileTrust>,
        layers: List<LayerSummary>,
    ): Map<String, Any?> {
        val lines = visible.sumOf { it.totalLines }
        val neverRun = visible.sumOf { it.unprovenLines }
        return mapOf(
            "lines" to lines,
            "neverRun" to neverRun,
            "percent" to if (lines > 0) neverRun * 100 / lines else 0,
            "colour" to hex(if (lines > 0) neverRun * 100 / lines else 0),
            "filesKnown" to all.size,
            "filesAffected" to all.count { !it.isClean },
            "deadFiles" to all.count { it.isDead },
            "folders" to layers.size,
        )
    }

    private class Geometry(
        val groups: List<Map<String, Any?>>,
        val cells: List<Map<String, Any?>>,
        val hidden: List<Map<String, Any?>>,
    )

    /**
     * Folders squarified into the whole box, then each folder's files squarified inside it.
     *
     * The one pixel inset and the header strip are kept from the Swing treemap this replaces,
     * because a person who saw that picture yesterday should recognise this one today.
     */
    private fun layout(layers: List<LayerSummary>, width: Double, height: Double): Geometry {
        if (width <= 0 || height <= 0) return Geometry(emptyList(), emptyList(), emptyList())

        val bounds = TreemapRect(0.0, 0.0, width, height)
        val groups = Squarify.layout(layers, { it.totalLines.toDouble() }, bounds)

        val drawn = groups.filter { it.rect.w >= MIN_VISIBLE && it.rect.h >= MIN_VISIBLE }
        val drawnFolders = drawn.map { it.value.folder }.toSet()

        val cells = drawn.flatMap { group ->
            val inner = TreemapRect(
                group.rect.x + 1,
                group.rect.y + HEADER,
                group.rect.w - 2,
                (group.rect.h - HEADER - 1).coerceAtLeast(1.0),
            )
            Squarify.layout(group.value.files, { it.totalLines.toDouble() }, inner)
                .filter { it.rect.w > 0 && it.rect.h > 0 }
                .map { cell(it.value, it.rect, group.value) }
        }

        return Geometry(
            groups = drawn.map { group ->
                mapOf(
                    "name" to group.value.name,
                    "folder" to group.value.folder,
                    "percent" to group.value.percentUnproven(),
                    "colour" to hex(group.value.percentUnproven()),
                ) + rect(group.rect)
            },
            cells = cells,
            hidden = layers.filter { it.folder !in drawnFolders }.map {
                mapOf("name" to it.name, "folder" to it.folder, "lines" to it.totalLines)
            },
        )
    }

    private fun cell(file: FileTrust, box: TreemapRect, layer: LayerSummary): Map<String, Any?> =
        mapOf(
            "path" to file.path,
            "name" to file.fileName,
            "folder" to layer.folder,
            "lines" to file.totalLines,
            "neverRun" to file.unprovenLines,
            "percent" to file.percentUnproven(),
            "colour" to hex(file.percentUnproven()),
            "dead" to file.isDead,
            "changed" to file.changedSinceRun,
        ) + rect(box)

    private fun hex(percent: Int): String {
        val colour = TrustColors.ramp(percent.toDouble())
        return String.format("#%02X%02X%02X", colour.red, colour.green, colour.blue)
    }

    /** Doubles, because the rounding belongs at the draw call, exactly as in [Squarify]. */
    private fun rect(box: TreemapRect): Map<String, Any?> = mapOf(
        "x" to box.x,
        "y" to box.y,
        "w" to box.w,
        "h" to box.h,
    )
}
