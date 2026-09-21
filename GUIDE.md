# Nexus: Developer & Collaborator Guide

Nexus (the plugin is still called **Code Visualizer** in `plugin.xml`) is an IntelliJ Platform plugin. It reads the open project, finds frontend HTTP calls (React / TypeScript) and backend endpoints (FastAPI, Spring), matches them up, and draws the result inside a tool window.

This guide covers three things:

1. How JetBrains plugins work in general
2. How this project is built and how its parts connect
3. How collaborators set up, work, and ship changes together

---

## 1. How a JetBrains plugin works

### 1.1 The basic model

An IntelliJ plugin is a **JAR loaded into the IDE's JVM**. It does not run as a separate process. The IDE finds the plugin through its **descriptor**, `META-INF/plugin.xml`, which declares:

| Element | Purpose |
|---|---|
| `<id>` | Unique, permanent identifier. **Never change it after publishing.** |
| `<name>`, `<vendor>`, `<description>` | What the Marketplace and Settings → Plugins show |
| `<depends>` | Platform modules or other plugins this plugin needs. `com.intellij.modules.platform` means "works in any JetBrains IDE" |
| `<resource-bundle>` | Localized UI strings (`.properties` files) |
| `<extensions>` | The main integration point: registrations for **extension points** (EPs) |
| `<actions>` | Menu items, toolbar buttons, and keyboard shortcuts (this project has none yet) |

### 1.2 Extension points

The platform exposes hundreds of extension points: tool windows, inspections, intentions, file types, line markers, startup activities, services, and more. A plugin plugs in by naming an EP in `plugin.xml` and pointing it at a class that implements the matching interface. The IDE creates that class **lazily**, the first time the feature is needed.

This project registers exactly one EP:

```xml
<toolWindow id="Code Visualizer" anchor="right"
            factoryClass="com.example.MyToolWindowFactory"
            icon="AllIcons.Toolwindows.ToolWindowPalette"/>
```

When the user opens the "Code Visualizer" panel on the right sidebar, the IDE calls `MyToolWindowFactory.createToolWindowContent(project, toolWindow)`.

### 1.3 Core platform concepts used here

| Concept | What it is | Where we use it |
|---|---|---|
| **Project** | One open project. Each IDE window has its own `Project` instance. | Passed into the factory and the analyzer |
| **VirtualFile (VFS)** | The IDE's cached, abstracted view of files on disk | `collectSourceFiles`, `VfsUtilCore.loadText` |
| **ProjectFileIndex** | Knows which files are project *content* and which are excluded, libraries, and so on | `iterateContent` in `ProjectFlowAnalyzer` |
| **Read/Write actions** | Platform data (PSI, VFS, indexes) may only be read inside a read action and changed inside a write action | `ReadAction.nonBlocking { ... }` |
| **Smart / dumb mode** | While the IDE is indexing ("dumb mode"), most index-based APIs are unavailable | `.inSmartMode(project)` delays analysis until indexing finishes |
| **EDT (UI thread)** | Swing's single UI thread. Never do heavy work on it. | `.finishOnUiThread(...)` sends the result back to the UI |
| **Disposer** | The platform's lifecycle and cleanup system | `content.setDisposer(browser)` releases the embedded browser |
| **JCEF** | Chromium embedded in the IDE (`JBCefBrowser`) | Hosts the React UI inside the tool window |
| **DynamicBundle** | Localized string lookup | `MyMessageBundle` |

### 1.4 Threading rules (important)

- Don't block the EDT. Heavy work (file scanning, regex) runs in the background.
- Read platform data inside a read action. `ReadAction.nonBlocking` is cancellable: if the user edits files, the platform restarts it instead of freezing.
- `.expireWith(project)` cancels the work automatically if the project is closed.
- Change UI state only on the EDT.

Breaking these rules causes IDE freezes or `Read access is allowed from inside read-action only` errors in `idea.log`.

### 1.5 Build tooling

