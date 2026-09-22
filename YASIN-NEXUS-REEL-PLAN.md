# Nexus Reel: codebase to product video, inside the IDE

> Branch to create: `yasin/full-codebase-video` (from `main`). **Claude makes no commits.** Yasin commits.
> Status: PLAN ONLY. Nothing is built until go-ahead.
>
> Decisions locked: **OpenAI direct** for the AI layer · demo on a **cloned open-source full-stack
> app** · **narration + music bed in scope** · name **Nexus Reel**, tool window tab **Reel**.

---

## 1. Context

**The hackathon ask.** JetBrains wants an IntelliJ-based plugin or app that makes developers' lives
easier, that is genuinely an AI solution, and that goes beyond "wrap a prompt around a common IDE
action". Judging order: (1) does it actually run end-to-end, (2) UX fit into the existing workflow,
(3) innovation, (4) technical depth (real codebase context, multi-step/agentic logic, JetBrains
integration), (5) presentation. 15 hours remain.

**The team.** Nexus is a "Visuals" play: several ways of seeing a codebase (blueprints, graphs,
architecture maps). Today the repo is an IntelliJ plugin (`Code Visualizer`) whose Kotlin side
regex-scans a project for frontend HTTP calls and backend endpoints, matches them, and pushes a
`ProjectGraph` JSON into a React app rendered in a JCEF browser in a tool window.

**Yasin's slice: video.** Not a blueprint, not a graph. A *product video* generated from the
codebase, in two cuts:

- **Technical cut** for developers: architecture, real traced requests, real file paths.
- **Stakeholder cut** for non-technical people: what the product does for a human being, in plain
  language, zero jargon.

**The problem this actually solves.** Two daily, universally-felt pains with one root cause:

1. *Onboarding.* A new engineer faces 40,000 lines with no map. The README is stale, the architecture
   doc never existed. The only current fix is a senior engineer's time.
2. *Explaining engineering upward.* Engineers hate making slides. Stakeholders never see what was
   actually built, only a burndown chart. That gap is where trust dies.

Root cause: **the codebase is the only honest source of truth about the product, and nobody can read
it except the people who wrote it.**

**Intended outcome.** One button in the IDE produces two narrated, animated videos grounded in the
real code, where every claim traces to a file and a line, and where a developer can click any file
path *mid-playback* and land in that file in the editor.

---

## 2. What we build

**Nexus Reel.** Tool window tab: **Reel**.

**One line:** *Nexus Reel turns your codebase into two product videos: one for the people who build
it, one for the people who fund it.*

### The three wow moments, in the order a judge sees them

1. **One codebase, two audiences, one click each.** Two buttons: *Technical cut* and *Stakeholder
   cut*. The first click does the expensive work, harvesting the codebase and building one product
   model. The second click reuses that model and only re-directs it, so it lands in roughly a third
   of the time. Technical: *"Analysis runs on a background thread inside the IDE, then crosses into an
   embedded browser as JSON. The two halves share nothing but that one contract."* Stakeholder, from
   **the same product model**: *"Open a codebase you have never seen before, and Nexus draws you a map
   of how it fits together."* Generating the second one live, and watching it arrive fast, **is** the demo: it proves
   the understanding was shared rather than done twice.

2. **Click-to-source from inside the video.** While the technical cut plays, every file path on
   screen is live DOM. Click it, the IDE opens that file at that line. No video tool can do this; it
   is only possible because the film lives in the IDE. The video stops being an artifact and becomes
   a navigation surface: **the video is the onboarding tour.**

3. **It is in your product's colours, and it cannot lie.** The palette is extracted from the target
   project's own CSS custom properties / Tailwind config, so the film looks like *their* product, not
   like our template. And a *Provenance* toggle overlays the exact file, route or dependency each
   statement came from. Nothing is asserted that the harvester did not find. This inherits the team's
   own charter in `.impeccable.md`, *"Never invent a relationship; unmatched and unsupported results
   must be visible"*, extended to **never invent a capability**.

### Deliverables

