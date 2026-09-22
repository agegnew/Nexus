// One agent: a persona with a goal, driving one real browser page until it succeeds, gives up,
// or runs out of steps. Everything it sees is recorded as evidence for the verdict.

import { AGENT_SYSTEM } from './brain.mjs'
import { UNIVERSAL_FORBID } from './missions.mjs'
import { pathOf } from './codemap.mjs'

const MAX_SNAPSHOT = 6000

/**
 * Drawn inside every page the agents drive: a cursor that follows the mouse and a ring that
 * marks the element about to be used. Headless browsers have no cursor of their own, and
 * without one the live tiles look like pages changing by themselves.
 */
export function overlayScript(color) {
  const install = (tint) => {
    const mount = () => {
      if (document.getElementById('__nexus_cursor')) return
      const cursor = document.createElement('div')
      cursor.id = '__nexus_cursor'
      cursor.innerHTML = `<svg width="26" height="26" viewBox="0 0 24 24"><path d="M3 2l7 19 2.6-7.4L20 11z" fill="${tint}" stroke="#fff" stroke-width="1.6" stroke-linejoin="round"/></svg>`
      Object.assign(cursor.style, {
        position: 'fixed', left: '0', top: '0', zIndex: '2147483647', pointerEvents: 'none',
        transform: 'translate(-40px,-40px)', filter: 'drop-shadow(0 2px 3px rgba(0,0,0,.35))',
      })
      document.documentElement.appendChild(cursor)
      addEventListener('mousemove', (event) => {
        cursor.style.transform = `translate(${event.clientX - 3}px, ${event.clientY - 2}px)`
      }, true)
      addEventListener('mousedown', (event) => {
        const ripple = document.createElement('div')
        Object.assign(ripple.style, {
          position: 'fixed', left: `${event.clientX - 18}px`, top: `${event.clientY - 18}px`, width: '36px', height: '36px',
          borderRadius: '50%', border: `3px solid ${tint}`, zIndex: '2147483646', pointerEvents: 'none',
          transition: 'transform .45s ease-out, opacity .45s ease-out', opacity: '1',
        })
        document.documentElement.appendChild(ripple)
        requestAnimationFrame(() => { ripple.style.transform = 'scale(1.9)'; ripple.style.opacity = '0' })
        setTimeout(() => ripple.remove(), 500)
      }, true)
    }
    window.__nexusRing = (x, y, width, height) => {
      const ring = document.createElement('div')
      Object.assign(ring.style, {
        position: 'fixed', left: `${x - 4}px`, top: `${y - 4}px`, width: `${width + 8}px`, height: `${height + 8}px`,
        borderRadius: '8px', outline: `3px solid ${tint}`, boxShadow: `0 0 0 6px ${tint}33`,
        zIndex: '2147483646', pointerEvents: 'none', transition: 'opacity .4s', opacity: '1',
      })
      document.documentElement.appendChild(ring)
      setTimeout(() => { ring.style.opacity = '0' }, 700)
      setTimeout(() => ring.remove(), 1100)
    }
    if (document.readyState === 'loading') addEventListener('DOMContentLoaded', mount)
    else mount()
  }
  return { content: `(${install.toString()})(${JSON.stringify(color)})` }
}

/**
 * Streams the page as JPEG frames through Chrome's screencast. Frames are throttled to [fps],
 * but the newest one is always delivered eventually, so a tile never freezes one frame early.
 */
export async function startScreencast(page, onFrame, { fps = 6, maxWidth = 720, maxHeight = 480 } = {}) {
  const cdp = await page.context().newCDPSession(page)
  const interval = 1000 / fps
  let lastSent = 0
  let pending = null
  let timer = null

  const flush = () => {
    timer = null
    if (!pending) return
    lastSent = Date.now()
    const data = pending
    pending = null
    onFrame(data)
  }

  cdp.on('Page.screencastFrame', ({ data, sessionId }) => {
    cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {})
    pending = data
    const wait = interval - (Date.now() - lastSent)
    if (wait <= 0) flush()
    else if (!timer) timer = setTimeout(flush, wait)
  })
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 62, maxWidth, maxHeight, everyNthFrame: 1 })

  return async () => {
    if (timer) clearTimeout(timer)
    flush()
    await cdp.send('Page.stopScreencast').catch(() => {})
    await cdp.detach().catch(() => {})
  }
}