Plugins are built with Gradle and the **IntelliJ Platform Gradle Plugin (2.x)**. It:

- downloads the target IDE (here IntelliJ IDEA `2025.3.5`) and compiles against it
- runs a **sandbox IDE** (`runIde`) with the plugin installed, in a separate config and system directory under `.intellijPlatform/sandbox`
- builds the distributable ZIP (`buildPlugin`)
- checks compatibility (`verifyPlugin`) and publishes to the Marketplace (`publishPlugin`)

Useful links:
- IntelliJ Platform SDK docs: https://plugins.jetbrains.com/docs/intellij/
- Gradle plugin docs: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- Threading model: https://plugins.jetbrains.com/docs/intellij/threading-model.html
- JCEF: https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html

---

## 2. Project structure

```
Nexus/
├── .run/                          Shared IntelliJ run configurations
│   ├── runIde.run.xml             "Run IDE with Plugin"  → gradle runIde
│   ├── runTests.run.xml           "Run Tests"            → gradle check
│   └── runVerifications.run.xml   "Run Verifications"    → gradle verifyPlugin
├── .codex/config.toml             Local AI-tool config (IDE MCP endpoint). Not part of the build
├── .impeccable.md                 Design context: users, visual direction, design principles
├── gradle/
│   ├── libs.versions.toml         Version catalog (JUnit)
│   └── wrapper/                   Gradle wrapper (always use ./gradlew, not a global gradle)
├── build.gradle.kts               Plugins + dependencies, including the target IDE version
├── settings.gradle.kts            Plugin versions, repositories, project name
├── gradle.properties              group/version, Gradle caches, Kotlin stdlib opt-out
├── CHANGELOG.md                   Keep-a-Changelog format (read by the changelog plugin)
├── src/main/
│   ├── kotlin/
│   │   ├── MyToolWindowFactory.kt   Tool window + JCEF browser + Kotlin→JS bridge
│   │   ├── ProjectFlowAnalyzer.kt   Static analysis: scan, detect, match, build graph
│   │   └── MyMessageBundle.kt       i18n helper
│   └── resources/
│       ├── META-INF/plugin.xml      Plugin descriptor
│       ├── META-INF/pluginIcon.svg  Marketplace / Settings icon
│       └── messages/MyMessageBundle.properties
└── visualizer-ui/                 React front end rendered inside the tool window
    ├── package.json               React 19, @xyflow/react 12, Vite 8, ESLint
    ├── vite.config.js
    ├── index.html
    └── src/
        ├── main.jsx               React entry point
        ├── App.jsx                Header, view switcher, hierarchical "Code tree" view
        ├── ArchitectureDiagram.jsx  Layered "Architecture" view (lazy-loaded)
        ├── App.css / index.css    Styling (dark, IDE-native look)
        └── assets/
```

Ignored and never committed: `build/`, `.gradle/`, `.idea/`, `.intellijPlatform/`, `.kotlin/`, `visualizer-ui/node_modules`, `visualizer-ui/dist`.

> All Kotlin files declare `package com.example` but live directly in `src/main/kotlin/`. Kotlin allows that, but new files should go in `src/main/kotlin/com/example/...` so the folders match the packages.

---

## 3. Architecture and data flow

The plugin is split into two parts:

- **Kotlin side (inside the IDE):** has access to project files and IDE APIs, and does the analysis.
- **Web side (React inside JCEF):** does all the rendering.

