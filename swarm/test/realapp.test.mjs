// The pieces that let the swarm test a real project: the brief it reads, the test account it
// signs in with (without ever showing the password), and the reviews the testers write.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { readProjectContext, routesFrom, suggestTarget, contextSummary } from '../lib/context.mjs'
import { createAccount } from '../lib/account.mjs'
import { normalizeReview, writeReview } from '../lib/review.mjs'
import { planMissions } from '../lib/missions.mjs'
import { buildReport, toMarkdown } from '../lib/report.mjs'

async function fixtureProject() {
  const root = await mkdtemp(join(tmpdir(), 'nexus-swarm-project-'))
  await writeFile(join(root, 'CLAUDE.md'), '# Bookly\nA library app. Members sign in and borrow books.\n')
  await writeFile(join(root, 'README.md'), '# Bookly\nRun `npm run dev`, then open http://localhost:5173.\nThe API runs on http://localhost:8000.\n')
  await mkdir(join(root, 'web', 'src'), { recursive: true })
  await writeFile(join(root, 'web', 'package.json'), JSON.stringify({ name: 'bookly-web', scripts: { dev: 'vite' }, dependencies: { react: '1', 'react-router-dom': '1' } }))
  await writeFile(join(root, 'web', 'src', 'routes.tsx'), "const routes = [{ path: '/login' }, { path: '/books/:id' }, { path: '/api/x' }]\n<Route path=\"/account\" />")
  await mkdir(join(root, 'web', 'node_modules', 'junk'), { recursive: true })
  await writeFile(join(root, 'web', 'node_modules', 'junk', 'README.md'), 'must never be read')
  return root
}

test('the brief puts CLAUDE.md first, finds the router pages, and skips node_modules', async () => {
  const context = await readProjectContext(await fixtureProject())
  assert.deepEqual(context.docs.map((doc) => doc.file), ['CLAUDE.md', 'README.md'])
  assert.deepEqual(context.routes, ['/account', '/books/:id', '/login'])
  assert.equal(context.suggestedTarget, 'http://localhost:5173', 'the web port wins over the API port')
  assert.match(context.text, /Members sign in and borrow books/)
  assert.doesNotMatch(context.text, /must never be read/)
  assert.deepEqual(contextSummary(context).docs, ['CLAUDE.md', 'README.md'])
})

test('a missing project gives an empty but usable brief', async () => {
  const context = await readProjectContext(join(tmpdir(), 'no-such-project-here'))
  assert.equal(context.text, '')
  assert.deepEqual(context.routes, [])
})

test('file-based routers become pages', () => {
  assert.deepEqual(routesFrom(['app/page.tsx', 'app/(shop)/cart/page.tsx', 'pages/about.tsx', 'pages/api/x.ts', 'pages/_app.tsx']), ['/', '/about', '/cart'])
  assert.equal(suggestTarget(['"dev": "next dev"']), 'http://localhost:3000')
  assert.equal(suggestTarget(['nothing here']), null)
})

test('the password only ever exists inside the browser', () => {
  const account = createAccount({ username: 'ada@example.com', password: 's3cret!' }, 123456)
  assert.equal(account.hasLogin, true)
  assert.equal(account.resolve('{{password}}'), 's3cret!')
  assert.equal(account.resolve('{{ username }}'), 'ada@example.com')
  assert.match(account.resolve('{{new_email}}'), /^nexus\.tester\+123456@example\.com$/)
  assert.doesNotMatch(account.brief(), /s3cret/)
  assert.match(account.brief(), /\{\{password\}\}/)
  assert.equal(account.mask('type "{{password}}" into "Password"'), 'type "••••••" into "Password"')
  assert.equal(account.mask('I typed s3cret! there'), 'I typed •••••• there')
})

test('reviews are cleaned and kept honest against the verdict', () => {
  const review = normalizeReview({
    rating: 5,
    title: 'Great',
    review: 'Loved it.',
    worked: ['search', 3, ''],
    problems: ['plain string', { severity: 'weird', text: 'odd severity' }, { text: '' }],
    suggestions: ['add a spinner'],
  }, 'failed')
  assert.equal(review.rating, 2, 'a failed goal is at most two stars')
  assert.deepEqual(review.worked, ['search'])
  assert.deepEqual(review.problems, [{ severity: 'minor', text: 'plain string' }, { severity: 'minor', text: 'odd severity' }])
  assert.equal(normalizeReview({ rating: 4 }, 'passed'), null)
  assert.equal(normalizeReview({ review: 'ok', rating: 'x' }, 'warning').rating, 3)
})

