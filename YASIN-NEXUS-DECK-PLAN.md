# Nexus Deck: the same understanding, as a .pptx

> Third tab in the Nexus tool window. Two buttons, one per audience, same as the Reel.
> Branch: `yasin/full-codebase-video`.

---

## 1. The idea in one line

The expensive part of Nexus Reel was never the video. It was **working out what the
codebase is**, and that result is already built and cached. A deck is a third renderer
over the same understanding.

```
                          ┌─ Technical video   (shipped)
HARVEST ─> UNDERSTAND ─┬──┼─ Stakeholder video (shipped)
          (cached)     │  ├─ Technical deck    (this)
                       │  └─ Stakeholder deck  (this)
```

So the demo line is: *"Same understanding. Four outputs. The deck arrived in seconds
because the model was already built."* That is the architecture showing itself on stage,
which is the same trick the second video cut already pulls.

---

## 2. Two decisions that shape everything

### 2.1 Write the OOXML by hand, take no dependency

A `.pptx` is a ZIP of XML parts. The alternative is Apache POI, which means
`poi-ooxml` + `poi` + `xmlbeans` + `commons-*`: roughly 20 MB into the plugin, a slow
first-use schema load, and a real risk of a classloader fight with the IntelliJ
platform, which ships its own `commons-compress` and friends. That risk lands at demo
time, which is the worst possible moment.

Hand-written OOXML needs nothing but `java.util.zip` and string building, which is
already how this codebase solves things: the Reel serves itself from a 50 line
`HttpServer` rather than taking a web framework, and vendors GSAP rather than fetching
it.

**This was not assumed, it was proved before planning any further.** A throwaway
generator wrote a two slide deck with nothing but the standard library, and LibreOffice
opened it and rendered it exactly as specified, down to the letter spacing and the
accent bar. See §7.

### 2.2 One geometry model, two renderers

The thing that bit this project before was two halves of a contract drifting apart (the
outro wrote `{headline, sub}` and the player read `{cta, repoUrl}`, so the last scene
silently showed a hardcoded string). So the deck compiles to **one list of positioned
shapes**, and both outputs render that list:

```
Deck ──> DeckGeometry ──> List<Shape> ──┬──> PptxWriter   ──> .pptx
                                        └──> the preview  ──> HTML in the tab
```

A `Shape` is a rectangle, a text box or a picture with absolute coordinates. Neither
renderer decides anything about layout, so neither can disagree with the other about
what is on a slide.

**Coordinates are pixels.** A 16:9 slide is 12192000 x 6858000 EMU, and there are
exactly 9525 EMU per pixel at 96 dpi, so **1280 x 720 px maps onto a slide with no
rounding at all**. The deck is designed in the same kind of fixed pixel space as the
reel's 1200 x 675 stage, and the same instincts apply.

---

## 3. Where the LLM sits, and where it turned out not to belong

| Stage | Input | Output | AI? |
|---|---|---|---|
| 1 HARVEST | the project | `Evidence` | no |
| 2 UNDERSTAND | `Evidence` | `ProductModel` | yes, **cached, usually already done** |
| 3' COMPOSE | `ProductModel` + `Evidence` + audience | `Deck` | no |
| 4' VALIDATE | `Deck` | `Deck` | no, deterministic critic |
| 5' DRAW | `Deck` | `List<Shape>` | no |
| 6' WRITE | `List<Shape>` | `.pptx` | no |

**This plan started with a third AI call and then dropped it, on purpose.** Writing stage
3' out made clear it would have been a call added for its own sake. `ProductModel` was
already designed to serve two audiences: a capability carries a `userFacingName` and a
`userBenefit` for one room and a `technicalSummary` for the other, and the model already
holds a problem statement, a target user, layers, flows, a stack, measurements and an
honest list of gaps. That is a deck outline. Projecting it costs no latency, adds no new
prompt to get wrong, and makes it impossible for the deck to contradict the film, because
both read the same understanding.

The AI is still what makes the deck possible. It just already ran, in stage 2, and its
result is cached, which is why a deck arrives in about a second on a project whose film
has already been made.

**With no API key it still produces a full deck.** `DeckComposer.fromEvidence` converts
what `FallbackDirector` already produces for the film. That director was written and
corrected against the one complaint that mattered most on this project, that a
stakeholder must never be shown a file path or the word endpoint, and rewriting that
judgement from scratch here would mean rediscovering it.

---

## 4. The layout catalogue

Twelve layouts. Technical-only, stakeholder-only and shared, mirroring the scene
templates.

| # | Layout | Tech | Stake | Slots |
|---|---|:--:|:--:|---|
| 1 | `title` | yes | yes | productName, tagline, project, date |
| 2 | `agenda` | yes | yes | items[{label, icon}] |
| 3 | `problem` | yes | yes | heading, body, context |
| 4 | `statement` | yes | yes | statement, attribution |
| 5 | `capability-grid` | yes | yes | heading, cards[{title, body, icon}] |
| 6 | `arch-layers` | yes | - | heading, layers[{name, components[]}] |
| 7 | `flow` | yes | yes | heading, steps[{label, detail}] |
| 8 | `stats` | yes | yes | heading, stats[{value, label}] |
| 9 | `stack` | yes | - | heading, groups[{category, items[]}] |
| 10 | `journey` | - | yes | heading, steps[{actor, action}] |
| 11 | `gaps` | yes | yes | heading, items[], framing |
| 12 | `closing` | yes | yes | headline, repoUrl, stamp |