```
┌──────────────────────── IntelliJ IDEA (JVM) ─────────────────────────┐
│                                                                       │
│  User opens "Code Visualizer" tool window                             │
│        │                                                              │
│        ▼                                                              │
│  MyToolWindowFactory.createToolWindowContent()                        │
│        │  1. creates JBCefBrowser                                     │
│        │  2. loadURL("http://localhost:5173?projectName=..&projectPath=..")
│        │                                                              │
│        │  3. onLoadEnd(main frame) ──► analyzeProject()               │
│        │                                  │                           │
│        │         ReadAction.nonBlocking   ▼   (background thread)     │
│        │         .inSmartMode   ProjectFlowAnalyzer.analyze(project)  │
│        │                                  │                           │
│        │                                  ▼  ProjectGraph (data class)│
│        │         finishOnUiThread ──► Gson.toJson(graph)              │
│        │                                  │                           │
│        │  4. executeJavaScript:            ▼                          │
│        │     window.__CODE_VISUALIZER_GRAPH__ = {...}                 │
│        │     dispatchEvent('code-visualizer:graph', {detail})         │
│        ▼                                                              │
│  ┌────────────── JCEF (Chromium) ──────────────┐                      │
│  │  visualizer-ui (React)                       │                      │
│  │  App.jsx listens for 'code-visualizer:graph' │                      │
│  │   ├─ Code tree   (React Flow, expandable)    │                      │
│  │   └─ Architecture (ArchitectureDiagram.jsx)  │                      │
│  └──────────────────────────────────────────────┘                      │
└───────────────────────────────────────────────────────────────────────┘
```

### 3.1 `MyToolWindowFactory.kt`

- Checks `JBCefApp.isSupported()`. If JCEF isn't available, it shows a plain label instead.
- Loads the UI from the **Vite dev server at `http://localhost:5173`**, passing the project name and path as query parameters. The UI uses them as a fallback header before the analysis arrives.
- Uses a `CefLoadHandlerAdapter` to wait until the main frame has loaded, then starts the analysis. It re-runs on every main-frame reload, so refreshing the page re-analyzes.
- Catches analysis errors and turns them into a `ProjectGraph` with `status = "error"`, so the UI always gets a response.
- Sends the result to JS in two ways at once. It sets a global (`window.__CODE_VISUALIZER_GRAPH__`) for when React mounts late, and it fires a DOM event (`code-visualizer:graph`) for when React is already listening.

> **Current limitation:** the UI isn't bundled into the plugin yet. The Vite dev server must be running, or the tool window stays blank. See §7, Roadmap.

### 3.2 `ProjectFlowAnalyzer.kt`: the analysis engine

This is purely static, regex-based analysis. It doesn't use PSI and doesn't run any code.

**Step 1: Collect files.** `ProjectFileIndex.iterateContent` walks project content, so excluded folders and libraries are skipped. It keeps files ≤ 1 MB with these extensions:
- frontend: `js, jsx, ts, tsx`
- backend: `java, kt, py`

**Step 2: Detect frontend calls.**

| Pattern | Example matched | Method |
|---|---|---|
| `axiosCallPattern` | `axios.get('/api/users')` | From the call name |
| `fetchCallPattern` | `fetch('/api/users', { method: 'POST' })` | `method:` in the options, otherwise `GET` |
| `httpWrapperPattern` | `http<User[]>('/api/users')` | Same as fetch |

URLs are normalized by `normalizeRequestPath`:
- `${BASE}` is removed.
- `${user.id}` becomes `{id}`. Complex expressions are dropped.
- Query strings and hashes are stripped. For absolute `http(s)://` URLs, only the path is kept.
- Slashes are collapsed and the trailing `/` is removed.

**Step 3: Detect backend endpoints.**

| Framework | What is detected |
|---|---|
| **FastAPI** (`.py`) | `@app.get("/x")` / `@router.post("/x")` decorators, followed by `def` / `async def`. Adds the `APIRouter(prefix="...")` prefix for `router` routes. Only the **first** `APIRouter` prefix in a file is used. |
| **Spring** (`.java`, `.kt`) | Class-level `@RequestMapping("/base")` plus method-level `@Get/Post/Put/Patch/DeleteMapping` and `@RequestMapping(method = RequestMethod.X)`. A `@RequestMapping` with no method becomes `ANY`. |

A file is skipped quickly if it doesn't contain `Mapping` (Spring) or `APIRouter` / `@app.` (FastAPI).

Results are capped at **200 frontend calls** and **200 backend endpoints** (`MAX_RESULTS`).

