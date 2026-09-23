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

## Testing your own app

For any app other than the demo, the swarm works like a small QA team given a brief:

1. **It reads the project.** `CLAUDE.md`, `AGENTS.md`, the READMEs (root and one folder down),
   `docs/*.md`, every `package.json`, and the page routes the code declares (React Router, Next,
   Nuxt, SvelteKit). The same files suggest the URL the app runs on, which fills the App URL box.
2. **It looks around.** A scout opens the home page and up to five pages its links lead to, and
   notes which ones have a password field.
3. **It plans.** The model writes down what the app is and who uses it, then invents five testers
   of that app with concrete goals, guided by the Nexus map (calls with NO BACKEND MATCH first) and
   by the optional "What should they test?" box. One is always a Chaos Monkey.
4. **It tests.** Each tester drives its own browser, signing in first when its goal needs an account.
5. **It reviews.** Every tester writes the review a real user would leave: stars, what worked, the
   problems (blocker, major or minor) and suggestions. A failed goal gets at most two stars, and the
   report adds an overall verdict and the average rating.

The pass/fail verdict still comes from the page (crashes, `undefined` on screen, failed requests),
never from the model's word alone. Testing your own app needs an OpenAI key; without one only the
Harbor Market demo runs, and any other app gets a clear message instead of demo journeys.

### Test account

Give a user name and password in the Swarm tab (or `--user`, with `--password` or
`NEXUS_SWARM_PASSWORD`, on the command line). The model is only told that an account exists and
types the placeholder `{{password}}`; the browser swaps in the real value as it fills the field.
The password never goes to OpenAI, is masked (`••••••`) in the tiles, and never reaches the replay
file or the report. The tab remembers the user name but not the password. Without an account,
testers who meet a sign-up form register a throwaway identity (`nexus.tester+…@example.com`).

## Run it

```
npm install                 # once; also run `npx playwright install chromium` if browsers are missing
npm run serve               # control server on 7070, for the Swarm tab in `npm run dev`
npm run demo                # one run in the terminal against the demo app, prints the report
npm test                    # unit tests, plus a full run with a fake model if the demo app is up
```

`node run.mjs once --target <url> [--project dir] [--user name --password secret] [--focus "checkout"] [--graph graph.json] [--headed] [--out dir]`
runs any app. In the IDE the project folder is passed for you.
`--headed` opens five real windows, tiled across the screen.

Output (report.json, report.md with every tester's review, screenshots, and the recording that "Replay last run" plays) goes to
`<project>/.idea/nexus-swarm/` when started from the IDE.
