// Five agents, five browsers, one run. Each agent gets its own browser context (its own cookies
// and storage), they all start together, and the run ends with a report.

import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { runAgent, overlayScript, startScreencast } from './agent.mjs'
import { stepsFor } from './testcase.mjs'
import { DEMO_MISSIONS, planMissions, looksLikeDemoApp } from './missions.mjs'
import { describeGraph } from './codemap.mjs'
import { buildReport, polishReport, toMarkdown } from './report.mjs'
import { readProjectContext, contextSummary } from './context.mjs'
import { createAccount } from './account.mjs'

// One tint per agent. The tile border, the drawn cursor and the report row all share it.
export const AGENT_COLORS = ['#22c3a6', '#f5a524', '#8b7cf6', '#ef5f7a', '#4aa8ff']

const VIEWPORT = { width: 1100, height: 720 }

// Real windows are drawn at 60% so each one lays the app out like a laptop screen while five
// share one display. Chrome applies the factor to window size and position too, so the slots
// are given in unscaled pixels and divided here: 640 × 500 on screen, three across, two down.
const WINDOW_SCALE = 0.6

function windowSlot(index, { width = 640, height = 500, gap = 12 } = {}) {
  const row = Math.floor(index / 3)
  // The second row is shifted half a window right, matching the tile grid in the Swarm tab.
  const column = (index % 3) + (row > 0 ? 0.5 : 0)
  const unscale = (pixels) => Math.round(pixels / WINDOW_SCALE)
  return { x: unscale(column * width), y: unscale(row * (height + gap)), width: unscale(width), height: unscale(height) }
}

/**
 * Opens a browser, falling back to the one already on the machine.
 *
 * Playwright downloads its own Chromium and does not have a build for every OS it otherwise
 * runs on: on macOS 12 `npx playwright install chromium` exits saying so, and every run then
 * dies with "Executable doesn't exist at .../chrome-headless-shell". Chrome is on that machine
 * already, and `channel: 'chrome'` drives it, so the fallback costs nothing and is the
 * difference between the feature working and not existing there.
 *
 * The bundled build is still tried first: it is the version this was tested against, and a
 * machine that has it should use it.
 */
async function openBrowser(options) {
  try {
    return await chromium.launch(options)
  } catch (error) {
    if (!/Executable doesn't exist|please run the following command/i.test(String(error?.message))) throw error
    for (const channel of ['chrome', 'msedge']) {
      try {
        return await chromium.launch({ ...options, channel })
      } catch { /* try the next one, then report the original problem */ }
    }
    throw error
  }
}

async function launch(headed, index) {
  if (!headed) return openBrowser({ headless: true })
  const slot = windowSlot(index)
  return openBrowser({
    headless: false,
    args: [`--window-position=${slot.x},${slot.y}`, `--window-size=${slot.width},${slot.height}`, `--force-device-scale-factor=${WINDOW_SCALE}`],
  })
}

const SCOUT_PAGES = 6

async function readPage(page) {
  return {
    path: new URL(page.url()).pathname,
    title: await page.title().catch(() => ''),
    snapshot: (await page.locator('body').ariaSnapshot({ timeout: 3000 }).catch(() => '')).slice(0, 2500),
    text: await page.locator('body').innerText({ timeout: 3000 }).catch(() => ''),
    hasPassword: (await page.locator('input[type=password]').count().catch(() => 0)) > 0,
  }
}

const NAV_CONTROLS = 'nav button, nav [role=tab], header button, [role=navigation] button, [role=tablist] [role=tab], [role=menubar] [role=menuitem]'
const RISKY = /log ?out|sign ?out|delete|remove|reset|clear|pay|buy|checkout|submit/i

/**
 * Single-page apps often switch screens with buttons rather than links, which a link crawl never
 * sees. From the home page, click each navigation button once and record the screen it shows, so
 * the planner knows what is on "Order" or "Settings" instead of guessing.
 */
async function visitNavigation(page, target, pages) {
  await page.goto(target, { waitUntil: 'networkidle', timeout: 10_000 }).catch(() => {})
  const labels = [...new Set(
    (await page.locator(NAV_CONTROLS).allInnerTexts().catch(() => []))
      .map((label) => label.trim().replace(/\s+/g, ' '))
      .filter((label) => label && label.length <= 40 && !RISKY.test(label)),
  )].slice(0, 8)
  const known = new Set(pages.map((seen) => seen.snapshot))
  for (const label of labels) {
    if (pages.length >= SCOUT_PAGES + 4) break
    await page.goto(target, { waitUntil: 'networkidle', timeout: 10_000 }).catch(() => {})
    const control = page.locator(NAV_CONTROLS).filter({ hasText: label }).first()
    const clicked = await control.click({ timeout: 3000 }).then(() => true, () => false)
    if (!clicked) continue
    await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {})
    await page.waitForTimeout(300)
    const seen = await readPage(page)
    // A button that shows the screen already recorded (often the home view itself) adds nothing,
    // except the fact that it IS that screen, which is what stops a planner expecting a change.
    if (known.has(seen.snapshot)) {
      const same = pages.find((recorded) => recorded.snapshot === seen.snapshot)
      same.path = `${same.path} (also what "${label}" shows)`
      continue
    }
    known.add(seen.snapshot)
    pages.push({ ...seen, path: `${seen.path} after clicking "${label}"` })
  }
}