**Step 4: Match and build the graph.** A call matches an endpoint when:
- the methods are equal (case-insensitive), or the endpoint is `ANY`, **and**
- the paths match, where each `{param}` segment in the endpoint path matches any single segment (`[^/]+`).

The first matching endpoint wins. Unmatched calls get an `unmatched` node. That's deliberate: the design principle is *never invent a relationship; show what couldn't be resolved.*

### 3.3 The graph contract (Kotlin ↔ React)

This JSON shape is the **interface between the two halves**. If you change it, update both sides in the same pull request.

```jsonc
{
  "projectName": "my-app",
  "projectPath": "C:/code/my-app",
  "status": "ready",            // "ready" | "empty" | "error"
  "message": "Static analysis completed.",
  "nodes": [
    { "id": "route-0",    "kind": "endpoint", "label": "GET /api/users/{id}", "subtitle": "FastAPI endpoint", "technology": "FastAPI" },
    { "id": "backend-0",  "kind": "backend",  "label": "users.get_user()", "subtitle": "app/users.py:12", "technology": "FastAPI", "filePath": "app/users.py", "line": 12 },
    { "id": "frontend-0", "kind": "frontend", "label": "api.ts", "subtitle": "web/src/api.ts:8", "filePath": "web/src/api.ts", "line": 8 },
    { "id": "unmatched-1","kind": "unmatched","label": "GET /external", "subtitle": "No matching backend endpoint" }
  ],
  "edges": [
    { "id": "handles-0",  "source": "route-0",    "target": "backend-0", "label": "handles" },
    { "id": "requests-0", "source": "frontend-0", "target": "route-0",   "label": "GET" }
  ],
  "stats": { "scannedFiles": 42, "frontendCalls": 2, "backendEndpoints": 1, "matchedCalls": 1 }
}
```

Node kinds: `frontend`, `endpoint`, `backend`, `unmatched`.
Edges: `frontend → endpoint | unmatched` (label = HTTP method) and `endpoint → backend` (label = `handles`).

### 3.4 `visualizer-ui`

- **`App.jsx`**
  - Gets the graph from `window.__CODE_VISUALIZER_GRAPH__` or from the `code-visualizer:graph` event.
  - `buildHierarchy()` turns the flat graph into a tree: **Project → System (Frontend / Backend / Other-unresolved) → Technology → File → Endpoint**.
  - `layoutTree()` places each depth in a fixed column (`HORIZONTAL_GAP = 300`, `VERTICAL_GAP = 112`). Only expanded branches are laid out, and clicking a node expands or collapses it.
  - Rendered with **React Flow** (`@xyflow/react`). Nodes can't be dragged or connected.
  - Handles the loading, empty, and error states explicitly.
- **`ArchitectureDiagram.jsx`**
  - Lazy-loaded.
  - Shows a fixed 1000×620 layered map: Client layer → Interface layer (API routes) → Service layer (one box per backend technology), plus an "External / unresolved" box when needed.
  - Has zoom controls (70–130%) and auto-fits the window with a `ResizeObserver`.
- **Styling:** follow `.impeccable.md`. That means a dark, IDE-native look, restrained semantic colors (frontend / HTTP / backend / unmatched), and layouts that work in a narrow tool window.

---

## 4. Getting started (every collaborator)

### 4.1 Prerequisites

- **IntelliJ IDEA** (Community or Ultimate), a recent version
- **JDK 21** (the foojay toolchain resolver can download it for Gradle automatically)
- **Node.js 20+** and npm
- **Git**

### 4.2 First-time setup

```bash
git clone https://github.com/agegnew/Nexus.git
cd Nexus

# Front end dependencies
cd visualizer-ui
npm install
cd ..
```

Open the `Nexus` folder in IntelliJ IDEA and let Gradle sync finish. The first sync downloads IntelliJ IDEA 2025.3.5 (about 1 GB), so expect it to take a while.

### 4.3 Run the plugin

You need **two processes**:

