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

1. It reads its test case, the current step, the page's accessibility tree, the failed network requests
   so far, and what each of its earlier actions changed.
2. The model (OpenAI, the key from Settings | Tools | Nexus) picks one action (click, fill, select,
   check, press, go to, back, scroll, wait) or reports the current step passed, quoting the proof, or failed.
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
4. **It tests, step by step.** Each tester gets a written test case: 2 to 6 numbered steps, each one
   small instruction ("Click \"Settings\" in the top bar") with an expected result it can see ("a
   message 'Settings saved'"). The runner keeps track of the current step, and after every action
   tells the tester what changed on the page: the new URL, what appeared, the requests it caused, or
   `NO VISIBLE CHANGE`. A step passes only when the tester quotes text that is really on the page.
   Repeating an action that changed nothing is refused, and a step with no result after 5 actions
   (or 3 actions in a row that changed nothing) fails with that reason. So a tester can no longer
   click the same button forever, and every failure names the step, what was expected and what the
   page showed instead.
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
npm run serve               # control server on 7070 (`npm run dev` in visualizer-ui starts it for you)
npm run demo                # one run in the terminal against the demo app, prints the report
npm test                    # unit tests, plus a full run with a fake model if the demo app is up
```

`npm run dev` (from the repository root, or in `visualizer-ui/`) starts this runner on 7070 alongside
the UI and stops it with the dev server. `NEXUS_SWARM_MODEL` and `NEXUS_SWARM_PROJECT` can be set in
the environment or in `visualizer-ui/.env.local` (git-ignored).

The OpenAI key is found without being pasted anywhere (`lib/key.mjs`), in this order:
`OPENAI_API_KEY`, `OPENAI_KEY`, the plugin's own `~/.code-visualizer/openai-key` file, then the tested
project's `.env.local` / `.env`. Inside the IDE the key from Settings | Tools | Nexus is passed in and
wins. The runner prints where its key came from, never the key.

`node run.mjs once --target <url> [--project dir] [--user name --password secret] [--focus "checkout"] [--graph graph.json] [--headed] [--out dir]`
runs any app. In the IDE the project folder is passed for you.
`--headed` opens five real windows, tiled across the screen.

Output (report.json, report.md with every tester's review, screenshots, and the recording that "Replay last run" plays) goes to
`<project>/.idea/nexus-swarm/` when started from the IDE.
