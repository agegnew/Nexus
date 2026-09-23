// A whole run against a small app with a real sign-in form, with a stand-in for OpenAI. It proves
// the path a real project takes: the planner is briefed from the project's CLAUDE.md, the agents
// sign in with the test account, the app receives the real password, and neither the model nor
// the live events (which the replay file records) ever see it. Every agent also leaves a review.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, writeFile, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const PASSWORD = 'correct-horse-42'

const PAGE = `<!doctype html><html><head><title>Bookly</title></head><body>
<nav><a href="/">Home</a> <a href="/login">Sign in</a></nav>
<main id="main"></main>
<script>
  const main = document.getElementById('main')
  if (location.pathname === '/login') {
    main.innerHTML = '<h1>Sign in</h1><form id="f"><label>Email <input id="e" name="email"></label>' +
      '<label>Password <input id="p" type="password" name="password"></label><button>Sign in</button></form><p id="m"></p>'
    document.getElementById('f').addEventListener('submit', async (event) => {
      event.preventDefault()
      const response = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: document.getElementById('e').value, password: document.getElementById('p').value }) })
      document.getElementById('m').textContent = response.ok ? 'Welcome back, ada! You have 2 books on loan.' : 'Wrong email or password'
    })
  } else {
    main.innerHTML = '<h1>Bookly</h1><p>Borrow books from your library.</p>'
  }
</script></body></html>`

function bookly() {
  const attempts = []
  const server = createServer(async (request, response) => {
    if (request.method === 'POST' && request.url === '/api/login') {
      const chunks = []
      for await (const chunk of request) chunks.push(chunk)
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      attempts.push(body)
      const ok = body.email === 'ada@bookly.test' && body.password === PASSWORD
      response.writeHead(ok ? 200 : 401, { 'Content-Type': 'application/json' })
      return response.end(JSON.stringify({ ok }))
    }
    response.writeHead(200, { 'Content-Type': 'text/html' })
    response.end(PAGE)
  })
  return { server, attempts }
}

// Every tester runs the same two-step test case. The model's side answers by the current step and
// how many actions it has used on it, the way the runner reports them.
const TEST_CASE = [
  { do: 'Click "Sign in" in the top bar', expect: 'A sign-in form with Email and Password' },
  { do: 'Type {{username}} and {{password}}, then click "Sign in"', expect: 'A welcome message' },
]
const MOVES = [
  [
    { thought: 'Sign in is in the top bar.', action: { type: 'click', role: 'link', name: 'Sign in' } },
    { thought: 'The form is there.', action: { type: 'step', result: 'passed', evidence: 'Sign in' } },
  ],
  [
    { thought: 'My email first.', action: { type: 'fill', name: 'Email', value: '{{username}}' } },
    { thought: 'Now the password.', note: 'The password field has no show/hide toggle.', action: { type: 'fill', name: 'Password', value: '{{password}}' } },
    { thought: 'Submit.', action: { type: 'click', role: 'button', name: 'Sign in' } },
    { thought: 'I am in.', action: { type: 'step', result: 'passed', evidence: 'Welcome back, ada!' } },
  ],
]

