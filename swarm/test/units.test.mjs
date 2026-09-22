import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { callSites, locateRequest, templateToRegex, describeGraph } from '../lib/codemap.mjs'
import { judge, forbiddenLine } from '../lib/agent.mjs'
import { buildReport, toMarkdown, toText } from '../lib/report.mjs'
import { DEMO_MISSIONS, looksLikeDemoApp } from '../lib/missions.mjs'

const graph = JSON.parse(await readFile(new URL('../../visualizer-ui/src/demo/harbor.ready.json', import.meta.url), 'utf8'))
const mission = (id) => DEMO_MISSIONS.find((item) => item.id === id)
const noEvidence = () => ({ apiErrors: [], consoleErrors: [], pageErrors: [], requests: 0 })

test('templates match concrete paths segment by segment', () => {
  assert.ok(templateToRegex('/api/users/{userId}/settings').test('/api/users/3/settings'))
  assert.ok(templateToRegex('/api/users/:id').test('/api/users/42'))
  assert.ok(!templateToRegex('/api/users/{id}').test('/api/users/3/settings'))
  assert.ok(!templateToRegex('/api/orders').test('/api/orders/7'))
})

test('a request with no backend route is traced to the line that makes it', () => {
  const found = locateRequest(graph, 'PUT', 'http://localhost:5174/api/users/3/settings?x=1')
  assert.equal(found.missingBackend, true)
  assert.deepEqual(found.frontend, { file: 'web/src/Settings.tsx', line: 13 })
  assert.equal(found.backend, null)
})

test('a matched request is traced to both the caller and the handler', () => {
  const found = locateRequest(graph, 'post', '/api/orders')
  assert.equal(found.missingBackend, false)
  assert.deepEqual(found.frontend, { file: 'web/src/OrderPage.tsx', line: 16 })
  assert.equal(found.backend.file, 'backend/app/orders.py')
})

test('unknown requests and missing graphs locate nothing', () => {
  assert.equal(locateRequest(graph, 'GET', '/api/nope'), null)
  assert.equal(locateRequest(null, 'GET', '/api/users'), null)
  assert.deepEqual(callSites({}), [])
})

test('the planner map flags broken calls', () => {
  const text = describeGraph(graph)
  assert.match(text, /Settings\.tsx: PUT \/api\/users\/\{userId\}\/settings {2}\(NO BACKEND MATCH\)/)
  assert.ok(looksLikeDemoApp(text, ''))
  assert.ok(!looksLikeDemoApp('web/App.tsx: GET /api/things', 'Other app'))
})

test('nonsense on screen is found, ordinary words are not', () => {
  assert.equal(forbiddenLine('Hello\nOrder #undefined placed: undefined × undefined'), 'Order #undefined placed: undefined × undefined')
  assert.equal(forbiddenLine('Total: NaN'), 'Total: NaN')
  assert.equal(forbiddenLine('Undefined behaviour is documented here'), null)
})

test('a crash fails the run whatever the model claims', () => {
  const evidence = { ...noEvidence(), pageErrors: ['TypeError: boom'] }
  const verdict = judge({ mission: mission('manager'), text: '', evidence, claimed: { status: 'passed', summary: 'fine' }, steps: 2, maxSteps: 12 })
  assert.equal(verdict.status, 'failed')
  assert.equal(verdict.reason, 'crash')
})

test('a built-in journey passes only when the proof is on screen', () => {
  const passed = judge({ mission: mission('shopper'), text: 'Order #1042 placed: 2 × Cold brew', evidence: noEvidence(), claimed: null, steps: 4, maxSteps: 12 })
  assert.equal(passed.status, 'passed')
  assert.equal(passed.reached, 'Order #1042 placed')

  const overclaimed = judge({ mission: mission('settings'), text: 'Saving…', evidence: noEvidence(), claimed: { status: 'passed', summary: 'saved' }, steps: 3, maxSteps: 12 })
  assert.equal(overclaimed.status, 'failed')
  assert.equal(overclaimed.reason, 'not-reached')
})

test('a pass with failed requests on the way is only a warning', () => {
  const evidence = { ...noEvidence(), apiErrors: [{ method: 'GET', path: '/api/x', status: 500 }] }
  const verdict = judge({ mission: { ...mission('explorer'), script: null }, text: 'grace@harbor.market', evidence, claimed: { status: 'passed', summary: 'ok' }, steps: 2, maxSteps: 12 })
  assert.equal(verdict.status, 'warning')
})

test('the report names the failing request and where it lives', () => {
  const results = [
    { mission: mission('shopper'), verdict: { status: 'passed', reason: 'ok', reached: 'Order #1042 placed' }, evidence: noEvidence(), steps: 4, durationMs: 9000, mode: 'script', screenshot: null },
    {
      mission: mission('settings'),
      verdict: { status: 'failed', reason: 'not-reached', detail: 'Settings saved', reached: null },
      evidence: { ...noEvidence(), apiErrors: [{ method: 'PUT', path: '/api/users/3/settings', status: 404 }] },
      steps: 3, durationMs: 8000, mode: 'script', screenshot: null,
    },
  ]
  const report = buildReport({ results, target: 'http://localhost:5174', graph, brain: { model: 'scripted' }, startedAt: 0, finishedAt: 12_000 })

  assert.equal(report.passed, 1)
  assert.equal(report.failed, 1)
  const settings = report.agents[1]
  assert.equal(settings.line, 'Nothing happened: PUT /api/users/3/settings returned 404')
  assert.equal(settings.cause.missingBackend, true)
  assert.deepEqual(settings.cause.frontend, { file: 'web/src/Settings.tsx', line: 13 })

  const markdown = toMarkdown(report)
  assert.match(markdown, /# Swarm test: 1 \/ 5 passed|# Swarm test: 1 \/ 2 passed · 12s/)
  assert.match(markdown, /`web\/src\/Settings\.tsx:13`/)
  assert.match(toText(report), /→ web\/src\/Settings\.tsx:13/)
})