| Output | Format | Purpose |
|---|---|---|
| Technical cut | plays in the tool window | onboard new hires, share with the team |
| Stakeholder cut | plays in the tool window | the conversation with the business |
| Standalone `.html` | always exportable, zero dependencies | double-click, plays anywhere, send to anyone |
| `.mp4` | when Node 22 + ffmpeg are present | Slack, email, a board deck |
| `reel.json` | storyboard + evidence, on disk | reproducible, diffable, hand-editable |

Length is a user choice, not a fixed 20s: **Teaser 30s / Standard 90s / Deep 3 min.** Those
land almost exactly on HyperFrames' own first-party `product-launch-video` route guidance (30 to
90s sweet spot, ~3 min hard cap), which is a useful independent check on the numbers. In-IDE
playback is 16:9; since destination drives aspect, a 1:1 or 9:16 export of the stakeholder cut
for LinkedIn or Shorts is a stretch goal.

### Where everything is saved

**The film plays inside the Reel tab.** That is the normal way you watch it. Nothing is written out
as a video unless you press Export, so generating a reel does not litter your repo.

| What | Where | In git? |
|---|---|---|
| Working files: evidence, product model, storyboard, narration mp3s, the composition folder | `<project>/.idea/yasin-reel/` | no, `.idea` is already gitignored |
| Exports: the `.mp4` and the standalone `.html` | `<project>/yasin-reel/` | no, added to `.gitignore` |

Exports are named `<project>-technical-<date>.mp4` and `<project>-stakeholder-<date>.html`. When one
finishes, a notification offers *Play*, *Reveal in Finder* and *Copy path*. **Export as...** opens a
normal save dialog for anywhere else, such as the Desktop or a shared drive.

Working files live under `.idea/` on purpose: they are a cache, they are already ignored by the
team's `.gitignore`, and keeping them per-project means two different repos never collide.

---

## 3. How a judge experiences it (demo script, ~3 min)

1. Open **Nexus itself**. Our own repo, so every file path on screen is one a judge can verify live,
   and we are not hiding behind a codebase we picked because it flattered the tool.
2. Open the **Reel** tab. Two buttons, neither built yet. Click **Technical cut**.
3. Real progress: `Harvesting 1,240 files` → `Understanding the product` → `Directing: technical` →
   `Voicing` → `Composing`.
4. ~45s later it plays. Narrated, over a music bed, in the project's own brand colours. Real paths
   on screen. **Click a path mid-playback, the editor jumps to that line.** Pause there and let it
   land.
5. Now click **Stakeholder cut**. It skips straight to `Directing: stakeholder` and arrives in ~20s,
   because the product model is already built. Say it out loud: *"It didn't read the code again. It
   understood it once, and this is that same understanding told to a different room."*
6. Play it. Same codebase, unrecognisably different film. No jargon, a real user journey.
7. **Export**, the `.mp4` lands with a notification. Drag it into Slack.
8. Close: *"Two audiences. One codebase. One click each."*

---

## 4. Architecture: a six-stage pipeline

```
 ┌─ IntelliJ (JVM) ──────────────────────────────────────────────────────────────┐
 │                                                                                │
 │  1. HARVEST     EvidenceHarvester.kt            (no AI, facts only)            │
 │     VFS / ProjectFileIndex -> evidence.json  + brand palette                   │
 │     reuses ProjectFlowAnalyzer.analyze() for call -> endpoint -> handler traces │
 │     SECRET SCRUB runs here and nothing unscrubbed leaves this stage             │
 │                          |                                                     │
 │  2. UNDERSTAND  NarrativeEngine    (LLM call 1, tool-calling loop)             │
 │     evidence.json -> productModel.json                                         │
 │     the model CALLS BACK for detail: readFile / listDir / grep / readRoutes    │
 │                          |                                                     │
 │  3. DIRECT      (LLM call 2, run twice: two system prompts, one model)         │
 │     productModel + audience + scene catalogue -> storyboard.json  x2           │
 │                          |                                                     │
 │  4. VALIDATE    StoryboardValidator.kt          (no AI, deterministic critic)  │
 │     jargon ban / refs resolve / reading-time floor / duration fit              │
 │     on failure -> one targeted repair round-trip                               │
 │                          |                                                     │
 │  5. VOICE       TtsClient.kt -> one mp3 per scene, cached by sha256            │
 │                          |                                                     │
 │  6. COMPOSE     storyboard.json -> reel runtime (HTML + CSS + GSAP)            │
 │     plays live in JCEF; exports standalone HTML; optional MP4 via HyperFrames  │
 └────────────────────────────────────────────────────────────────────────────────┘
```

