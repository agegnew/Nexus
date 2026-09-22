package com.example.yasinreel.deck

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The finished deck, in the shape the tab's preview reads.
 *
 * Separate from [DeckToolWindowFactory] so it can be exercised without an IDE around it:
 * the preview is the one part of this feature that cannot be checked by opening the
 * .pptx, so being able to dump a payload and load it in a plain browser is the only way
 * to look at it at all.
 *
 * Shapes are written by hand rather than handed to Gson because [Shape] is a sealed
 * hierarchy and the page has to be told which of the three each one is. A tag written
 * here is one line; teaching a serialiser to write it is a type adapter that would then
 * have to be kept in step with the model.
 */
object DeckPreviewJson {

    fun of(deck: Deck, art: List<SlideArt>, fileName: String, slides: Int, byAi: Boolean, cacheHit: Boolean, issues: List<String>): JsonObject {
        val out = JsonArray()
        art.forEach { slide ->
            val shapes = JsonArray()
            slide.shapes.forEach { shapes.add(shapeOf(it)) }
            out.add(JsonObject().apply {
                addProperty("title", slide.title)
                addProperty("notes", slide.notes)
                add("shapes", shapes)
            })
        }
        return JsonObject().apply {
            addProperty("audience", deck.audience)
            addProperty("title", deck.title)
            addProperty("fileName", fileName)
            addProperty("slides", slides)
            addProperty("byAi", byAi)
            addProperty("cacheHit", cacheHit)
            add("issues", JsonArray().apply { issues.forEach { add(it) } })
            add("slides_art", out)
        }
    }

    fun shapeOf(shape: Shape): JsonObject = JsonObject().apply {
        addProperty("x", shape.x)
        addProperty("y", shape.y)
        addProperty("w", shape.w)
        addProperty("h", shape.h)
        when (shape) {
            is Box -> {
                addProperty("kind", "box")
                addProperty("fill", shape.fill)
                addProperty("roundPct", shape.roundPct)
                shape.stroke?.let { addProperty("stroke", it) }
                addProperty("strokeWidth", shape.strokeWidth)
            }
            is Label -> {
                addProperty("kind", "text")
                add("lines", JsonArray().apply { shape.lines.forEach { add(it) } })
                addProperty("sizePx", shape.sizePx)
                addProperty("color", shape.color)
                addProperty("bold", shape.bold)
                addProperty("align", shape.align.name)
                addProperty("anchor", shape.anchor.name)
                addProperty("spacing", shape.spacing)
                addProperty("linePct", shape.linePct)
                addProperty("gapPx", shape.gapPx)
                addProperty("mono", shape.mono)
            }
            is Pic -> {
                addProperty("kind", "pic")
                addProperty("icon", shape.icon)
            }
        }
    }
}