/** What the agent can see right now. */
async function observe(page) {
  const url = page.url()
  const snapshot = await page.locator('body').ariaSnapshot({ timeout: 2500 }).catch(() => '')
  const text = await page.locator('body').innerText({ timeout: 2500 }).catch(() => '')
  return {
    url,
    snapshot: snapshot.trim() ? snapshot.slice(0, MAX_SNAPSHOT) : '(the page is completely empty)',
    text: text.slice(0, 4000),
  }
}

/** Finds the element an action refers to, trying the exact accessible name before a loose one. */
async function locate(page, action) {
  const name = String(action.name ?? '')
  const roles = {
    fill: ['textbox', 'spinbutton', 'searchbox'],
    select: ['combobox', 'listbox'],
    check: ['checkbox', 'switch'],
  }[action.type] ?? [action.role, 'button', 'link', 'tab', 'menuitem'].filter(Boolean)

  const candidates = []
  for (const exact of [true, false]) {
    for (const role of roles) candidates.push(page.getByRole(role, { name, exact }))
  }
  if (action.type !== 'click') candidates.push(page.getByLabel(name))
  else candidates.push(page.getByText(name, { exact: false }))

  for (const candidate of candidates) {
    if ((await candidate.count().catch(() => 0)) > 0) return candidate.first()
  }
  throw new Error(`Could not find ${action.role ?? action.type} "${name}"`)
}

/** Moves the drawn cursor to the element and rings it, so a watcher can follow along. */
async function point(page, element, cursor) {
  await element.scrollIntoViewIfNeeded({ timeout: 2000 }).catch(() => {})
  const box = await element.boundingBox()
  if (!box) return
  const x = box.x + box.width / 2
  const y = box.y + box.height / 2
  await page.mouse.move(cursor.x, cursor.y)
  await page.mouse.move(x, y, { steps: 14 })
  cursor.x = x
  cursor.y = y
  await page.evaluate(({ x: left, y: top, width, height }) => window.__nexusRing?.(left, top, width, height), box).catch(() => {})
  await page.waitForTimeout(260)
}

async function act(page, action, cursor) {
  switch (action.type) {
    case 'wait':
      await page.waitForTimeout(1400)
      return
    case 'goto':
      await page.goto(new URL(action.value || '/', page.url()).toString(), { waitUntil: 'domcontentloaded' })
      return
    case 'click': {
      const element = await locate(page, action)
      await point(page, element, cursor)
      await element.click({ timeout: 4000 })
      return
    }
    case 'fill': {
      const element = await locate(page, action)
      await point(page, element, cursor)
      await element.click({ timeout: 4000 })
      await element.fill('')
      if (action.value) await element.pressSequentially(String(action.value), { delay: 65 })
      return
    }
    case 'select': {
      const element = await locate(page, action)
      await point(page, element, cursor)
      const wanted = String(action.value ?? '').toLowerCase()
      const options = await element.locator('option').evaluateAll((items) =>
        items.map((item) => ({ value: item.value, label: item.textContent ?? '' })))
      const match = options.find((option) => option.value.toLowerCase() === wanted)
        ?? options.find((option) => option.label.toLowerCase().includes(wanted))
      if (!match) throw new Error(`No option "${action.value}"`)
      await element.selectOption(match.value)
      return
    }
    case 'check': {
      const element = await locate(page, action)
      await point(page, element, cursor)
      await element.setChecked(String(action.value) !== 'false', { timeout: 4000 })
      return
    }
    default:
      throw new Error(`Unknown action "${action.type}"`)
  }
}

