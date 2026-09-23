// One agent: a persona with a goal, driving one real browser page until it succeeds, gives up,
// or runs out of steps. Everything it sees is recorded as evidence for the verdict.

import { AGENT_SYSTEM } from './brain.mjs'
import { UNIVERSAL_FORBID } from './missions.mjs'
import { pathOf } from './codemap.mjs'
import { createAccount } from './account.mjs'
import { writeReview } from './review.mjs'
import { STEP_BUDGET, NO_CHANGE_LIMIT, stepsFor, freshStepResults, changeBetween, evidenceOnPage, sameAction, testCaseText, quotedProof } from './testcase.mjs'

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

/**
 * The current value of every visible form field. The accessibility tree leaves out what a
 * dropdown has selected and what a field holds, so without this a tester cannot see its own
 * typing or choice take effect. Password fields only say whether they are filled.
 */
async function formState(page) {
  const fields = await page.locator('input, select, textarea').evaluateAll((elements) => elements
    .filter((element) => element.offsetParent !== null && element.type !== 'hidden')
    .slice(0, 30)
    .map((element) => {
      const label = element.getAttribute('aria-label') || element.labels?.[0]?.innerText || element.placeholder || element.name || element.id || element.type
      let value
      if (element.type === 'checkbox' || element.type === 'radio') value = element.checked ? 'ticked' : 'not ticked'
      else if (element.type === 'password') value = element.value ? '(filled)' : '(empty)'
      else if (element.tagName === 'SELECT') value = element.selectedOptions?.[0]?.textContent?.trim() || '(nothing selected)'
      else value = element.value === '' ? '(empty)' : element.value
      return `- ${String(label).trim().replace(/\s+/g, ' ').slice(0, 50)} (${element.tagName === 'SELECT' ? 'dropdown' : element.type || 'text'}): ${String(value).slice(0, 80)}`
    })).catch(() => [])
  return fields.length ? `\nForm fields (current values):\n${fields.join('\n')}` : ''
}