**The cache boundary sits between stage 2 and stage 3.** Stages 1 and 2 are per-project and run once,
their result keyed by project path plus a content hash and stored in `.idea/nexus-reel/`. Stages 3 to
6 are per-audience and run on each button click. So the first click pays for harvest and
understanding; the second click, whichever audience it is, starts at stage 3. If the code changed
since the cached run, the hash misses and stage 1 re-runs, with a visible *Re-analyze* affordance for
forcing it.

Stages 1 to 5 are Kotlin. Stage 6 is a self-contained web app **bundled inside the plugin jar** and
served from `127.0.0.1`, so unlike the existing `visualizer-ui` it needs **no `npm run dev`**.

### Why this is agentic, not a prompt wrapper

The brief singles out "multi-step or agentic logic where it's warranted". Ours is warranted and
demonstrable on stage:

- **Stage 2 is a tool-calling loop, not one shot.** The model gets a compact evidence *index*, then
  requests what it needs: `readFile(path, from, to)`, `listDir(path)`, `grep(pattern)`,
  `readRoutes()`. Typical run: 3 to 6 tool calls. Real codebase context pulled on demand, not a
  pasted snippet. We log the tool calls and can show the transcript in the demo.
- **Stage 4 is a deterministic critic that sends the model back to fix itself.** A self-correcting
  loop with a ground-truth referee.
- **Stage 3 runs one product model through two directors.** The expensive understanding happens once
  and is cached; audience adaptation is cheap and provably consistent between the cuts. Because the
  two cuts are now generated by separate clicks, this is *observable on stage*: the second film
  arrives in about a third of the time, which is the architecture showing itself.

---

## 5. Where exactly the LLM sits, and what it does

The split is the whole design: **static analysis gives facts; the LLM gives meaning.** Harvesting can
tell you there are 14 routes under `/api/cart`. Only a language model can tell you those routes are
one capability called "Shopping basket", and that a stakeholder cares because it is how money gets
taken. That is the gap the LLM exists to close, and nothing else closes it.

### Stage 2, Understanding (the core IP)

Input: `evidence.json` (capped ~50 KB) plus the four tools. Output: strict JSON via
`response_format: json_schema`.

```jsonc
{
  "productName": "...", "tagline": "...", "problemStatement": "...", "targetUser": "...",
  "confidence": "high|medium|low",
  "capabilities": [{
    "id": "cap-checkout",
    "userFacingName": "Check out and pay",
    "userBenefit": "Buyers pay by card without leaving the store",
    "technicalSummary": "POST /api/checkout -> CheckoutService.create() -> Stripe PaymentIntent",
    "evidence": ["route-12", "dep:stripe", "file:api/checkout.py"],
    "confidence": "high"
  }],
  "architecture": { "layers": [...], "dataStores": [...] },
  "techStack": [...], "integrations": [...],
  "keyFlows": [{ "name": "...", "steps": [{ "actor": "...", "action": "...", "evidenceRef": "..." }] }],
  "scaleFacts": [{ "label": "Endpoints", "value": "14", "evidenceRef": "stats.backendEndpoints" }],
  "gaps": ["no tests found", "12 API calls have no matching endpoint"]
}
```

System-prompt rules: every claim cites an evidence id; unsupported claims are omitted or marked low
confidence; never invent features, metrics or integrations; report gaps rather than hide them.

### Stage 3, Direction (two directors, one model)

Output is constrained to a **fixed scene-template enum with typed slots**, so the LLM never writes
HTML or CSS. It chooses templates and fills slots. *This single decision is what makes the output
reliably good-looking in a 15-hour build*, and it is the difference between a demo that always works
and one that sometimes renders garbage.