function describe(action) {
  if (!action) return ''
  const name = action.name ? ` "${action.name}"` : ''
  switch (action.type) {
    case 'fill': return action.value ? `type "${action.value}" into${name}` : `clear${name}`
    case 'select': return `choose "${action.value}" in${name}`
    case 'check': return `${String(action.value) === 'false' ? 'untick' : 'tick'}${name}`
    case 'wait': return 'wait'
    case 'done': return action.status ? `done: ${action.status}` : 'check the result'
    default: return `${action.type}${name}`
  }
}

/** The first line of the page that contains a word no healthy screen shows. */
export function forbiddenLine(text) {
  const lines = text.split('\n').map((line) => line.trim()).filter(Boolean)
  for (const word of UNIVERSAL_FORBID) {
    const pattern = new RegExp(`(^|[^A-Za-z])${word.replace(/[[\]]/g, '\\$&')}([^A-Za-z]|$)`)
    const line = lines.find((candidate) => pattern.test(candidate))
    if (line) return line.slice(0, 90)
  }
  return null
}

/**
 * The verdict, grounded in what the page and the network actually did. The model's own claim is
 * only the tie-breaker: a crash or nonsense on screen fails the run whatever it says.
 */
export function judge({ mission, text, evidence, claimed, steps, maxSteps }) {
  const expected = mission.expect ? new RegExp(mission.expect, 'i') : null
  const reached = expected ? text.match(expected)?.[0] ?? null : null
  const base = { reached, apiErrors: evidence.apiErrors.length }

  if (evidence.pageErrors.length > 0) {
    return { ...base, status: 'failed', reason: 'crash', detail: evidence.pageErrors[0] }
  }
  const nonsense = forbiddenLine(text)
  if (nonsense) return { ...base, status: 'failed', reason: 'nonsense', detail: nonsense }

  if (claimed) {
    if (claimed.status === 'passed' && expected && !reached && mission.script) {
      return { ...base, status: 'failed', reason: 'not-reached', detail: mission.expect }
    }
    if (claimed.status === 'failed') {
      return { ...base, status: 'failed', reason: steps >= maxSteps ? 'gave-up' : 'blocked', detail: claimed.summary }
    }
    if (claimed.status === 'passed') {
      return { ...base, status: evidence.apiErrors.length ? 'warning' : 'passed', reason: 'ok', detail: claimed.summary }
    }
  }

  if (expected && !reached) return { ...base, status: 'failed', reason: 'not-reached', detail: mission.expect }
  if (!expected && steps >= maxSteps) return { ...base, status: 'failed', reason: 'gave-up', detail: null }
  return { ...base, status: evidence.apiErrors.length ? 'warning' : 'passed', reason: 'ok', detail: claimed?.summary ?? null }
}

function historyText(history) {
  if (history.length === 0) return '(nothing yet)'
  return history.slice(-8).map((entry, index) => `${index + 1}. ${entry.what}${entry.error ? ` -> FAILED: ${entry.error}` : ' -> ok'}`).join('\n')
}

/**
 * Runs one mission to a verdict. [emit] receives progress events for the live tiles; the
 * return value is everything the report needs.
 */