/** What the agent can see right now. */
async function observe(page) {
  const url = page.url()
  const snapshot = await page.locator('body').ariaSnapshot({ timeout: 2500 }).catch(() => '')
  const text = await page.locator('body').innerText({ timeout: 2500 }).catch(() => '')
  const fields = await formState(page)
  return {
    url,
    snapshot: snapshot.trim() ? `${snapshot.slice(0, MAX_SNAPSHOT)}${fields}` : '(the page is completely empty)',
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
  if (action.type !== 'click') candidates.push(page.getByLabel(name), page.getByPlaceholder(name))
  else candidates.push(page.getByText(name, { exact: false }))
  // Password inputs have no ARIA role, so a model that asks for "Password" finds them here.
  if (action.type === 'fill' && /pass/i.test(name)) candidates.push(page.locator('input[type=password]'))

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

async function act(page, action, cursor, account) {
  switch (action.type) {
    case 'wait':
      await page.waitForTimeout(1400)
      return
    case 'goto': {
      // Only within the app under test: a model must not wander off to another site.
      const next = new URL(action.value || '/', page.url())
      const here = new URL(page.url())
      if (next.origin !== here.origin) throw new Error(`Refused to leave the app for ${next.origin}`)
      if (next.pathname === here.pathname && next.search === here.search) {
        throw new Error(`You are already on ${here.pathname}. Read the page instead of reloading it.`)
      }
      // Wait for the page to settle, so its content (not a half-rendered shell) is what gets compared.
      await page.goto(next.toString(), { waitUntil: 'networkidle', timeout: 12_000 }).catch(() => {})
      return
    }
    case 'back':
      await page.goBack({ waitUntil: 'domcontentloaded', timeout: 8000 })
      return
    case 'scroll':
      await page.mouse.wheel(0, 600)
      await page.waitForTimeout(300)
      return
    case 'press':
      await page.keyboard.press(String(action.value || 'Enter'))
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
      const value = account.resolve(action.value)
      if (value) await element.pressSequentially(value, { delay: value.length > 60 ? 5 : 65 })
      return
    }
    case 'select': {
      const element = await locate(page, action)
      await point(page, element, cursor)
      const wanted = account.resolve(action.value).toLowerCase()
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

function describe(action, account) {
  if (!action) return ''
  const name = action.name ? ` "${action.name}"` : ''
  switch (action.type) {
    case 'fill': return action.value ? account.mask(`type "${short(action.value)}" into${name}`) : `clear${name}`
    case 'press': return `press ${action.value || 'Enter'}`
    case 'goto': return `go to ${action.value || '/'}`
    case 'back': return 'go back'
    case 'scroll': return 'scroll down'
    case 'select': return `choose "${action.value}" in${name}`
    case 'check': return `${String(action.value) === 'false' ? 'untick' : 'tick'}${name}`
    case 'wait': return 'wait'
    case 'done': return action.status ? `done: ${action.status}` : 'check the result'
    default: return `${action.type}${name}`
  }
}

function short(text) {
  const value = String(text ?? '')
  return value.length > 40 ? `${value.slice(0, 37)}… (${value.length} chars)` : value
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
  return history.slice(-10).map((entry, index) => `${index + 1}. ${entry.what} -> ${entry.error ? `FAILED: ${entry.error}` : entry.effect ?? 'ok'}`).join('\n')
}

/**
 * Closes the step list once the verdict is known: a failed run always names the step it failed on,
 * and steps after a failure were never tried.
 */
export function settleSteps(results, verdict, reason) {
  if (verdict.status === 'failed' && !results.some((step) => step.status === 'failed')) {
    const open = results.findIndex((step) => step.status === 'running' || step.status === 'pending')
    const index = open < 0 ? results.length - 1 : open
    results[index].status = 'failed'
    results[index].observed = results[index].observed ?? reason
  }
  let broken = false
  for (const step of results) {
    if (step.status === 'failed') broken = true
    else if (step.status === 'running' || step.status === 'pending') step.status = broken || verdict.status === 'failed' ? 'skipped' : 'passed'
  }
  return results
}

/**
 * Runs one mission, a written test case, to a verdict. [emit] receives progress events for the
 * live tiles; the return value is everything the report needs.
 */
export async function runAgent({ mission, page, target, brain, emit, maxSteps: defaultMaxSteps = 12, pace = 900, isStopped = () => false, account = createAccount(), app = '' }) {
  const startedAt = Date.now()
  const maxSteps = mission.maxSteps ?? defaultMaxSteps
  const evidence = { apiErrors: [], consoleErrors: [], pageErrors: [], requests: 0 }
  const history = []
  // Everything the agent did, with its reasoning, and what it remarked on: the review is written from these.
  const journal = []
  const notes = []
  const cursor = { x: 40, y: 40 }
  const results = freshStepResults(stepsFor(mission))
  let current = 0
  let mode = brain.canThink ? 'model' : 'script'
  let scriptIndex = 0
  let claimed = null
  let steps = 0
  let turns = 0
  let noChange = 0
  let last = null
  // The requests the current action caused, so its effect can be described.
  let recent = []

  page.on('response', (response) => {
    const request = response.request()
    if (!['fetch', 'xhr'].includes(request.resourceType())) return
    evidence.requests += 1
    const record = { method: request.method(), path: pathOf(request.url()), status: response.status() }
    recent.push(record)
    emit({ type: 'network', id: mission.id, ...record })
    if (record.status >= 400) evidence.apiErrors.push(record)
  })
  page.on('requestfailed', (request) => {
    if (!['fetch', 'xhr'].includes(request.resourceType())) return
    const record = { method: request.method(), path: pathOf(request.url()), status: 0 }
    recent.push(record)
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

  const stepView = () => results.map(({ do: instruction, expect, status, observed }) => ({ do: instruction, expect, status, observed }))
  const say = (status, thought, action) => emit({
    type: 'agent', id: mission.id, status, step: steps, thought: account.mask(thought), action: describe(action, account), testSteps: stepView(),
  })
  const failStep = (reason) => {
    results[current].status = 'failed'
    results[current].observed = account.mask(reason).slice(0, 160)
    claimed = { status: 'failed', summary: `Step ${current + 1} failed: ${results[current].observed}` }
  }
  const passStep = (observed) => {
    results[current].status = 'passed'
    results[current].observed = observed ? account.mask(observed).slice(0, 160) : null
    current += 1
    noChange = 0
    last = null
    if (current < results.length) results[current].status = 'running'
  }

  say('running', 'Opening the app.')
  try {
    await page.goto(target, { waitUntil: 'networkidle', timeout: 15_000 })
  } catch (error) {
    evidence.pageErrors.push(`Could not open ${target}: ${error.message.split('\n')[0]}`)
  }
  await page.waitForTimeout(pace)

  // A crash ends the test at once: there is nothing left to click, and the crash is the finding.
  while (turns < maxSteps + results.length && !isStopped() && evidence.pageErrors.length === 0) {
    turns += 1
    const seen = await observe(page)
    let decision

    if (mode === 'model') {
      const errors = evidence.apiErrors.map((error) => `${error.method} ${error.path} -> HTTP ${error.status}`).join('\n') || 'none'
      const step = results[current]
      try {
        decision = await brain.json(AGENT_SYSTEM, [
          `Persona: ${mission.persona}`,
          `Test: ${mission.goal}`,
          app ? `The app: ${app}` : '',
          mission.login ? `This test needs you signed in. ${account.brief()}` : account.brief(),
          `Test case:\n${testCaseText(results, current)}`,
          `CURRENT STEP ${current + 1} of ${results.length}: ${step.do}\nExpected: ${step.expect || 'the action visibly worked'}\nActions used on this step: ${step.actions} of ${STEP_BUDGET}. URL: ${seen.url}`,
          `What you did so far:\n${historyText(history)}`,
          `Failed network requests so far:\n${errors}`,
          `Accessibility tree:\n${account.mask(seen.snapshot)}`,
        ].filter(Boolean).join('\n\n'))
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
        results.splice(0, results.length, ...freshStepResults(stepsFor(mission)))
        current = 0
        continue
      }
    } else {
      const next = mission.script?.[scriptIndex++]
      // A scripted journey moves through its steps as its actions do.
      while (next && Number.isInteger(next.step) && next.step > current && current < results.length - 1) passStep(null)
      decision = next
        ? { thought: next.thought, action: next }
        : { thought: 'That was the whole journey. Checking the result.', action: { type: 'done' } }
    }

    // The model's answer to "is the expected result on the page now?" counts as a step report.
    const action = mode === 'model' && decision?.expected_visible === true && decision?.action?.type !== 'step'
      ? { type: 'step', result: 'passed', evidence: decision.evidence ?? '' }
      : decision?.action ?? { type: 'wait' }
    if (typeof decision?.note === 'string' && decision.note.trim() && notes.length < 12) notes.push(account.mask(decision.note.trim().slice(0, 200)))

    if (action.type === 'done') {
      if (mode === 'model') claimed = { status: action.status === 'passed' ? 'passed' : 'failed', summary: action.summary ?? decision.thought }
      say('running', decision.thought || 'Checking the result.', action)
      break
    }

    if (action.type === 'step') {
      const quote = String(action.evidence ?? '').slice(0, 200)
      if (action.result === 'passed') {
        if (evidenceOnPage(quote, `${seen.text}\n${seen.snapshot}`)) {
          const entry = { what: `check step ${current + 1}`, effect: `PASSED, seen "${account.mask(quote)}"`, thought: account.mask(decision.thought || '') }
          history.push(entry)
          journal.push(entry)
          passStep(quote)
          say('running', decision.thought || `Step ${current} passed.`, null)
          if (current >= results.length) {
            claimed = { status: 'passed', summary: `All ${results.length} steps passed` }
            break
          }
          continue
        }
        // The model claimed a result the page does not show: not accepted, and it costs an action.
        results[current].actions += 1
        const entry = { what: `check step ${current + 1}`, error: `REJECTED: "${account.mask(quote)}" is not on the page. Quote real text, or report the step failed.`, thought: account.mask(decision.thought || '') }
        history.push(entry)
        journal.push(entry)
        if (results[current].actions > STEP_BUDGET) {
          failStep(`expected "${results[current].expect}" but it never appeared`)
          break
        }
        continue
      }
      failStep(quote || decision.thought || 'the expected result did not appear')
      say('running', decision.thought || `Step ${current + 1} failed.`, null)
      break
    }

    // A step that is going nowhere is a finding, not a reason to keep clicking.
    if (mode === 'model' && results[current].actions >= STEP_BUDGET) {
      failStep(`no result after ${STEP_BUDGET} actions; expected "${results[current].expect}"`)
      break
    }
    if (mode === 'model' && noChange >= NO_CHANGE_LIMIT) {
      failStep(`${NO_CHANGE_LIMIT} actions in a row changed nothing on the page`)
      break
    }

    results[current].actions += 1
    const entry = { what: describe(action, account), thought: account.mask(decision.thought || '') }
    if (mode === 'model' && last && !last.changed && sameAction(last.action, action)) {
      entry.error = 'REFUSED: you already did exactly this and nothing changed. Do something different, or report the step failed.'
      noChange += 1
    } else {
      steps += 1
      say('running', decision.thought || '', action)
      recent = []
      try {
        await act(page, action, cursor, account)
      } catch (error) {
        entry.error = account.mask(error.message.split('\n')[0].slice(0, 160))
      }
      await page.waitForTimeout(pace)
      const after = entry.error ? null : await observe(page)
      const change = after ? changeBetween(seen, after, recent) : { changed: false }
      if (!entry.error) entry.effect = account.mask(change.text)
      noChange = change.changed ? 0 : noChange + 1
      last = { action, changed: change.changed }

      // The runner checks the step itself: once the step's action has been done, the text its
      // expected result quotes being on the page is proof enough, whatever the model says next.
      const proof = after && mode === 'model' ? quotedProof(results[current].expect, `${after.text}\n${after.snapshot}`) : null
      if (proof) {
        entry.effect = `${entry.effect}; step ${current + 1} PASSED, seen "${account.mask(proof)}"`
        history.push(entry)
        journal.push(entry)
        passStep(proof)
        say('running', `Step ${current} passed: I can see "${account.mask(proof)}".`, null)
        if (current >= results.length) {
          claimed = { status: 'passed', summary: `All ${results.length} steps passed` }
          break
        }
        continue
      }
    }
    history.push(entry)
    journal.push(entry)
  }

  // Out of turns, or stopped, without a result: that is a failure to finish, not a pass.
  if (!claimed && mode === 'model' && evidence.pageErrors.length === 0) {
    claimed = { status: 'failed', summary: isStopped() ? 'Stopped before finishing' : `Ran out of actions on step ${Math.min(current + 1, results.length)}` }
  }

  await page.waitForTimeout(400)
  const final = await observe(page)
  const verdict = judge({ mission, text: final.text, evidence, claimed, steps, maxSteps })
  settleSteps(results, verdict, account.mask(verdictThought(verdict)))
  const screenshot = await page.screenshot({ type: 'jpeg', quality: 70 }).catch(() => null)

  // The verdict is already final; the tile says what the agent is doing while the model writes.
  if (mode === 'model') say('running', 'Writing my review.')
  const review = mode === 'model'
    ? await writeReview(brain, { mission, app, journal, notes, verdict, evidence, finalText: account.mask(final.text), mask: account.mask, testSteps: stepView() })
    : null
  emit({ type: 'agent', id: mission.id, status: verdict.status, step: steps, thought: account.mask(verdictThought(verdict)), action: '', review, testSteps: stepView() })

  return {
    mission,
    verdict,
    evidence,
    steps,
    testSteps: stepView(),
    durationMs: Date.now() - startedAt,
    mode,
    claimed,
    review,
    notes,
    journal,
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