```jsonc
{ "audience": "technical", "totalMs": 90000, "voice": "crisp",
  "scenes": [{
    "template": "flow-trace", "durationMs": 9000,
    "slots": { "from": "Cart.tsx:42", "via": "POST /api/checkout", "to": "checkout.py:18" },
    "narration": "A click in the cart becomes one POST to the checkout route, handled here.",
    "sourceRefs": [{ "file": "web/src/Cart.tsx", "line": 42 }]
  }] }
```

**Both directors inherit one rule, borrowed from `brag`'s preference ladder:** show the real thing.
Recreate a real screen or a real traced flow first; animate the real concept second; fall back to
text last. *Never fill a scene with abstract patterns, colour washes or generic motion graphics.*

**Technical director.** The subject is **the architecture**, not the file system. Talk about how the
system is put together: what the layers are, how data moves between them, what the stack is and why,
where the boundaries sit, where the complexity concentrates. Do **not** narrate file names and line
numbers. They appear on screen as small provenance labels and stay clickable, but "handled at
checkout.py line 18" is not worth saying out loud, whereas "the two halves share nothing but one JSON
contract" is. Prefer one real traced chain over three abstract descriptions. Surface gaps honestly.

**Stakeholder director.** The viewer will never read code and is deciding whether to fund this.
Hard-banned vocabulary, machine-enforced in Stage 4: `endpoint, API, backend, frontend, framework,
repository, component, deploy, schema, database, function, class, library, SDK, latency, refactor,
codebase, server`. Required shape: a human situation → the promise in one sentence → 3 to 5 things a
person can now do → proof of substance → what is next. The centerpiece must be a **real user flow**
from `keyFlows`, not a wall of statistics.

That banned-vocabulary list is a concrete technique to *show* judges: we do not merely ask for a
friendlier tone, we constrain the vocabulary and then machine-check it.

### Stage 4, Validation (deterministic, no AI)

- Stakeholder script contains no banned word (exact plus stem match).
- Every `sourceRef` resolves to a file that exists, at a line that exists.
- Every `evidence` id in the product model exists in `evidence.json`.
- **Narration pace:** `words / 2.5 words-per-sec <= durationMs`.
- **On-screen reading-time floor** (from `brag`): a 1 to 3 word label needs ~0.8s settled; a full
  sentence needs ~0.3s per word, minimum 1.2s. A 4s scene lands 2 to 3 short reads, not 6.
- Total runtime within 15% of the requested length.

A failure produces **one** targeted repair request naming the exact violations. A second failure drops
the offending scene rather than shipping a wrong claim.

### Stage 5, Voice and music

OpenAI TTS (`gpt-4o-mini-tts`), one request per scene, mp3 back. **A different voice per audience**,
warm for stakeholders, crisp for technical: a small touch that lands hard on stage. Cached at
`.idea/nexus-reel/tts/<sha256>.mp3` so re-renders are free. Silent mode fully supported with no key.

Music bed at volume 0.3 to 0.4, ducking to 0.15 under narration (the `brag` convention). One bundled
CC0 track to avoid any licensing question. Music on its own audio track, never sharing a track index
with an overlapping element.

### Provider

`NarrativeEngine` is an interface with one implementation for now, **`OpenAiNarrativeEngine`** (OkHttp
plus Gson, ~150 lines including the tool loop). Koog stays a documented post-hackathon swap: same
interface, no pipeline change. The API key is stored via IntelliJ **`PasswordSafe`**, never on disk in
plaintext, and configured in a proper `Configurable` settings page. That is the JetBrains-native
answer and judges look for it.

**Which models.** Two different jobs, so two different tiers, both overridable in settings:

| Stage | Job | Default | Why |
|---|---|---|---|
| 2, Understanding | tool-calling loop, strict JSON, real reasoning over a whole codebase | strongest GPT-5 class model the key can see | this is the only stage where quality of judgement decides whether the film is right |
| 3, Directing | fill typed slots in a fixed scene enum | GPT-5-mini class | constrained output, cheap, run twice per project |
| 5, Voice | narration | `gpt-4o-mini-tts` | two voices, warm and crisp |

