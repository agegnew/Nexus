package com.example.yasinreel.model

import com.google.gson.JsonObject

/**
 * Stage 3 output: the shooting script, and the contract between Kotlin and the
 * player runtime in resources/yasin-reel/.
 *
 * The AI never writes HTML, CSS or animation. It picks a [Scene.template] from
 * [SceneTemplate.ALL] and fills typed slots. That constraint is what makes the
 * output reliably good-looking: an unknown template is impossible, so a broken
 * scene is impossible.
 *
 * If this shape changes, change resources/yasin-reel/runtime/ in the same commit.
 */
data class Storyboard(
    val audience: String,
    val totalMs: Int,
    val theme: Theme,
    val scenes: List<Scene>
)

data class Theme(val colors: List<String>, val projectName: String)

data class Scene(
    val template: String,
    val durationMs: Int,
    val slots: JsonObject,
    val narration: String?,
    val sourceRefs: List<SourceRef>
)

/** Drives click-to-source: clicking this on screen opens [file] at [line]. */
data class SourceRef(val file: String, val line: Int?)

object SceneTemplate {
    const val TITLE = "title"
    const val BIG_STATEMENT = "big-statement"
    const val STAT_GRID = "stat-grid"
    const val CAPABILITY_CARDS = "capability-cards"
    const val ARCH_LAYERS = "arch-layers"
    const val FLOW_TRACE = "flow-trace"
    const val JOURNEY = "journey"

    /**
     * The product's own interface, redrawn from what its source declares.
     *
     * The only scene whose content is not a sentence about the product but the product,
     * and therefore the only one that cannot be filled by a director: its slots come
     * straight from the harvest. A director that tried to write them would be writing
     * someone else's navigation for them.
     */
    const val PRODUCT_UI = "product-ui"
    const val OUTRO = "outro"

    val ALL = listOf(
        TITLE, BIG_STATEMENT, STAT_GRID, CAPABILITY_CARDS,
        ARCH_LAYERS, FLOW_TRACE, JOURNEY, PRODUCT_UI, OUTRO
    )

    /** Scenes that assume the viewer reads code, so never used in a stakeholder cut. */
    val TECHNICAL_ONLY = setOf(ARCH_LAYERS, FLOW_TRACE)

    /**
     * Scenes that assume the viewer does not read code, so never used in a technical cut.
     *
     * [PRODUCT_UI] is here because a developer already has the app open on the other
     * monitor. Showing an engineer a picture of a sidebar they wrote is a slow way to
     * say nothing; showing it to the person who paid for the sidebar is the point.
     */
    val STAKEHOLDER_ONLY = setOf(JOURNEY, PRODUCT_UI)
}

object Audience {
    const val TECHNICAL = "technical"
    const val STAKEHOLDER = "stakeholder"
}
