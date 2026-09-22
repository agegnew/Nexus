# visualizer-ui

React + Vite frontend for the Nexus IntelliJ plugin. The plugin opens this app in a
JCEF browser (`http://localhost:5173` in dev), then injects the analysis result as
`window.__CODE_VISUALIZER_GRAPH__` and dispatches a `code-visualizer:graph` event.

## Working on the frontend without the IDE

`npm run dev` and open http://localhost:5173 — with no plugin attached the app seeds
itself from the fixtures in [`src/demo`](src/demo), so both the code tree and the
architecture view render straight away. A picker in the view switcher swaps datasets:

| Fixture | URL | Covers |
| --- | --- | --- |
| TaskFlow | `?demo=taskflow` | 13 calls, 10 routes, FastAPI + Spring, 3 unresolved |
| Minimal | `?demo=minimal` | Smallest useful tree, one matched + one unresolved call |
| Empty | `?demo=empty` | `status: "empty"` state |
| Error | `?demo=error` | `status: "error"` state |

`?demo=off` disables seeding. Demo data is dev-only: it is skipped when the plugin
launched the page (it passes `projectName` / `projectPath`), it is replaced the moment
a real `code-visualizer:graph` event arrives, and the picker never renders in a
production build.

The fixtures mirror the `ProjectGraph` payload in `src/main/kotlin/ProjectFlowAnalyzer.kt`
— keep them in sync when that data model changes.

## Scripts

- `npm run dev` — dev server on port 5173
- `npm run build` — production build into `dist/`
- `npm run lint` — ESLint
