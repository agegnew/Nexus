// A mission is run as a written test case: numbered steps, each "do this" plus "you should see
// that". The runner, not the model, keeps track of which step is current, tells the model what
// each action changed on the page, refuses to repeat an action that changed nothing, and fails a
// step that makes no progress. That is what stops an agent clicking the same button forever.

/** Actions one step may take before it counts as stuck. */
export const STEP_BUDGET = 5
/** Actions in a row that changed nothing before the step counts as stuck. */
export const NO_CHANGE_LIMIT = 3

/** Cleans the planner's steps: 1 to 7 of them, each with an instruction and an expected result. */
export function normalizeSteps(raw) {
  return (Array.isArray(raw) ? raw : [])
    .map((step) => (typeof step === 'string' ? { do: step, expect: '' } : step))
    .filter((step) => typeof step?.do === 'string' && step.do.trim())
    .slice(0, 7)
    .map((step) => ({
      do: step.do.trim().slice(0, 200),
      expect: typeof step.expect === 'string' ? step.expect.trim().slice(0, 200) : '',
    }))
}

/** The steps a mission runs. A mission with only a goal becomes one step. */
export function stepsFor(mission) {
  const steps = normalizeSteps(mission.steps)
  if (steps.length) return steps
  return [{ do: mission.goal, expect: mission.expect ? `The page shows text matching /${mission.expect}/` : 'The goal is visibly done' }]
}

/** The run's bookkeeping for each step, which the tiles and the report show. */
export function freshStepResults(steps) {
  return steps.map((step, index) => ({ ...step, status: index === 0 ? 'running' : 'pending', observed: null, actions: 0 }))
}

function lines(text) {
  return new Set(String(text ?? '').split('\n').map((line) => line.trim()).filter(Boolean))
}

/**
 * What one action did to the page, in words the model can act on. [before] and [after] are
 * observations ({ url, snapshot }); [network] the requests the action caused.
 */
export function changeBetween(before, after, network = []) {
  const parts = []
  if (before.url !== after.url) parts.push(`URL is now ${pathAndQuery(after.url)}`)
  const was = lines(before.snapshot)
  const now = lines(after.snapshot)
  const added = [...now].filter((line) => !was.has(line))
  const removed = [...was].filter((line) => !now.has(line))
  if (added.length) parts.push(`appeared: ${added.slice(0, 4).map((line) => line.replace(/^- /, '').slice(0, 90)).join(' | ')}${added.length > 4 ? ` (+${added.length - 4} more)` : ''}`)
  if (removed.length) parts.push(`${removed.length} line${removed.length === 1 ? '' : 's'} disappeared`)
  const failed = network.filter((call) => call.status >= 400 || call.status === 0)
  if (network.length) parts.push(`requests: ${network.slice(0, 3).map((call) => `${call.method} ${call.path} -> ${call.status || 'no response'}`).join(', ')}`)
  const changed = parts.length > 0
  return {
    changed,
    failedRequests: failed.length,
    text: changed ? parts.join('; ') : 'NO VISIBLE CHANGE (the page is exactly as before)',
  }
}

function pathAndQuery(url) {
  try {
    const parsed = new URL(url)
    return `${parsed.pathname}${parsed.search}`
  } catch {
    return url
  }
}

function normalize(text) {
  return String(text ?? '').toLowerCase().replace(/[“”"'`]/g, '').replace(/\s+/g, ' ').trim()
}

/**
 * Whether the text the model quoted as proof is really on the page. An exact (normalized) match
 * counts, and so does a quote whose every meaningful word is on the page, since models tidy up
 * spacing and punctuation when they copy.
 */
export function evidenceOnPage(evidence, pageText) {
  const quote = normalize(evidence)
  if (quote.length < 2) return false
  const page = normalize(pageText)
  if (page.includes(quote)) return true
  const words = quote.split(/[^\p{L}\p{N}#@.]+/u).filter((word) => word.length >= 3)
  return words.length >= 2 && words.every((word) => page.includes(word))
}

/** A repeat is the same action on the same element with the same value. */
export function sameAction(a, b) {
  if (!a || !b) return false
  return a.type === b.type && String(a.name ?? '') === String(b.name ?? '') && String(a.value ?? '') === String(b.value ?? '') && String(a.role ?? '') === String(b.role ?? '')
}

/** The test case as the model sees it: every step, its state, and the current one marked. */
export function testCaseText(results, current) {
  return results.map((step, index) => {
    const mark = { passed: '[x]', failed: '[FAILED]', skipped: '[ ]', pending: '[ ]', running: '[>]' }[step.status] ?? '[ ]'
    const observed = step.observed ? ` (seen: "${step.observed}")` : ''
    return `${mark} Step ${index + 1}: ${step.do}\n      Expected: ${step.expect || 'the action visibly worked'}${observed}${index === current ? '   <-- CURRENT STEP' : ''}`
  }).join('\n')
}
