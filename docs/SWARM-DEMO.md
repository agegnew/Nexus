# Swarm Test: demo guide

Five AI agents test the running app in parallel, live in the Nexus tool window, then write a
one-screen report. Each failure links to the line of code behind it.

## Before going on stage (once per machine)

```
cd swarm && npm install && npx playwright install chromium
cd demo/nexus-demo-app && ./start.ps1          # Harbor Market on http://localhost:5174
./gradlew runIde                                 # then open demo/nexus-demo-app in the sandbox IDE
```

- Put an OpenAI key in **Settings | Tools | Nexus** (or `OPENAI_API_KEY`). Without one the agents
  follow scripted journeys. That works and finds the same bugs, but the tiles say "Scripted".
- Open **Nexus → Map tab → Swarm**, press **Run 5 agents** once to warm it up. This also records
  the run that **Replay last run** plays if Wi-Fi or the API fails on stage.

## The 60-second script

1. "Nexus already knows this app's map: 6 calls, 2 of them go nowhere." (Map / Architecture tab)
2. "Instead of writing tests, I send five users at it." Press **Run 5 agents**.
3. The five tiles go live: Shopper, Explorer, Regular, Manager, Chaos Monkey, each with its own
   cursor, thought bubble and live API calls. Click a tile to enlarge it.
4. Tiles stamp green or red. Manager's page crashes. Chaos Monkey gets "Order #undefined".
5. The report slides in: **2 / 5 passed**. Click **Caller Settings.tsx:13** and the IDE jumps to the
   exact line that calls an endpoint the backend never declared.

## Fallbacks

| Problem | Do this |
|---|---|
| API or Wi-Fi down | **Replay last run**: the recorded run plays back, frames included |
| Want real browsers on screen | Tick **Real windows**: five Chromium windows tile across the display |
| Tab says "Runner offline" | `npm install` in `swarm/` was skipped, or Node is not on PATH; the message says which |
| Nothing in the IDE works | `cd visualizer-ui && npm run dev`, `cd swarm && npm run serve`, open `http://localhost:5173/?demo=harbor&view=swarm` |

## Results

The IDE writes each run to `demo/nexus-demo-app/.idea/nexus-swarm/`: `report.md`, `report.json`,
one screenshot per agent, and `last-run.jsonl` (the replay).