function fakeOpenAi() {
  const prompts = []
  const calls = { plan: 0, agent: 0, review: 0, polish: 0 }
  const server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const raw = Buffer.concat(chunks).toString('utf8')
    prompts.push(raw)
    const body = JSON.parse(raw)
    const system = body.messages[0].content
    const user = body.messages[1].content
    let reply = {}
    if (system.startsWith('You are the lead')) {
      calls.plan += 1
      reply = {
        app: 'Bookly, a library app where members sign in and borrow books.',
        missions: ['Member', 'Student', 'Parent', 'Teacher', 'Chaos Monkey'].map((persona, index) => ({
          id: `tester-${index}`, persona, emoji: '📚', goal: 'Sign in and see my loans.', login: true, expect: 'Welcome back', steps: TEST_CASE,
        })),
      }
    } else if (system.startsWith('You are a manual QA tester')) {
      calls.agent += 1
      const step = Number(user.match(/CURRENT STEP (\d+) of/)[1]) - 1
      const used = Number(user.match(/Actions used on this step: (\d+)/)[1])
      reply = MOVES[step][used] ?? MOVES[step].at(-1)
    } else if (system.startsWith('You just finished testing')) {
      calls.review += 1
      reply = { rating: 4, title: 'Smooth sign in', review: 'Signing in was quick and my loans showed right away.', worked: ['sign in'], problems: [{ severity: 'minor', text: 'No password toggle' }], suggestions: ['Add a show password button'] }
    } else if (system.startsWith('You write the summary')) {
      calls.polish += 1
      reply = { headline: 'All 5 testers signed in', overall: 'Sign in works for every tester.', lines: {} }
    }
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify(reply) } }] }))
  })
  return { server, prompts, calls }
}

