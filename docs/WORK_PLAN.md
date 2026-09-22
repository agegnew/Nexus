# Nexus — Hackathon Work Plan (final is 2026-09-23)

> Nexus is an IntelliJ plugin that draws how a full-stack app is wired:
> React/TS `fetch` / `axios` calls → HTTP routes → FastAPI / Spring handlers.
> This plan adds the features that turn the diagram into a **tool** for the demo.

**Rules for tonight:** prototype quality. No tests, no security, no bundling. Only what the judges will see.

---

## 0. Situation

| Fact | Consequence |
|---|---|
| Final presentation is **tomorrow (2026-09-23)** | One evening + one morning. Cut anything not visible in the demo. |
| `agegnew` wrote the whole base (Kotlin + React) on 2026-09-21 | He is the only one who can build & run the plugin (`./gradlew runIde`). |
| Hamad's laptop has **Node but no Java / no IntelliJ** | Hamad + Claude work on the React UI and test it in a normal browser (`npm run dev` → `http://localhost:5173`). Kotlin changes are written carefully and **agegnew compiles them**. |
| There is **no demo project** in the workspace | The analyzer has nothing to scan. Phase 0 fixes this first. |
| UI is served from the Vite dev server (`localhost:5173`) | On stage: run `npm run dev` on agegnew's laptop **before** opening the tool window. |

### Team split

| Who | Does | Where |
|---|---|---|
| **Hamad + Claude** | Phase 0 demo project · Phase 1A Health/Ghosts · React side of Phase 1B · Phase 2 React features | `demo/`, `visualizer-ui/src/*` |
| **agegnew** | Kotlin side of Phase 1B (click-to-jump) · Kotlin side of Phase 2A (generate endpoint) · runs the sandbox IDE, records screenshots/GIF | `src/main/kotlin/*` |

Branch: everybody on **`feature/nexus-live`** (one branch, small commits, `git pull --rebase` before push). `main` stays as the safe fallback.

---

## Phase 0 — Demo project (tonight, first 45 min) · Hamad + Claude

**Why:** the demo must be *guaranteed* to show wired calls, broken wires, and ghost routes. Random real repos will not.

Create `demo/nexus-demo-app/` (opened as its own project in the sandbox IDE):

```
demo/nexus-demo-app/
├── web/src/
│   ├── api.ts            # typed http<T>() wrapper + fetch calls
│   ├── UserList.tsx      # GET /api/users, GET /api/users/${user.id}
│   ├── OrderPage.tsx     # GET /api/orders, POST /api/orders
│   ├── Settings.tsx      # PUT /api/users/${id}/settings   <- BROKEN (backend has no PUT)
│   └── Analytics.tsx     # GET /api/analytics/summary       <- BROKEN (no backend at all)
└── backend/app/
    ├── main.py           # app = FastAPI(); include_router(...); @app.get("/health") <- GHOST
    ├── users.py          # APIRouter(prefix="/api/users"): GET "", GET "/{user_id}", DELETE "/{user_id}" <- DELETE is GHOST
    └── orders.py         # APIRouter(prefix="/api/orders"): GET "", POST ""
```

Expected result in the graph: **frontend calls 6 · matched 4 · broken 2 · endpoints 6 · ghosts 2**.

**Done when:** agegnew opens `demo/nexus-demo-app` in the sandbox IDE, opens the Code Visualizer tool window, and the header says "4 of 6 calls matched".

Also add a **browser safety net**: `visualizer-ui/src/dev/sampleGraph.js` = the exact JSON the analyzer produces for this demo project. Opening `http://localhost:5173/?demo=1` renders it without the IDE. Used for (a) developing tonight without Java, (b) emergency fallback on stage if the plugin breaks.

---

## Phase 1A — Health score + Ghost routes 👻 (tonight) · Hamad + Claude · React only

**Idea:** every node in the tree gets a health badge, and the header gets a project health strip. Names are fun on purpose: *wired ✅ · broken wire ⚡ · ghost route 👻*.

### Definitions (computed from the existing graph JSON — no Kotlin change)

| Term | Meaning | How to compute |
|---|---|---|
| **Wired call** | frontend call that reached a backend endpoint | `frontend` node whose outgoing edge targets an `endpoint` node |
| **Broken wire ⚡** | frontend call with no backend | `frontend` node whose outgoing edge targets an `unmatched` node |
| **Live route** | endpoint that at least one frontend call uses | `endpoint` node with ≥ 1 incoming edge from a `frontend` node |
| **Ghost route 👻** | endpoint nobody calls (dead API) | `endpoint` node with 0 incoming edges from `frontend` nodes |
| **Leaf score** | 100 for wired / live, 0 for broken / ghost | |
| **Branch score** | `healthy leaves / total leaves` under that branch, as % | aggregated in `buildHierarchy` |
| **Project health** | `(wired calls + live routes) / (frontend calls + endpoints)` as % | shown in the header |

