package com.example.yasinreel.model

/**
 * Stage 2 output: what this codebase IS, in human terms.
 *
 * Built once per project and cached, then handed to both directors, which is why
 * the technical cut and the stakeholder cut can never contradict each other.
 *
 * Every `evidence` / `evidenceRef` is an id that must resolve against the
 * [Evidence] it was built from. StoryboardValidator enforces that.
 */
data class ProductModel(
    val productName: String,
    val tagline: String,
    val problemStatement: String,
    val targetUser: String,
    val confidence: String,
    val capabilities: List<Capability>,
    val architecture: Architecture,
    val techStack: List<TechItem>,
    val keyFlows: List<KeyFlow>,
    val scaleFacts: List<ScaleFact>,
    val gaps: List<String>
)

data class Capability(
    val id: String,
    val userFacingName: String,
    val userBenefit: String,
    val technicalSummary: String,
    val evidence: List<String>,
    val confidence: String
)

data class Architecture(val layers: List<Layer>, val dataStores: List<String>)

data class Layer(val name: String, val components: List<Component>)

data class Component(val name: String, val tech: String?)

data class TechItem(val name: String, val category: String, val why: String)

data class KeyFlow(val name: String, val steps: List<FlowStep>)

data class FlowStep(val actor: String, val action: String, val evidenceRef: String?)

data class ScaleFact(val label: String, val value: String, val evidenceRef: String?)