1. **Start the UI dev server** (keep it running):
   ```bash
   cd visualizer-ui
   npm run dev          # serves http://localhost:5173
   ```
2. **Start the sandbox IDE**: use the run configuration **"Run IDE with Plugin"**, or run:
   ```bash
   ./gradlew runIde     # Windows: gradlew.bat runIde
   ```
3. In the sandbox IDE, open a project that has a React frontend and a FastAPI or Spring backend, then open **View → Tool Windows → Code Visualizer** (right sidebar).

Vite hot-reloads UI changes inside the tool window. For Kotlin changes, restart `runIde`.

### 4.4 Debugging

- **Kotlin:** start "Run IDE with Plugin" in **Debug** mode and set breakpoints in the factory or analyzer.
- **Logs:** the sandbox IDE writes to `.intellijPlatform/sandbox/*/*/log/idea.log`, which the run configuration shows as a tab. Look for `Code Visualizer analysis completed: files=…, calls=…, routes=…, matched=…`.
- **Web UI inside JCEF:** in the sandbox IDE, open *Help → Find Action → Registry*, enable `ide.browser.jcef.debug.port` (for example `9222`), then open `http://localhost:9222` in Chrome to use DevTools. You can also develop the UI in a normal browser at `http://localhost:5173` and inject a test graph from the console:
  ```js
  window.dispatchEvent(new CustomEvent('code-visualizer:graph', { detail: { /* graph JSON */ } }))
  ```

### 4.5 Useful Gradle tasks

| Command | What it does |
|---|---|
| `./gradlew runIde` | Launch the sandbox IDE with the plugin |
| `./gradlew build` | Compile and run tests |
| `./gradlew check` | Run tests and checks |
| `./gradlew buildPlugin` | Build the installable ZIP in `build/distributions/` |
| `./gradlew verifyPlugin` | Check binary compatibility against IDE versions |
| `./gradlew patchChangelog` | Move `[Unreleased]` changelog notes into a version section |

UI commands (inside `visualizer-ui/`): `npm run dev`, `npm run build`, `npm run lint`, `npm run preview`.

---

## 5. Working together

### 5.1 Branching model

- `main` must always build and run. **Don't commit directly to `main`.**
- Create a short-lived branch for each change:
  - `feature/<short-name>`, e.g. `feature/nextjs-fetch-detection`
  - `fix/<short-name>`, e.g. `fix/fastapi-multiple-prefixes`
  - `docs/<short-name>`, `refactor/<short-name>`, `chore/<short-name>`
- Rebase on `main` (or merge `main` in) before opening a pull request.

```bash
git checkout main && git pull
git checkout -b feature/my-change
# ...work...
git add -A && git commit -m "feat(analyzer): detect Express routes"
git push -u origin feature/my-change
# open a Pull Request on GitHub
```

### 5.2 Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`.

- Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `style`, `perf`
- Scopes: `analyzer`, `toolwindow`, `ui`, `build`, `docs`

### 5.3 Pull requests

Every PR should:
- explain **what** changed and **why**, and link the issue if there is one
- include a screenshot or GIF for any UI change
- say how it was tested (which sample project, which frameworks)
- pass `./gradlew build` and `npm run lint`
- update `CHANGELOG.md` under `## [Unreleased]` for user-visible changes
- update **both** Kotlin and React when the graph contract (§3.3) changes
- get at least **one review** before merging (squash-merge is preferred)

### 5.4 Who works where

The two halves are independent apart from the graph contract, so people can work in parallel:

| Area | Files | Skills |
|---|---|---|
| Detection / matching | `ProjectFlowAnalyzer.kt` | Kotlin, regex, target frameworks |
| IDE integration | `MyToolWindowFactory.kt`, `plugin.xml` | IntelliJ Platform SDK |
| Visualization | `visualizer-ui/src/*` | React, React Flow, CSS |
| Build / release | `*.gradle.kts`, `gradle.properties`, `CHANGELOG.md` | Gradle |