export async function runAgent({ mission, page, target, brain, emit, maxSteps = 12, pace = 900, isStopped = () => false }) {
  const startedAt = Date.now()
  const evidence = { apiErrors: [], consoleErrors: [], pageErrors: [], requests: 0 }
  const history = []
  const cursor = { x: 40, y: 40 }
  let mode = brain.canThink ? 'model' : 'script'
  let scriptIndex = 0
  let claimed = null
  let steps = 0

  page.on('response', (response) => {
    const request = response.request()
    if (!['fetch', 'xhr'].includes(request.resourceType())) return
    evidence.requests += 1
    const record = { method: request.method(), path: pathOf(request.url()), status: response.status() }
    emit({ type: 'network', id: mission.id, ...record })
    if (record.status >= 400) evidence.apiErrors.push(record)
  })
  page.on('requestfailed', (request) => {
    if (!['fetch', 'xhr'].includes(request.resourceType())) return
    const record = { method: request.method(), path: pathOf(request.url()), status: 0 }
    evidence.apiErrors.push(record)
    emit({ type: 'network', id: mission.id, ...record })
  })
  page.on('console', (message) => {
    if (message.type() === 'error') evidence.consoleErrors.push(message.text().slice(0, 300))
  })
  page.on('pageerror', (error) => {
    evidence.pageErrors.push(`${error.name}: ${error.message}`.slice(0, 200))
    evidence.crashStack = evidence.crashStack ?? String(error.stack ?? '')
  })

  const say = (status, thought, action) => emit({
    type: 'agent', id: mission.id, status, step: steps, thought, action: describe(action),
  })

  say('running', 'Opening the app.')
  try {
    await page.goto(target, { waitUntil: 'networkidle', timeout: 15_000 })
  } catch (error) {
    evidence.pageErrors.push(`Could not open ${target}: ${error.message.split('\n')[0]}`)
  }
  await page.waitForTimeout(pace)

  // A crash ends the journey at once: there is nothing left to click, and the crash is the finding.
  while (steps < maxSteps && !isStopped() && evidence.pageErrors.length === 0) {
    const seen = await observe(page)
    let decision

    if (mode === 'model') {
      const errors = evidence.apiErrors.map((error) => `${error.method} ${error.path} -> HTTP ${error.status}`).join('\n') || 'none'
      try {
        decision = await brain.json(AGENT_SYSTEM, [
          `Persona: ${mission.persona}`,
          `Goal: ${mission.goal}`,
          `Step ${steps + 1} of ${maxSteps}. URL: ${seen.url}`,
          `What you did so far:\n${historyText(history)}`,
          `Failed network requests so far:\n${errors}`,
          `Accessibility tree:\n${seen.snapshot}`,
        ].join('\n\n'))
      } catch (error) {
        if (!mission.script) {
          claimed = { status: 'failed', summary: 'The model stopped answering.' }
          break
        }
        // The model went away mid-run; finish the journey from the script instead of dying on stage.
        mode = 'script'
        say('running', 'Model unavailable, switching to the scripted journey.')
        await page.goto(target, { waitUntil: 'networkidle', timeout: 15_000 }).catch(() => {})
        history.length = 0
        continue
      }
    } else {
      const next = mission.script?.[scriptIndex++]
      decision = next
        ? { thought: next.thought, action: next }
        : { thought: 'That was the whole journey. Checking the result.', action: { type: 'done' } }
    }

    const action = decision?.action ?? { type: 'wait' }

    if (action.type === 'done') {
      if (mode === 'model') claimed = { status: action.status === 'passed' ? 'passed' : 'failed', summary: action.summary ?? decision.thought }
      say('running', decision.thought || 'Checking the result.', action)
      break
    }

    steps += 1
    say('running', decision.thought || '', action)
    try {
      await act(page, action, cursor)
      history.push({ what: describe(action) })
    } catch (error) {
      history.push({ what: describe(action), error: error.message.split('\n')[0].slice(0, 160) })
    }
    await page.waitForTimeout(pace)
  }

  await page.waitForTimeout(400)
  const final = await observe(page)
  const verdict = judge({ mission, text: final.text, evidence, claimed, steps, maxSteps })
  const screenshot = await page.screenshot({ type: 'jpeg', quality: 70 }).catch(() => null)

  emit({ type: 'agent', id: mission.id, status: verdict.status, step: steps, thought: verdictThought(verdict), action: '' })

  return {
    mission,
    verdict,
    evidence,
    steps,
    durationMs: Date.now() - startedAt,
    mode,
    claimed,
    finalUrl: final.url,
    screenshot: screenshot ? screenshot.toString('base64') : null,
  }
}

function verdictThought(verdict) {
  switch (verdict.reason) {
    case 'crash': return `The page crashed: ${verdict.detail}`
    case 'nonsense': return `The screen shows "${verdict.detail}"`
    case 'not-reached': return 'I never got what I came for.'
    case 'gave-up': return 'I ran out of ideas. Giving up.'
    case 'blocked': return verdict.detail || 'Blocked.'
    default: return verdict.reached ? `Done: "${verdict.reached}"` : (verdict.detail || 'Done.')
  }
}