/**
 * Walks the home page and a few pages its own links lead to, like a tester's first look around,
 * so the planner knows which screens exist and where the sign-in form is.
 */
async function scout(browser, target, routes = []) {
  const context = await browser.newContext({ viewport: VIEWPORT })
  const page = await context.newPage()
  try {
    await page.goto(target, { waitUntil: 'networkidle', timeout: 15_000 })
  } catch (error) {
    await context.close().catch(() => {})
    return { snapshot: '', text: '', pages: [], ok: false, error: error.message.split('\n')[0] }
  }
  const origin = new URL(page.url()).origin
  const home = await readPage(page)
  const pages = [home]
  try {
    const links = await page.locator('a[href]').evaluateAll((anchors) => anchors.map((anchor) => anchor.href)).catch(() => [])
    const declared = routes.filter((route) => !/[:*[]/.test(route)).map((route) => new URL(route, origin).href)
    const queue = [...new Set([...links, ...declared])]
      .map((href) => { try { return new URL(href) } catch { return null } })
      .filter((url) => url && url.origin === origin && !/logout|signout|sign-out|delete/i.test(url.pathname))
      .map((url) => `${url.origin}${url.pathname}`)
    const seen = new Set([home.path])
    for (const href of queue) {
      if (pages.length >= SCOUT_PAGES) break
      const path = new URL(href).pathname
      if (seen.has(path)) continue
      seen.add(path)
      const ok = await page.goto(href, { waitUntil: 'networkidle', timeout: 8000 }).then(() => true, () => false)
      if (ok) pages.push(await readPage(page))
    }
    await visitNavigation(page, target, pages)
  } catch {
    // The crawl is a bonus; the home page alone is enough to plan from.
  } finally {
    await context.close().catch(() => {})
  }
  return { snapshot: home.snapshot, text: home.text, pages: pages.map(({ text, ...rest }) => rest), ok: true }
}

/**
 * Chooses the five missions. The model plans them from the Nexus map when it can; the built-in
 * journeys cover the demo app, and are also the fallback when planning fails.
 */
async function chooseMissions({ brain, graph, home, plan, emit, context, account, focus }) {
  const graphText = describeGraph(graph)
  const demo = looksLikeDemoApp(graphText, home.text)
  const wantsPlan = plan === true || (plan !== false && !demo)
  if (wantsPlan && brain.canThink) {
    const read = context.docs.length ? `Read ${context.docs.map((doc) => doc.file).join(', ')}; planning 5 testers` : 'Reading the Nexus map and planning 5 testers'
    emit({ type: 'run', phase: 'planning', message: read })
    const planned = await planMissions(brain, { graphText, homeSnapshot: home.snapshot, context, pages: home.pages, account, focus })
    if (planned) return { missions: planned.missions, app: planned.app, source: 'planned' }
    if (!demo) throw new Error('The model could not plan tests for this app. Check the OpenAI key and model in Settings | Tools | Nexus.')
  }
  if (!demo) {
    // The scripted journeys only know the demo shop; on any other app they would test nothing real.
    throw new Error('Testing your own app needs an OpenAI key (Settings | Tools | Nexus). Without one only the Harbor Market demo can run.')
  }
  return { missions: DEMO_MISSIONS, app: '', source: 'built-in' }
}

/**
 * Runs the swarm. [emit] gets every live event; the resolved value is the report. [signal]
 * stops it early (all browsers close, finished agents still count).
 */
export async function runSwarm({ target, graph = null, headed = false, plan = 'auto', brain, emit, outDir = null, pace, maxSteps, signal, projectPath = null, credentials = null, focus = '' }) {
  const startedAt = Date.now()
  const browsers = []
  const stopped = () => Boolean(signal?.aborted)
  const closeAll = () => Promise.all(browsers.map((browser) => browser.close().catch(() => {})))
  signal?.addEventListener('abort', () => { closeAll() }, { once: true })

  emit({ type: 'run', phase: 'starting', target, brain: brain.model, headed })

  try {
    const context = await readProjectContext(projectPath || graph?.projectPath || null)
    const account = createAccount(credentials ?? {}, startedAt)
    emit({ type: 'run', phase: 'scouting', message: `Reading the project and looking around ${target}`, project: contextSummary(context) })

    const scoutBrowser = await openBrowser({ headless: true })
    const home = await scout(scoutBrowser, target, context.routes).finally(() => scoutBrowser.close().catch(() => {}))
    if (!home.ok) {
      const message = `Could not open ${target}. Is the app running? (${home.error})`
      emit({ type: 'run', phase: 'error', message })
      throw new Error(message)
    }

    let chosen
    try {
      chosen = await chooseMissions({ brain, graph, home, plan, emit, context, account, focus })
    } catch (error) {
      emit({ type: 'run', phase: 'error', message: error.message })
      throw error
    }
    const { missions, source, app } = chosen
    emit({
      type: 'missions',
      source,
      app,
      missions: missions.map((mission, index) => ({
        id: mission.id, persona: mission.persona, emoji: mission.emoji, goal: mission.goal, color: AGENT_COLORS[index % AGENT_COLORS.length],
        steps: stepsFor(mission),
      })),
    })
    emit({ type: 'run', phase: 'running', message: target })

    // Headless: one browser, five isolated contexts. Headed: five real windows, tiled.
    const shared = headed ? null : await launch(false, 0)
    if (shared) browsers.push(shared)

    const results = await Promise.all(missions.map(async (mission, index) => {
      const color = AGENT_COLORS[index % AGENT_COLORS.length]
      const browser = shared ?? await launch(true, index)
      if (!shared) browsers.push(browser)
      // A real window sizes its own page; a fixed viewport would be clipped by the window.
      const context = await browser.newContext({ viewport: shared ? VIEWPORT : null })
      await context.addInitScript(overlayScript(color))
      const page = await context.newPage()
      const stopCast = await startScreencast(page, (data) => emit({ type: 'frame', id: mission.id, data }))
        .catch(() => async () => {})
      try {
        return await runAgent({ mission, page, target, brain, emit, pace, maxSteps, isStopped: stopped, account, app })
      } catch (error) {
        // A closed browser (stop pressed) or an unexpected Playwright error ends this agent only.
        const message = error.message.split('\n')[0]
        emit({ type: 'agent', id: mission.id, status: 'failed', thought: stopped() ? 'Stopped.' : message, action: '' })
        return {
          mission,
          verdict: { status: 'failed', reason: 'blocked', detail: stopped() ? 'Stopped before finishing' : message, reached: null },
          evidence: { apiErrors: [], consoleErrors: [], pageErrors: [], requests: 0 },
          steps: 0,
          testSteps: stepsFor(mission).map((step, stepIndex) => ({ ...step, status: stepIndex === 0 ? 'failed' : 'skipped', observed: stepIndex === 0 ? message : null })),
          durationMs: Date.now() - startedAt,
          mode: 'error',
          screenshot: null,
        }
      } finally {
        await stopCast().catch(() => {})
        // A real window stays up until the whole swarm is done, so the audience can still see
        // the crash or the error; the browsers all close together at the end.
        if (shared) await context.close().catch(() => {})
      }
    }))

    // Real windows linger for a moment on their final screens; then every browser closes before
    // the run is announced as done, so "Replay last run" is ready the moment it appears.
    if (headed && !stopped()) await new Promise((resolve) => setTimeout(resolve, 5000))
    await closeAll()

    emit({ type: 'run', phase: 'reporting', message: 'Writing the report' })
    const finishedAt = Date.now()
    const report = await polishReport(buildReport({ results, target, graph, brain, startedAt, finishedAt }), brain)
    report.missionSource = source
    report.app = app
    report.account = account.username || null
    report.focus = focus?.trim() || null
    report.project = contextSummary(context)
    if (outDir) await saveReport(outDir, report).catch(() => {})
    emit({ type: 'report', report })
    emit({ type: 'run', phase: stopped() ? 'stopped' : 'done', message: `${report.passed} / ${report.total} passed` })
    return report
  } finally {
    await closeAll()
  }
}

/** report.json, report.md and one screenshot per agent, next to the replay log. */
async function saveReport(outDir, report) {
  await mkdir(join(outDir, 'screenshots'), { recursive: true })
  const slim = {
    ...report,
    agents: await Promise.all(report.agents.map(async (agent) => {
      if (!agent.screenshot) return { ...agent, screenshot: null }
      const file = join('screenshots', `${agent.id}.jpg`)
      await writeFile(join(outDir, file), Buffer.from(agent.screenshot, 'base64'))
      return { ...agent, screenshot: file.replace(/\\/g, '/') }
    })),
  }
  await writeFile(join(outDir, 'report.json'), JSON.stringify(slim, null, 2))
  await writeFile(join(outDir, 'report.md'), toMarkdown(report))
}
