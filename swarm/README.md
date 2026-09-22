# Nexus Swarm

Five AI agents test a running web app at the same time. Each one is a persona with a goal
("order two cold brews", "save my settings") driving its own real Chromium browser. The Swarm tab
in Nexus streams all five live, then shows a one-screen report. Every failure in the report is
traced to the line of code that made the failing request, through the Nexus map.

```
Nexus tab (React) ◄── server-sent events ── node run.mjs serve ── 5 × Playwright browser
        │  POST /run {target, graph}                │
        └── Kotlin (SwarmService) starts it and passes the port, the OpenAI key and the model
```

## How an agent works

1. It reads the page's accessibility tree, the failed network requests so far and what it already did.
2. The model (OpenAI, the key from Settings | Tools | Nexus) picks one action: click, fill, select, check, wait or done.
3. Playwright performs it. A drawn cursor and a ring show where it is acting.
4. The verdict comes from the page, not the model: a crash, or `undefined`/`NaN` on screen, fails the
   run whatever the model claims, and the built-in journeys must show their proof text.

Without an API key every agent follows a scripted journey, so the whole thing still runs offline. If
the model stops answering in the middle of a run, the agent switches to its script.

For apps other than the demo, the model first plans five missions from the Nexus map, preferring
calls that have no backend.

## Run it

```
npm install                 # once; also run `npx playwright install chromium` if browsers are missing
npm run serve               # control server on 7070, for the Swarm tab in `npm run dev`
npm run demo                # one run in the terminal against the demo app, prints the report
npm test                    # unit tests, plus a full run with a fake model if the demo app is up
```

`node run.mjs once --target <url> [--graph graph.json] [--headed] [--out dir]` runs any app.
`--headed` opens five real windows, tiled across the screen.

Output (report.json, report.md, screenshots, and the recording that "Replay last run" plays) goes to
`<project>/.idea/nexus-swarm/` when started from the IDE.