Colour bands: **≥ 80 % green · 50–79 % amber · < 50 % red**. Reuse the existing `frontend / backend / unmatched` tone colours from `App.css`; no new palette.

### Steps

1. `visualizer-ui/src/health.js` (new, pure functions): `computeHealth(analysis)` → `{ score, wired, broken, live, ghosts, ghostRouteIds, brokenCallIds }`, plus `aggregate(children)` for branch scores.
2. `App.jsx` → `buildHierarchy()`: attach `health: { state, score, healthy, total }` to every tree item. Backend leaves that are ghosts get `state: 'ghost'` and tone `unmatched`. Frontend leaves that are broken already get tone `unmatched` — add `state: 'broken'`.
3. `App.jsx` → `TreeNode`: render a badge in the top-right: leaf → `✅ wired` / `⚡ broken` / `👻 ghost` / `✅ live · 2 callers`; branch → `3/4 · 75 %` pill in the colour band.
4. `App.jsx` header: replace "4 of 6 calls matched" with the **health strip**: `Health 67 % · 4 wired · 2 broken ⚡ · 2 ghosts 👻`. Nice-to-have: clicking "ghosts" or "broken" expands the tree to those leaves.
5. `App.css`: `.health-badge`, `.health-badge--good/--warn/--bad`, `.health-strip`. Keep it dense and IDE-like (see `.impeccable.md`).
6. Optional (5 min): show the same strip in the `ArchitectureDiagram.jsx` heading.

**Test here:** `npm run dev` → `http://localhost:5173/?demo=1` → badges visible, numbers match the table in Phase 0.

**Done when:** every tree node shows a badge, ghosts are visible in the backend branch, the header shows the strip, `npm run lint` passes.

---

## Phase 1B — Click-to-jump (tonight) · React: Hamad + Claude · Kotlin: agegnew

**Idea:** click a node → IntelliJ opens the file at the right line (or selects the folder in the Project view). This makes the graph feel like part of the IDE.

### Contract (JS → Kotlin bridge)

Kotlin injects one global function into the page **before** the graph is sent:

```js
window.__nexusOpenFile(JSON.stringify({ filePath: "backend/app/users.py", line: 12 }))
```

`filePath` is relative to the project root (already how the analyzer reports it). `line` is 1-based. If `filePath` is a directory → select it in the Project tool window.

### React side (Hamad + Claude)

1. `buildHierarchy()`: carry `filePath` (+ `line` for leaves) into every **file** item and every **route** leaf (frontend leaf → the frontend node's file/line; backend leaf → the backend node's file/line).
2. `TreeNode`: add a small `↗` button (top-right, next to the badge) on nodes that have `filePath`. Clicking it calls `openInIde(filePath, line)`; it does **not** toggle expand. A single click on a **leaf** (no children) also jumps.
3. `src/ideBridge.js` (new): `openInIde(filePath, line)` → calls `window.__nexusOpenFile` if it exists, otherwise `console.info('[nexus] would open', filePath, line)` (browser dev mode).

### Kotlin side (agegnew) — `src/main/kotlin/MyToolWindowFactory.kt`

```kotlin
// new imports
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefJSQuery

// inside createToolWindowContent, right after `val browser = JBCefBrowser()`
val openFileQuery = JBCefJSQuery.create(browser)   // disposed together with the browser
openFileQuery.addHandler { payload ->
    runCatching { openInIde(project, gson.fromJson(payload, OpenFileRequest::class.java)) }
        .onFailure { logger.warn("Nexus open-file failed: $payload", it) }
    null
}

// inside onLoadEnd, BEFORE analyzeProject(project, browser)
cefBrowser.executeJavaScript(
    """
    window.__nexusOpenFile = function (payload) { ${openFileQuery.inject("payload")} };
    """.trimIndent(),
    cefBrowser.url,
    0
)

// class-level
private data class OpenFileRequest(val filePath: String = "", val line: Int = 1)

private fun openInIde(project: Project, request: OpenFileRequest) {
    val base = project.basePath ?: return
    val fullPath = "$base/${request.filePath}".replace('\\', '/')
    ApplicationManager.getApplication().invokeLater {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath) ?: return@invokeLater
        if (file.isDirectory) {
            ProjectView.getInstance(project).select(null, file, true)
        } else {
            OpenFileDescriptor(project, file, (request.line - 1).coerceAtLeast(0), 0).navigate(true)
        }
    }
}
```

**agegnew checklist:** `./gradlew build` compiles → `runIde` → open the demo project → click the `users.py` leaf → the editor opens `backend/app/users.py` at the decorator line. If nothing happens, search `idea.log` for "Nexus open-file failed".

**Done when:** clicking any leaf or `↗` opens the right file at the right line in the sandbox IDE.

---

## Phase 2 — Tomorrow morning, only if Phase 0 + 1 are demo-ready

Priority order: **2A → 2B → 2C**. Stop wherever time runs out; each one stands alone.