**Do not hard-code an id before checking.** At hour 3, call `GET /v1/models` with the hackathon vault
key and use what it actually returns. Keys are often scoped, and a 404 on a model id at demo time is
an avoidable way to lose. Whatever is chosen goes in settings with the working id as the default.

---

## 6. Rendering: one composition, two renderers

### What we learned from brag and HyperFrames

`brag` is an agent workflow (inspect → plan → compose → deliver) that hand-authors **one `index.html`**
and renders it with **HyperFrames** (`hyperframes` on npm v0.8.60, a HeyGen product). HyperFrames
takes an HTML composition whose DOM declares timing with `data-*` attributes and which registers
exactly one `gsap.timeline({ paused: true })` at `window.__timelines["<composition-id>"]`, then drives
it frame by frame through headless Chrome and encodes with FFmpeg.

Two findings decide our architecture:

1. **A valid HyperFrames project is just `index.html` plus `assets/`.** No `package.json`, no
   `hyperframes.json` required. Confirmed against a real completed run on this machine.
2. **Rendering is far too slow to sit in the interactive path.** The real run on this machine:
   **22.4s of video took 5m 51s**, of which 5m 04s was software x264 encode (2014 i7, no GPU
   encoder). A 90s film would be well over 20 minutes. That is fatal for "press a button, get a
   video", but perfectly fine for a background export.

### The consequence: author once, render twice

A HyperFrames composition is *a plain seekable HTML page*. **The IDE already ships a Chromium: JCEF.**
So the same artifact plays live in the tool window and can be handed to the CLI for a studio render.

- **Author** the reel as HTML + CSS + a single paused GSAP timeline, satisfying the HyperFrames
  contract (`data-composition-id`, `data-width`, `data-height`, `data-duration`; scenes as
  `<section class="clip" data-start data-duration>`; `<audio id data-start data-track-index
  data-volume>`; vendored GSAP; no unseeded `Math.random`, no `repeat: -1`, no network at render).
