package com.example.yasinreel.llm

import com.example.yasinreel.model.Evidence
import com.example.yasinreel.model.ProductModel
import com.example.yasinreel.model.Storyboard

/**
 * The two AI stages, behind one interface.
 *
 * Understanding runs once per project and is cached; directing runs once per audience.
 * Keeping them on the same interface is what lets the pipeline swap the whole provider
 * (a local model, a different vendor, an agent framework) without touching a stage.
 */
interface NarrativeEngine {

    /**
     * Stage 2. Turns harvested facts into meaning, with [tools] available so the model can
     * go and read more of the codebase before it commits. [progress] is called once per
     * round so a 60 second job can say what it is doing.
     */
    fun understand(evidence: Evidence, tools: EvidenceTools, progress: (String) -> Unit): ProductModel

    /**
     * Stage 3. Picks scene templates and fills their slots for one [audience], aiming at
     * [targetMs] of runtime. [evidence] comes along for provenance: it is what turns a
     * scene into something the viewer can click through to the real file.
     */
    fun direct(model: ProductModel, evidence: Evidence, audience: String, targetMs: Int): Storyboard
}