test('the reviewer never sees the password, even if the page echoed it', async () => {
  let seen = ''
  const brain = { canThink: true, json: async (system, user) => { seen = user; return { rating: 4, review: 'Signed in fine.' } } }
  const account = createAccount({ username: 'ada', password: 'hunter22' })
  const review = await writeReview(brain, {
    mission: { persona: 'Member', goal: 'Sign in' },
    app: 'a library',
    journal: [{ what: account.mask('type "{{password}}" into "Password"'), thought: 'typing' }],
    notes: [],
    verdict: { status: 'passed', reason: 'ok' },
    evidence: { apiErrors: [], pageErrors: [], consoleErrors: [] },
    finalText: 'Welcome ada, your password hunter22 is weak',
    mask: account.mask,
  })
  assert.equal(review.rating, 4)
  assert.doesNotMatch(seen, /hunter22/)
})

test('the planner gets the docs, the crawl and the account, but no password', async () => {
  let prompt = ''
  const missions = Array.from({ length: 5 }, (_, index) => ({ id: `m ${index}`, persona: 'Member', goal: 'Borrow a book', login: index < 2, expect: '(' }))
  const brain = { canThink: true, json: async (system, user) => { prompt = user; return { app: 'A library for members.', missions } } }
  const planned = await planMissions(brain, {
    graphText: 'web/src/api.ts: GET /api/books',
    homeSnapshot: '',
    context: { text: 'Bookly: members borrow books' },
    pages: [{ path: '/login', title: 'Sign in', snapshot: '- textbox "Email"', hasPassword: true }],
    account: createAccount({ username: 'ada', password: 'hunter22' }),
    focus: 'borrowing',
  })
  assert.match(prompt, /Bookly: members borrow books/)
  assert.match(prompt, /\/login \(Sign in\) \[has a password field\]/)
  assert.match(prompt, /yes, username "ada"/)
  assert.match(prompt, /borrowing/)
  assert.doesNotMatch(prompt, /hunter22/)
  assert.equal(planned.app, 'A library for members.')
  assert.deepEqual(planned.missions.map((mission) => mission.id), ['m-0', 'm-1', 'm-2', 'm-3', 'm-4'])
  assert.equal(planned.missions[0].maxSteps, 20, 'signing in gets a bigger step budget')
  assert.equal(planned.missions[0].expect, null, 'an invalid regex is dropped')
})

test('the report carries each review and the average rating', () => {
  const review = { rating: 2, title: 'Could not borrow', review: 'The button did nothing.', worked: ['sign in'], problems: [{ severity: 'blocker', text: 'Borrow does nothing' }], suggestions: ['Show an error'] }
  const results = [
    { mission: { id: 'a', persona: 'Member', emoji: '📚', goal: 'Borrow' }, verdict: { status: 'failed', reason: 'blocked', detail: 'no' }, evidence: { apiErrors: [], pageErrors: [], consoleErrors: [] }, steps: 5, durationMs: 1, mode: 'model', review, screenshot: null },
    { mission: { id: 'b', persona: 'Guest', emoji: '👀', goal: 'Browse' }, verdict: { status: 'passed', reason: 'ok' }, evidence: { apiErrors: [], pageErrors: [], consoleErrors: [] }, steps: 3, durationMs: 1, mode: 'model', review: { ...review, rating: 5, problems: [] }, screenshot: null },
  ]
  const report = buildReport({ results, target: 'http://localhost:5173', graph: null, brain: { model: 'gpt' }, startedAt: 0, finishedAt: 1000 })
  assert.equal(report.rating, 3.5)
  const markdown = toMarkdown({ ...report, account: 'ada', overall: 'Not ready yet.' })
  assert.match(markdown, /## What the testers said/)
  assert.match(markdown, /★★☆☆☆ · Could not borrow/)
  assert.match(markdown, /\*\*blocker\*\*: Borrow does nothing/)
  assert.match(markdown, /Signed in as ada/)
})