**Technical deck:** title, agenda, problem, arch-layers, flow, stack, capability-grid,
stats, gaps, closing.

**Stakeholder deck:** title, agenda, problem, statement, journey, capability-grid, stats,
gaps as *what is next*, closing.

Every slide carries **speaker notes** in `ppt/notesSlides/`. That is the one thing a deck
has that a film does not, and it is what makes the deck usable by someone who did not
write the code.

---

## 5. Keeping text inside its box

The film could measure its own layout in a browser and shrink type until it fit. A
`.pptx` writer cannot measure anything, so the defence has to move earlier:

1. **The director is given hard limits** per slot (a stat value is at most 12
   characters, a card title at most 40, and so on) and `DeckValidator` enforces them,
   the same way the storyboard validator already caps a stat value.
2. **A width estimate** from a per-character table for Arial picks the font size step,
   the same idea as `scaleType` in `scenes.js`.
3. **`<a:normAutofit>`** on every text body, so PowerPoint and LibreOffice shrink
   anything that still does not fit rather than letting it spill.
4. **Every slide is rendered and looked at** (§7), which is how the film's real defects
   were found.

The stakeholder deck inherits the **banned vocabulary list** unchanged from
`StoryboardValidator.BANNED_WORDS`. Stakeholders do not read the words `endpoint`,
`repository` or `codebase` in a film, and they will not read them in a deck either.

---

## 6. The font, and why it is boring

**Arial.** A `.pptx` names fonts, it does not embed them by default, so a deck set in
something fashionable renders as a substitute on the machine it is emailed to. Arial is
present on Windows, macOS, Office, LibreOffice and Google Slides and is metrically
identical everywhere, so the slide you looked at is the slide they see. A deck that
leaves the building has to survive the trip.

Icons are the **same sixteen Fluent Emoji** the film uses, rasterised to PNG and
embedded in `ppt/media/`, so the deck and the video are visibly the same product. PNG
rather than SVG because `.pptx` SVG support is recent and uneven.

---

## 7. How it gets verified

The film's real defects were found by seeking the timeline in headless Chrome and
looking at actual frames. Reading the code would not have found any of them. The deck
gets the same treatment, and the machine already has what it needs:

```
Deck ──> .pptx ──> LibreOffice --headless --convert-to png ──> look at every slide
```

LibreOffice exports the first slide of a file, so the harness writes **one single slide
deck per slide** from the same slide XML and converts them all in one batch. Already
proved to work on this machine.

Checks, per slide:

- it opens at all, which is the check that a hand written package really has to pass
- no text box overflows its frame, measured from the PDF text positions
- the stakeholder deck contains no banned word
- every number on a slide traces to a `ScaleFact` that came out of the harvester
- both decks open with zero repair warnings

---

## 8. Files

```
src/main/kotlin/com/example/yasinreel/deck/
  Deck.kt                   the stage 3' contract: Deck, Slide, SlideLayout, Caps
  DeckComposer.kt           ProductModel -> Deck, and Evidence -> Deck with no key
  DeckValidator.kt          layout rules, banned words, repair
  DeckTheme.kt              colours, type scale, the Arial metric table
  DeckGeometry.kt           Deck -> List<Shape>, the one layout authority
  Shape.kt                  box / text / picture, in pixels
  PptxWriter.kt             List<Shape> -> OOXML -> .pptx, java.util.zip only
  DeckPreviewJson.kt        the same shapes, for the tab's preview
  DeckIcons.kt              the sixteen PNGs, and which one a slide wears
  DeckPipeline.kt           orchestration, reuses the reel's understanding stage
  DeckToolWindowFactory.kt  the tab
src/main/resources/yasin-deck/
  index.html, deck.css, runtime/preview.js, runtime/deck.js
  icons/*.png                            sixteen icons, rasterised from the film's set
src/test/kotlin/com/example/yasinreel/
  DeckTest.kt               package structure, fit, vocabulary, and a slide dump to look at
```

Shared files touched **once each**: `NexusToolWindowFactory.kt` (add the tab),
`ReelServer.kt` (serve the new root under `/deck`), and `ReelPipeline.kt`, where stages 1
and 2 became a public `buildUnderstanding` so the deck can call the same code rather than
read the project twice. No teammate file is edited, as before.

Decks are written to `~/Desktop/Nexus Reel/`, the folder the video exports already use,
named `<project>-technical-<date>.pptx`. One place for everything Nexus produces.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| PowerPoint shows a repair dialog | the format was proved before planning; every generated deck is opened by LibreOffice in the verification pass, and a package that survives a strict reader is very unlikely to fail a lenient one |
| Text overflows a box | four defences in §5, and every slide is looked at |
| The deck contradicts the film | impossible by construction, both are composed from the same cached `ProductModel` |
| No API key at demo time | `DeckComposer.fromEvidence` produces a complete deck from harvested facts alone |
| Fonts substitute on another machine | Arial, §6 |
| Drift between preview and .pptx | one geometry model, §2.2 |
