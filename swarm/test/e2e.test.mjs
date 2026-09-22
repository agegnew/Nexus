// A whole run against the Harbor Market demo app, with a stand-in for OpenAI so the model path
// (agent decisions, report polishing) is exercised without a key. The stand-in claims success
// for every agent; the test proves the verdicts still come from what the page actually did.
//
// Needs the demo app on http://localhost:5174 (see demo/nexus-demo-app/README.md); skipped otherwise.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { readFile, mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const TARGET = process.env.NEXUS_SWARM_TARGET || 'http://localhost:5174'
const appUp = await fetch(TARGET).then((response) => response.ok, () => false)

function fakeOpenAi(missions) {
  const calls = { agent: 0, polish: 0 }
  const server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    const system = body.messages[0].content
    const user = body.messages[1].content
    let reply

    if (system.startsWith('You are a user testing')) {
      calls.agent += 1
      const persona = user.match(/^Persona: (.+)$/m)[1]
      const mission = missions.find((item) => item.persona === persona)
      const done = user.includes('(nothing yet)') ? 0 : (user.split('What you did so far:\n')[1].split('\n\n')[0].match(/^\d+\./gm) ?? []).length
      const next = mission.script[done]
      reply = next
        ? { thought: next.thought, action: next }
        : { thought: 'Looks good to me.', action: { type: 'done', status: 'passed', summary: 'Everything worked.' } }
    } else if (system.startsWith('You write the summary')) {
      calls.polish += 1
      const facts = JSON.parse(user)
      reply = { headline: `${facts.passed} of ${facts.total} journeys work`, lines: Object.fromEntries(facts.agents.map((agent) => [agent.id, `${agent.persona}: ${agent.status}`])) }
    } else {
      reply = {}
    }

    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify(reply) } }] }))
  })
  return { server, calls }
}

test('five agents run in parallel and the page, not the model, decides the verdict', { skip: !appUp && `demo app not running on ${TARGET}`, timeout: 120_000 }, async () => {
  const { DEMO_MISSIONS } = await import('../lib/missions.mjs')
  const { server, calls } = fakeOpenAi(DEMO_MISSIONS)
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  process.env.NEXUS_SWARM_BASE_URL = `http://127.0.0.1:${server.address().port}`

  const { createBrain } = await import('../lib/brain.mjs')
  const { runSwarm } = await import('../lib/swarm.mjs')
  const graph = JSON.parse(await readFile(new URL('../../visualizer-ui/src/demo/harbor.ready.json', import.meta.url), 'utf8'))
  const outDir = await mkdtemp(join(tmpdir(), 'nexus-swarm-e2e-'))
  const events = []

  try {
    const report = await runSwarm({
      target: TARGET,
      graph,
      brain: createBrain({ apiKey: 'test-key', model: 'fake' }),
      emit: (event) => events.push(event),
      outDir,
      pace: 120,
    })

    const status = Object.fromEntries(report.agents.map((agent) => [agent.id, agent.status]))
    assert.deepEqual(status, { shopper: 'passed', explorer: 'passed', settings: 'failed', manager: 'failed', chaos: 'failed' })
    assert.equal(report.passed, 2)
    assert.equal(report.headline, '2 of 5 journeys work')

    const byId = Object.fromEntries(report.agents.map((agent) => [agent.id, agent]))
    assert.equal(byId.settings.cause.path, '/api/users/3/settings')
    assert.equal(byId.settings.cause.missingBackend, true)
    assert.equal(byId.manager.reason, 'crash')
    assert.equal(byId.manager.cause.frontend.file, 'web/src/Analytics.tsx')
    assert.equal(byId.chaos.reason, 'nonsense')
    assert.ok(byId.chaos.screenshot, 'every agent leaves a last screenshot')

    assert.ok(calls.agent >= 10, 'agents asked the model for their moves')
    assert.equal(calls.polish, 1)
    assert.ok(events.some((event) => event.type === 'frame'), 'tiles received live frames')
    assert.ok(events.filter((event) => event.type === 'agent' && event.status === 'running').length >= 10)

    const markdown = await readFile(join(outDir, 'report.md'), 'utf8')
    assert.match(markdown, /2 \/ 5 passed/)
  } finally {
    server.close()
    delete process.env.NEXUS_SWARM_BASE_URL
  }
})