const listen = (server) => new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`)))

test('testers are briefed from the docs, sign in for real, and never leak the password', { timeout: 180_000 }, async () => {
  const app = bookly()
  const model = fakeOpenAi()
  const target = await listen(app.server)
  process.env.NEXUS_SWARM_BASE_URL = await listen(model.server)

  const project = await mkdtemp(join(tmpdir(), 'nexus-swarm-bookly-'))
  await writeFile(join(project, 'CLAUDE.md'), '# Bookly\nMembers sign in with email and password, then see their loans.\n')
  const outDir = await mkdtemp(join(tmpdir(), 'nexus-swarm-login-'))
  const events = []

  try {
    const { createBrain } = await import('../lib/brain.mjs')
    const { runSwarm } = await import('../lib/swarm.mjs')
    const report = await runSwarm({
      target,
      brain: createBrain({ apiKey: 'test-key', model: 'fake' }),
      emit: (event) => events.push(event),
      outDir,
      pace: 60,
      projectPath: project,
      credentials: { username: 'ada@bookly.test', password: PASSWORD },
      focus: 'signing in',
    })

    assert.equal(model.calls.plan, 1)
    const planPrompt = model.prompts.find((prompt) => prompt.includes('You are the lead'))
    assert.match(planPrompt, /Members sign in with email and password/, 'the planner read CLAUDE.md')
    assert.match(planPrompt, /\[has a password field\]/, 'the scout found the sign-in page')

    assert.equal(report.passed, 5, JSON.stringify(report.agents.map((agent) => [agent.id, agent.status, agent.line])))
    assert.ok(app.attempts.length >= 5 && app.attempts.every((attempt) => attempt.password === PASSWORD), 'the app received the real password')

    for (const prompt of model.prompts) assert.ok(!prompt.includes(PASSWORD), 'the model never saw the password')
    assert.ok(!JSON.stringify(events).includes(PASSWORD), 'no live event (or replay line) holds the password')
    assert.ok(events.some((event) => event.type === 'agent' && /••••••/.test(event.action ?? '')), 'the tile shows a masked password')

    for (const agent of report.agents) {
      assert.deepEqual(agent.testSteps.map((step) => step.status), ['passed', 'passed'])
      assert.equal(agent.testSteps[1].observed, 'Welcome back, ada!', 'each step records what proved it')
    }
    const agentPrompt = model.prompts.find((prompt) => prompt.includes('CURRENT STEP 2 of 2'))
    assert.match(agentPrompt, /appeared: .*Sign in/, 'the tester is told what its last action changed')

    assert.equal(model.calls.review, 5)
    assert.ok(report.agents.every((agent) => agent.review?.rating === 4))
    assert.equal(report.rating, 4)
    assert.equal(report.overall, 'Sign in works for every tester.')
    assert.equal(report.account, 'ada@bookly.test')
    assert.deepEqual(report.project.docs, ['CLAUDE.md'])

    const markdown = await readFile(join(outDir, 'report.md'), 'utf8')
    assert.match(markdown, /What the testers said/)
    assert.ok(!markdown.includes(PASSWORD))
    const saved = await readFile(join(outDir, 'report.json'), 'utf8')
    assert.ok(!saved.includes(PASSWORD))
  } finally {
    app.server.close()
    model.server.close()
    delete process.env.NEXUS_SWARM_BASE_URL
  }
})

test('without a key, a real app is refused with a clear message instead of running demo journeys', { timeout: 60_000 }, async () => {
  const app = bookly()
  const target = await listen(app.server)
  const events = []
  try {
    const { createBrain } = await import('../lib/brain.mjs')
    const { runSwarm } = await import('../lib/swarm.mjs')
    await assert.rejects(
      runSwarm({ target, brain: createBrain(), emit: (event) => events.push(event) }),
      /needs an OpenAI key/,
    )
    assert.ok(events.some((event) => event.type === 'run' && event.phase === 'error'))
    assert.ok(!events.some((event) => event.type === 'missions'), 'no demo missions were sent at a real app')
  } finally {
    app.server.close()
  }
})

test('a tester clicking a dead button is stopped: the repeat is refused and the step fails with a reason', { timeout: 120_000 }, async () => {
  let clicks = 0
  const app = createServer((request, response) => {
    if (request.url === '/clicked') {
      clicks += 1
      response.writeHead(204)
      return response.end()
    }
    response.writeHead(200, { 'Content-Type': 'text/html' })
    response.end('<!doctype html><title>Notes</title><h1>Notes</h1><button onclick="navigator.sendBeacon(\'/clicked\')">Save</button>')
  })
  const prompts = []
  const model = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    const [system, user] = [body.messages[0].content, body.messages[1].content]
    let reply = {}
    if (system.startsWith('You are the lead')) {
      reply = {
        app: 'A notes app.',
        missions: [1, 2, 3, 4, 5].map((index) => ({ id: `t${index}`, persona: `Tester ${index}`, goal: 'Save a note', steps: [{ do: 'Click "Save"', expect: 'A message "Saved"' }] })),
      }
    } else if (system.startsWith('You are a manual QA tester')) {
      prompts.push(user)
      // The stubborn model: it only ever wants to click Save, and once claims a pass it cannot prove.
      reply = prompts.length === 2
        ? { thought: 'Looks saved.', action: { type: 'step', result: 'passed', evidence: 'Your note was saved' } }
        : { thought: 'Click Save again.', action: { type: 'click', role: 'button', name: 'Save' } }
    }
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify(reply) } }] }))
  })
  const target = await listen(app)
  process.env.NEXUS_SWARM_BASE_URL = await listen(model)
  try {
    const { createBrain } = await import('../lib/brain.mjs')
    const { runSwarm } = await import('../lib/swarm.mjs')
    const report = await runSwarm({ target, brain: createBrain({ apiKey: 'test-key', model: 'fake' }), emit: () => {}, pace: 60 })

    assert.equal(report.failed, 5)
    for (const agent of report.agents) {
      assert.equal(agent.testSteps[0].status, 'failed')
      assert.match(agent.testSteps[0].observed, /changed nothing|no result after/)
      assert.ok(agent.steps <= 2, `${agent.id} clicked ${agent.steps} times; repeats of a dead click must be refused`)
    }
    assert.ok(clicks <= 10, `the dead button was clicked ${clicks} times across 5 testers`)
    assert.ok(prompts.some((prompt) => /NO VISIBLE CHANGE/.test(prompt)), 'the tester was told its click changed nothing')
    assert.ok(prompts.some((prompt) => /REFUSED: you already did exactly this/.test(prompt)), 'the repeat was refused')
    assert.ok(prompts.some((prompt) => /REJECTED: "Your note was saved" is not on the page/.test(prompt)), 'an unproven pass was rejected')
  } finally {
    app.close()
    model.close()
    delete process.env.NEXUS_SWARM_BASE_URL
  }
})