- **Serve** it from a tiny `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an ephemeral
  port, straight out of the plugin jar (~50 lines, JDK only, no dependency). Relative assets, fonts
  and audio all resolve properly. This also fixes, for our slice, the "plugin is unusable without
  `npm run dev`" problem in GUIDE §7.
- **Play** it in `JBCefBrowser`: `tl.play()`, `tl.seek(t)`, a scrubber and per-scene chapter marks.

**Why DOM/CSS/GSAP and not a canvas renderer:** DOM is far faster to author, looks better, and makes
click-to-source nearly free (`<span data-file="..." data-line="42">` plus one click handler) instead
of requiring hand-rolled canvas hit-testing. The one thing DOM cannot do is be captured by
`MediaRecorder`, which is why export is layered.

### Export, layered by what is available, each layer degrading gracefully

| Layer | Requires | Result |
|---|---|---|
| **Play in IDE** | nothing | always works. This is the product. |
| **Export standalone `.html`** | nothing | one self-contained file with GSAP, audio and CSS inlined. Double-click, it plays. Send it to anyone. |
| **Export `.mp4`** | Node ≥ 22 + ffmpeg, detected via `npx hyperframes doctor --json` | background `Task.Backgroundable` running `npx hyperframes render --quality draft --gpu`, then a notification. Already working on this machine. |

The core demo never depends on the toolchain, and the machine we demo on already has it, so the demo
shows the MP4. If we ship MP4, bake the poster as frame 0 with ffmpeg (Slack, X and Discord
regenerate thumbnails server-side and ignore cover-art metadata).

**Early spike, hour 12:** measure `--quality draft --gpu` on a 90s film. If it is still minutes, the
demo exports the 30s teaser instead and the 90s cut renders during setup.

---

## 7. Scene template catalogue

Each template is a JS function that builds DOM and appends tweens to the shared timeline:
`build(scene, tl, theme) -> HTMLElement`. Colours come from the harvested brand palette, falling back
to the team's `oklch` tokens in [App.css](visualizer-ui/src/App.css) so Reel matches Nexus.

**Core eight (must ship):**

| # | Template | Tech | Stake | Slots |
|---|---|---|---|---|
| 1 | `title` | yes | yes | productName, tagline, repoUrl |
| 2 | `big-statement` | yes | yes | statement, context (kinetic type) |
| 3 | `stat-grid` | yes | yes | stats[{label, value}], counts up |
| 4 | `capability-cards` | yes | yes | cards[{title, body}] |
| 5 | `arch-layers` | yes | - | layers[{name, components[{name, tech}]}] |
| 6 | `flow-trace` | yes | - | steps[{label, file, line}], a packet animated along the chain |
| 7 | `journey` | - | yes | steps[{actor, action}] along a path |
| 8 | `outro` | yes | yes | cta, repoUrl, generatedAt |

Which already composes both films end to end:

- **Technical:** title → stat-grid → arch-layers → flow-trace → capability-cards → outro
- **Stakeholder:** title → big-statement → journey → capability-cards → stat-grid → outro

**`flow-trace` is deliberately generic.** It renders any 3 to 5 step chain, not just an HTTP one.
The harvester fills it from whichever connection evidence exists: matched HTTP calls from
`ProjectFlowAnalyzer` when the project has them, otherwise a cross-boundary bridge (`executeJavaScript`,
`postMessage`, `CustomEvent`, JNI) or an entry-point call chain. **This is required, not optional:
Nexus has zero HTTP calls** (`calls=0, routes=0, matched=0` in the sandbox log), so on our own repo
the chain is `MyToolWindowFactory` → `ProjectFlowAnalyzer.analyze()` → `Gson.toJson` →
`executeJavaScript` → `CustomEvent` → `App.jsx:264` → React Flow. That crosses a language boundary,
which reads better on screen than a REST hop, and it makes the plugin work on any codebase rather
than only on full-stack web apps.

**Stretch six, in this order:** `code-reveal` (a real snippet types in), `gaps` (the honest
known-gaps list, `.impeccable.md` made visible), `integrations` (third parties inferred from
dependencies), `file-tree`, `timeline` (git history), `graph-grow`.

---

## 8. Safety: nothing secret leaves the harvester

Borrowed wholesale from `brag`'s hard rule, and non-negotiable because we are sending codebase
evidence to a third-party API and then putting it on screen.

- The harvester **never** reads `.env*`, `*.pem`, `*.key`, service-account JSON, or anything
  gitignored. `.env.example` is read for **key names only**, never values.
- A scrub pass runs over all harvested text before it leaves Stage 1: redact anything matching
  common token shapes (`sk-`, `ghp_`, AWS keys, JWTs, bearer tokens), email addresses, private
  hostnames and IPs.
- The scrub is the **last thing** Stage 1 does, so no later stage can bypass it.
- Unit-test the scrubber against a fixture of planted secrets. Worth saying out loud to judges.

---

## 9. Plugin integration surface

| Surface | Implementation | Why it scores |
|---|---|---|
| Two generate buttons | *Technical cut* and *Stakeholder cut* in the Reel tab, one click each | one click per film; the second is fast because the model is cached |
| `Generate Product Reel` | `AnAction` in `ToolsMenu` + `ProjectViewPopupMenu`, opens the tab | discoverable where developers already right-click |
| **Reel** tool window | its own `toolWindow` registration, id `Reel`, anchored right | a separate factory class, so `MyToolWindowFactory.kt` is never edited and the team never conflicts with us |
| Progress | `Task.Backgroundable`, real per-stage text, cancellable | honest feedback on a 60s job |
| Settings | `Configurable` + `PasswordSafe` + `PersistentStateComponent` | key handling done the JetBrains way |
| Done notification | `NotificationGroup` with *Play*, *Reveal in Finder*, *Copy path* | closes the loop outside the tool window |
| **Click-to-source** | `JBCefJSQuery` → `OpenFileDescriptor` → `FileEditorManager` | the differentiator; also closes a GUIDE §7 roadmap item |
| Runs in every JetBrains IDE | text-only harvest, `depends` stays `com.intellij.modules.platform` | works unchanged in PyCharm, WebStorm, GoLand |

The last row is a deliberate design decision worth saying out loud: by **not** taking a PSI
dependency, Reel stays IDE-agnostic.

---

## 10. Files to create

All new work lives under paths nobody else on the team owns, so merge conflicts stay near zero. The
only shared files touched are `plugin.xml`, `build.gradle.kts` and `CHANGELOG.md`, once each.

```
src/main/kotlin/com/example/yasinreel/
  ReelAction.kt                 AnAction -> opens the Reel tab
  ReelPipeline.kt               generate(audience): stages 1-2 once and cached, 3-6 per audience
  ProductModelCache.kt          stage-2 result keyed by project path + content hash, on disk
  harvest/EvidenceHarvester.kt  the deep scan -> Evidence
  harvest/BrandPalette.kt       :root custom properties / tailwind config -> theme
  harvest/SecretScrubber.kt     §8, runs last in stage 1
  harvest/Evidence.kt           data classes + Gson serialisation
  llm/NarrativeEngine.kt        interface: understand() / direct()
  llm/OpenAiNarrativeEngine.kt  OkHttp, tool-calling loop, json_schema responses
  llm/Prompts.kt                system prompts + the banned-vocabulary list
  llm/EvidenceTools.kt          readFile / listDir / grep / readRoutes, sandboxed to the project
  model/ProductModel.kt         stage-2 output
  model/Storyboard.kt           stage-3 output, scene enum + typed slots
  validate/StoryboardValidator.kt   the deterministic critic
  tts/TtsClient.kt              OpenAI TTS + sha256 disk cache
  render/ReelServer.kt          127.0.0.1 HttpServer serving the bundled runtime
  render/ReelPanel.kt           JBCefBrowser host + JBCefJSQuery bridge + transport UI
  render/CompositionWriter.kt   storyboard -> standalone HTML folder (HyperFrames-compatible)
  render/HyperFramesExporter.kt doctor --json probe, then render in the background
  settings/ReelSettings.kt      PersistentStateComponent + PasswordSafe
  settings/ReelConfigurable.kt  Settings UI