To avoid merge conflicts, agree in the issue who owns a file before two people make large edits to it. Adding new detectors as separate functions or files, rather than growing existing regexes, also keeps conflicts small.

### 5.5 Coding conventions

**Kotlin**
- Follow the official Kotlin style (IntelliJ: *Code → Reformat Code*).
- Put new classes in `package com.example` under `src/main/kotlin/com/example/`.
- Heavy work goes in background or read actions, never on the EDT (§1.4).
- Put user-visible strings in `MyMessageBundle.properties` and read them via `MyMessageBundle.message(...)`.
- Log with `Logger.getInstance(...)`. Don't use `println`.

**React**
- Use function components and hooks, and memoize derived data (`useMemo`) the way `App.jsx` does.
- Run `npm run lint` before pushing.
- Respect the design principles in `.impeccable.md`. In particular, never hide unmatched results.

### 5.6 Adding support for a new framework (example workflow)

1. Add the file extension to `frontendExtensions` or `backendExtensions` if needed.
2. Write a `find<Framework>Endpoints(file, projectPath)` function (or a frontend-call detector) that returns `BackendEndpoint` / `FrontendCall`, and set `framework = "<Name>"`.
3. Wire it into the `when` block in `analyze()`.
4. Normalize paths with `normalizeRoutePath` so that `{param}` matching keeps working.
5. Add unit tests (see §6).
6. Test in the sandbox IDE against a small sample project.
7. The UI groups backend nodes by `technology` automatically, so no UI change is usually needed.

### 5.7 Git identity

Commit with the name and email linked to your own GitHub account (`git config user.name` / `git config user.email`), so your commits are attributed to you.

---

## 6. Testing

- JUnit 4 and the IntelliJ test framework (`TestFrameworkType.Platform`) are already configured. Put tests in `src/test/kotlin/`.
- There are **no tests yet**. The first priority is unit tests for the analyzer's pure logic: path normalization, `pathsMatch`, and each regex against sample source text. Platform-level tests can extend `BasePlatformTestCase` and build a fixture project.
- Run tests with `./gradlew check` or the **"Run Tests"** run configuration.

---

## 7. Roadmap / known limitations

| Limitation | Impact | Possible direction |
|---|---|---|
| UI loaded from `localhost:5173` | The plugin only works while `npm run dev` is running, so it can't be released as-is | Build `visualizer-ui` into `src/main/resources/webview/` and serve it through a JCEF resource handler; add a Gradle task that runs `npm run build` |
| Regex-based detection | Misses dynamic URLs, API clients built with `axios.create`, and multi-line decorators beyond the look-ahead limits | Move to PSI or tree-sitter for accurate parsing |
| One FastAPI prefix per file; `include_router(prefix=…)` ignored | Wrong paths in larger apps | Resolve `include_router` chains across files |
| Hard cap of 200 calls/endpoints | Large projects are truncated silently | Show in the UI that results were truncated; make the cap configurable |
| No click-to-navigate | Users can't jump to source from the graph | Add a JS → Kotlin bridge (`JBCefJSQuery`) that opens `filePath:line` via `OpenFileDescriptor` |
| No refresh action | Re-analysis needs a page reload | Add a tool-window toolbar action, or re-run on VFS changes |
| Placeholder metadata (`com.example`, "Plugin Test") | Not ready for the Marketplace | Rename the id, group, and vendor **before** the first public release (the id can't be changed later) |

---

## 8. Release checklist

1. Update `version` in `gradle.properties`.
2. Run `./gradlew patchChangelog` so the notes move to the new version.
3. Run `./gradlew build verifyPlugin buildPlugin`.
4. Install `build/distributions/*.zip` via *Settings → Plugins → ⚙ → Install Plugin from Disk…* and smoke-test it.
5. Tag the release: `git tag v1.0.0 && git push --tags`.
6. Publish with `./gradlew publishPlugin`. This needs a Marketplace token in the `PUBLISH_TOKEN` env var, configured in `build.gradle.kts`.