### 2A — Generate the missing endpoint (HIGH priority) · Kotlin: agegnew · React: Hamad + Claude

**Idea:** click a broken wire ⚡ → "Create FastAPI endpoint" → Nexus writes a stub into the right Python file, opens it, re-analyzes → the wire heals ✅ on screen. Codegen from the diagram = the strongest demo moment.

**Contract:**
```js
window.__nexusCreateEndpoint(JSON.stringify({ method: "PUT", path: "/api/users/{id}/settings" }))
```

**Kotlin (~80 lines, new file `src/main/kotlin/com/example/EndpointGenerator.kt`):**
1. Scan project `.py` files for `APIRouter(prefix="…")`; choose the file whose prefix is the **longest prefix** of `path`. If none, choose the file containing `FastAPI(` and use `@app`.
2. Strip the prefix; build the stub:
   ```python

   @router.put("/{id}/settings")
   async def put_settings(id: str):
       # TODO: generated by Nexus — implement me
       return {"status": "not implemented"}
   ```
   Handler name = `<method>_<last static segment>`; every `{param}` becomes a `str` argument.
3. `WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, stub) }`, then `OpenFileDescriptor(...).navigate(true)` at the new line, then call `analyzeProject(project, browser)` again so the graph updates.
4. Register a second `JBCefJSQuery` + inject `window.__nexusCreateEndpoint` (same pattern as 1B). Also inject `window.__nexusRefresh` that just re-runs `analyzeProject` — gives a free **Refresh** button.

**React:** the broken-wire leaf shows a `＋ Create endpoint` button (FastAPI only for the demo). The header gets a `↻ Refresh` button calling `window.__nexusRefresh`.

**Done when:** on stage, `Settings.tsx`'s broken `PUT /api/users/{id}/settings` → click Create → `users.py` gets the stub, the graph turns green for that call.

### 2B — Blast radius · React only · ~1 h

Click a **backend** leaf → every frontend leaf that calls it is highlighted, everything else dims, the node shows *"3 screens depend on this"*. Implementation: `selectedEndpointId` state; in `layoutTree` mark `data.highlight` / `data.dimmed`; auto-expand the frontend branches that contain the callers. Escape / click on the background clears it.

### 2C — Tour mode · React only · ~2 h

A `▶ Tour` button plays a scripted sequence with a caption bar: *expand Frontend → expand Backend → point at a ghost 👻 → point at a broken wire ⚡ → jump to code*. Implemented as an array of steps `{ caption, expandIds, selectId, view }` run with `setTimeout` (2.5 s per step); Esc stops. This is your presentation running itself — no nervous clicking.

---

## 3. Demo script (90 seconds)

1. **"Every dev has opened an unfamiliar full-stack repo and asked: which backend does this button hit?"** Open `nexus-demo-app`, open the Code Visualizer tool window. The tree appears.
2. Point at the header: **"Health 67 % — Nexus tells you immediately what's wired, what's broken, what's dead."**
3. Expand Backend → **ghost route 👻** `DELETE /api/users/{user_id}`: "nobody calls this — dead code."
4. Expand Other/unresolved → **broken wire ⚡** `PUT /api/users/{id}/settings`: "the frontend calls something that doesn't exist. This is a production bug waiting."
5. Click the leaf → **IntelliJ jumps to `Settings.tsx:14`**. "We're inside the IDE — GitHub's diagram can't do this."
6. *(If 2A is done)* Click **Create endpoint** → `users.py` opens with the stub → the graph heals. "From diagram to code in one click."
7. *(If 2C is done)* Or just press ▶ Tour and narrate over it.
8. Close: "Nexus never invents a relationship — everything you see was found in the code."

**Backup:** if the plugin fails, open `http://localhost:5173/?demo=1` in Chrome and present from the browser.

---

## 4. Order of work tonight (checklist)

- [ ] Hamad+Claude: `git checkout -b feature/nexus-live`
- [ ] Hamad+Claude: Phase 0 demo project + `sampleGraph.js` + `?demo=1` mode
- [ ] Hamad+Claude: Phase 1A `health.js` + badges + strip → test in browser → commit
- [ ] Hamad+Claude: Phase 1B React side (`ideBridge.js`, `↗` button) → commit → push
- [ ] agegnew: pull, paste the Kotlin from Phase 1B, `./gradlew build`, `runIde`, open `demo/nexus-demo-app`, verify the jump works → commit → push
- [ ] agegnew: screenshot/GIF of the tool window on the demo project (for slides + README)
- [ ] Everyone: 10-minute rehearsal of the demo script
- [ ] Tomorrow morning: Phase 2A → 2B → 2C in that order; stop 1 hour before the final

## 5. What we deliberately do NOT do

Bundle the UI into the plugin · PSI parsing · unit tests · Marketplace metadata · AI/LLM features · security hardening. These are all listed in `GUIDE.md §7` for after the hackathon.