src/main/resources/yasin-reel/
  index.html                    the player shell (HyperFrames composition contract)
  reel.css                      theme driven by CSS custom properties from the harvested palette
  vendor/gsap.min.js            vendored locally, never from a CDN (determinism rule)
  runtime/timeline.js           builds the single paused GSAP timeline from storyboard.json
  runtime/scenes/*.js           one file per template
  runtime/bridge.js             JS -> Kotlin (openFile, exportRequested, progress)
  assets/music/bed.mp3          one bundled CC0 track
```

**Reused, not rewritten:** `ProjectFlowAnalyzer.analyze(project)` supplies the
frontend → endpoint → backend traces that feed the `flow-trace` scene. That is real synergy: this
feature makes the team's analyzer *presentable*, and the money shot of the technical cut is literally
their output.

**Also required:** declare Gson explicitly in `build.gradle.kts`. It is currently an undeclared
IntelliJ-platform transitive that JetBrains has been removing, and we are about to lean on it hard.

---

## 11. Build order for 15 hours, with checkpoints

| Hours | Work | Checkpoint |
|---|---|---|
| 0.0-0.5 | Branch, package skeleton, plugin.xml + Gradle deps, `ReelServer` serving a hello page | **Reel tab opens and renders with no dev server running** |
| 0.5-1.0 | Build the bridge-chain detector so `flow-trace` has real steps on Nexus | a real traced chain exists for our own repo, since HTTP finds nothing here |
| 1.0-3.0 | `EvidenceHarvester` + `BrandPalette` + `SecretScrubber` | `evidence.json` produced for the demo repo, eyeballed, scrubber unit-tested |
| 3.0-5.0 | `OpenAiNarrativeEngine` + tool loop + settings/PasswordSafe | `productModel.json` generated from the demo repo |
| 5.0-6.5 | Two directors + validator + repair loop + `ProductModelCache` | each audience generates on its own click; the second run visibly skips stages 1-2 |
| 6.5-10.5 | **Reel runtime**: timeline, 8 scene templates, transport UI | both cuts play silently in the tool window |
| 10.5-11.5 | Click-to-source bridge | clicking a path opens the file at the line |
| 11.5-12.5 | TTS + music bed + ducking | both cuts play narrated |
| 12.5-13.5 | Export: standalone HTML always; MP4 spike via HyperFrames | a file lands on disk and plays outside the IDE |
| 13.5-14.5 | Polish: progress UI, empty/error states, provenance overlay, offline replay | graceful on a project with nothing to analyse |
| 14.5-15.0 | Rehearsal, README, slide | the 3-minute run-through is smooth |

**Cut lines, drop in this order if behind:** provenance overlay → music bed → MP4 export (ship the
standalone HTML) → TTS → teaser cut → drop to six scene templates.

**Never cut:** the two audiences, in-IDE playback, click-to-source, a shareable exported artifact.
Those four are the entire idea.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Scene authoring eats the clock | templates are independent and additive; six ships, eight is the target |
| MP4 render too slow on the demo machine | measured risk, already quantified. Layered export means the demo never blocks on it; spike `--quality draft --gpu` at hour 12.5 and fall back to the 30s teaser |
| LLM returns unusable JSON | `response_format: json_schema` + the Stage 4 validator + one repair round-trip |
| No API key or no network at demo time | the last good `reel.json` is cached per project; an offline flag re-renders it with no LLM call. **Rehearse the demo in offline mode.** |
| Nexus has no HTTP calls, so `flow-trace` is empty | confirmed, not a guess. `flow-trace` is generic by design and fed by the bridge-chain detector built at hour 0.5 |
| Project has nothing to analyse | the harvester always yields README, dependencies and structure; low-confidence badge and honest "limited evidence" scenes |
| A secret reaches the screen | §8, scrub last in stage 1, unit-tested against planted fixtures |
| Gson is an undeclared platform transitive | declare it explicitly |
| Merge conflicts with teammates | own only `com.example.yasinreel` and `resources/yasin-reel/`; touch shared files once each |

---

## 13. Verification

**Per stage, runnable independently, so nothing waits on the whole pipeline:**

1. `EvidenceHarvester`: a scratch action writes `evidence.json` to the project root. Check by eye
   against the demo repo. Are the routes, dependencies, entry points, palette and stats right?
2. `SecretScrubber`: JUnit against a fixture containing a planted `sk-...`, a JWT, an AWS key, an
   email and a private hostname. All must be redacted.
3. `NarrativeEngine`: a `main` that reads a fixture `evidence.json` and prints the product model. No
   IDE needed.
4. `StoryboardValidator`: JUnit with a deliberately bad storyboard (a banned word, a non-existent
   file, over-long narration). All three must be caught. These will be the repo's **first tests**.
5. Reel runtime: open `resources/reel/index.html` through the local server in a normal Chrome with a
   fixture `storyboard.json` and scrub the timeline. Fast iteration without launching the IDE.
6. Composition contract: `cd <exported folder> && npx hyperframes check --json` must report `ok`.
   This is a free, machine-readable QA gate for contrast, overflow and clipping, and it is how we
   know the composition is renderable before we try.

**End to end, the real test:**

```bash
./gradlew runIde
```

In the sandbox IDE, open the cloned demo repo and open the **Reel** tab. Click **Technical cut**,
then, once it plays, click **Stakeholder cut**. Confirm, in order:

- the first click reports all six stages, is cancellable, and lands in roughly 45s
- it plays with narration over the music bed, in the demo project's own brand colours
- clicking a file path in the technical cut opens that file at that line
- **the second click skips stages 1 and 2** (the log shows a product-model cache hit) and lands in
  roughly a third of the time. This is the claim the demo rests on, so measure it, do not assume it
- the stakeholder cut contains none of the banned words (the validator log confirms)
- editing a source file then clicking again misses the content hash and re-harvests, and the
  *Re-analyze* affordance forces it
- **Export** writes a standalone HTML that plays in a normal browser, and an MP4 if the toolchain is
  present
- re-running with the network off replays from cache

Also confirm the degenerate cases: a project with no HTTP calls at all, and an empty project. Neither
may crash, and neither may invent content.

---

## 14. Out of scope

Hand-editing the timeline in a UI, multi-language narration, uploading anywhere, Marketplace
publishing, and any change to the team's existing `ProjectFlowAnalyzer` behaviour or `visualizer-ui`.
