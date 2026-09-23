#!/usr/bin/env node
// Nexus Swarm: five AI agents test a running web app in parallel, then write a short report.
//
//   node run.mjs serve [--port 7070] [--out dir]
//       Control server for the Nexus tab. Prints NEXUS_SWARM_READY {"port":N} once listening.
//   node run.mjs once --target http://localhost:5174 [--graph graph.json] [--headed] [--out dir]
//                     [--project dir] [--user name --password secret] [--focus "checkout"]
//       One run from the terminal. Prints the report and exits 0 when every journey passed.
//
// --project (or NEXUS_SWARM_PROJECT) is the source folder: its README, CLAUDE.md and docs brief
// the planner. The password can also come from NEXUS_SWARM_PASSWORD, to keep it out of shell history.
//
// The model key comes from OPENAI_API_KEY, OPENAI_KEY, ~/.code-visualizer/openai-key (the plugin's
// own key file) or the tested project's .env, in that order (lib/key.mjs). Without one the agents
// follow their scripted journeys, so the demo still runs offline. NEXUS_SWARM_MODEL picks the model.

import { readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { createBrain } from './lib/brain.mjs'
import { createSwarmServer } from './lib/server.mjs'
import { runSwarm } from './lib/swarm.mjs'
import { toText } from './lib/report.mjs'
import { findKey } from './lib/key.mjs'

function parseArgs(argv) {
  const [command = 'serve', ...rest] = argv
  const options = { command }
  for (let index = 0; index < rest.length; index += 1) {
    const flag = rest[index]
    if (!flag.startsWith('--')) continue
    const key = flag.slice(2)
    const next = rest[index + 1]
    if (next === undefined || next.startsWith('--')) options[key] = true
    else { options[key] = next; index += 1 }
  }
  return options
}

const options = parseArgs(process.argv.slice(2))
const projectPath = typeof options.project === 'string' ? resolve(options.project) : process.env.NEXUS_SWARM_PROJECT || null
const found = findKey({ projectPath })
const brain = createBrain({ apiKey: found.key, model: process.env.NEXUS_SWARM_MODEL || '' })
brain.keySource = found.source
const outDir = resolve(options.out || join(tmpdir(), 'nexus-swarm'))
const pace = options.pace ? Number(options.pace) : undefined
const maxSteps = options['max-steps'] ? Number(options['max-steps']) : undefined

if (options.command === 'serve') {
  const server = createSwarmServer({ brain, outDir, pace, maxSteps, projectPath })
  const port = Number(options.port ?? 7070)
  server.on('error', (error) => {
    console.error(`NEXUS_SWARM_ERROR ${JSON.stringify({ message: error.message })}`)
    process.exit(1)
  })
  server.listen(port, '127.0.0.1', () => {
    console.log(`NEXUS_SWARM_READY ${JSON.stringify({ port: server.address().port, brain: brain.model, keySource: brain.keySource, outDir })}`)
    if (!brain.canThink) console.log('No OpenAI key found (OPENAI_API_KEY, ~/.code-visualizer/openai-key, or the project .env): only the demo can be tested.')
  })
  // The IDE closes stdin when it shuts the swarm down; exit rather than linger as an orphan.
  if (options['exit-with-stdin']) process.stdin.on('end', () => process.exit(0)).resume()
} else if (options.command === 'once') {
  if (!options.target) {
    console.error('once needs --target <url>')
    process.exit(2)
  }
  const graph = options.graph ? JSON.parse(await readFile(options.graph, 'utf8')) : null
  const report = await runSwarm({
    target: options.target,
    graph,
    headed: Boolean(options.headed),
    plan: options.plan === 'true' ? true : options.plan === 'false' ? false : 'auto',
    brain,
    outDir,
    pace,
    maxSteps,
    projectPath,
    credentials: typeof options.user === 'string'
      ? { username: options.user, password: typeof options.password === 'string' ? options.password : process.env.NEXUS_SWARM_PASSWORD || '' }
      : null,
    focus: typeof options.focus === 'string' ? options.focus : '',
    emit: (event) => {
      if (options.verbose && event.type === 'agent') console.log(`  [${event.id}] ${event.status} · ${event.action || ''} ${event.thought || ''}`)
      if (event.type === 'run' && event.message) console.log(`· ${event.message}`)
    },
  })
  console.log(`\n${toText(report)}\n\nReport: ${join(outDir, 'report.md')}`)
  process.exit(report.failed === 0 ? 0 : 1)
} else {
  console.error(`Unknown command "${options.command}". Use serve or once.`)
  process.exit(2)
}
