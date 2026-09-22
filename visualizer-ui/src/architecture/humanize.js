// Names a client source file the way someone outside the codebase would:
// "LoginForm.tsx calling POST /api/auth/login" becomes a shield, "Login" and
// "Signs people in". The file path stays available as the card's tooltip.

// Trailing words that name a code construct rather than the feature itself.
const CONSTRUCT_SUFFIX = /^(page|view|form|screen|modal|panel|dialog|service|client|api|controller|provider|context|store|slice|hook|component|container|widget|route|router|handler|helper|util|utils)$/i

const VERB_FOR_METHOD = {
  GET: 'reads',
  HEAD: 'reads',
  OPTIONS: 'reads',
  POST: 'creates',
  PUT: 'updates',
  PATCH: 'updates',
  DELETE: 'removes',
}

const VERB_ORDER = ['reads', 'creates', 'updates', 'removes']

// Nouns whose plain meaning is universal enough to name outright. Anything
// not listed still gets a generic description and the default symbol.
// `summary` is a fixed sentence and is therefore a CLAIM. It may only be used when the verbs
// the file actually performs support it, or the card says the opposite of the truth: a file
// that only calls DELETE /api/session signs people OUT, and printing "Signs people in" over
// it is not a rough description, it is wrong. `only` names the verbs the sentence survives.
const WELL_KNOWN = [
  { match: /^(auth|login|signin|session|token|oauth)$/, summary: 'Signs people in', only: ['creates'], glyph: 'shield' },
  { match: /^(user|users|account|accounts|profile|profiles|me)$/, summary: 'Manages accounts', only: ['reads', 'creates', 'updates'], glyph: 'user' },
  { match: /^(report|reports|analytics|metric|metrics|stat|stats|insight|insights)$/, glyph: 'chart' },
  { match: /^(billing|charge|charges|payment|payments|invoice|invoices|subscription|subscriptions)$/, glyph: 'card' },
  { match: /^(event|events|telemetry|log|logs|track|tracking|audit)$/, glyph: 'pulse' },
  { match: /^(project|projects|board|boards|workspace|workspaces)$/, glyph: 'board' },
  { match: /^(task|tasks|todo|todos|item|items|note|notes|ticket|tickets)$/, glyph: 'checklist' },
]

// A route segment is rarely a bare noun. `billing-events`, `billingEvents` and `billing_events`
// are all billing, and testing the raw segment against /^billing$/ recognises none of them.
function matches(entry, segment) {
  const words = String(segment)
    .replace(/[-_.]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .split(' ')
    .filter(Boolean)
  return entry.match.test(segment) || words.some((word) => entry.match.test(word))
}

export function humanName(fileName) {
  const words = fileName
    .replace(/\.[^.]+$/, '')
    .replace(/^use(?=[A-Z])/, '')
    .replace(/[-_.]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/\s+/)
    .filter(Boolean)

  // Strip the construct word only while something is left that names the feature. A file
  // genuinely called api.ts has nothing else to be called, and dropping the word left every
  // such file rendering as the same empty card: one real project's whole client layer was
  // two rows, both reading "Api".
  if (words.length > 1 && CONSTRUCT_SUFFIX.test(words.at(-1))) words.pop()
  if (words.length === 0) return fileName

  return words.map((word) => word[0].toUpperCase() + word.slice(1)).join(' ')
}

// Segments that are plumbing in every API ever written, so never the subject of a route.
const GENERIC_SEGMENT = /^(api|apis|rest|graphql|rpc|public|private|internal|v\d+|\d+)$/i

// Every segment of a route that could name what it is about, outermost first.
// `/api/workspaces/{id}/billing/consent` gives ['workspaces', 'billing', 'consent'].
export function domainsOf(routePath) {
  return String(routePath || '')
    .split('/')
    .filter((part) => (
      part
      && !GENERIC_SEGMENT.test(part)
      && !part.startsWith('{')
      && !part.startsWith(':')
      && !part.startsWith('[')
      && !part.startsWith('$')
      && !part.includes('.')
      && !part.includes(':')
    ))
}

export function resourceOf(routePath) {
  return domainsOf(routePath)[0] ?? 'data'
}

export function verbFor(method) {
  return VERB_FOR_METHOD[(method || 'GET').toUpperCase()] ?? 'reads'
}

// `billing-events` and `taskLists` are how a route spells a noun, not how a person says it.
function spoken(resource) {
  return String(resource)
    .replace(/[-_.]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .trim() || 'data'
}

function phrase(verbs, resource) {
  const noun = spoken(resource)
  const ordered = VERB_ORDER.filter((verb) => verbs.has(verb))
  if (ordered.length === 0) return `Works with ${noun}`
  if (ordered.length >= 3) return `Manages ${noun}`

  const said = ordered.join(' and ')
  return `${said[0].toUpperCase()}${said.slice(1)} ${noun}`
}

// `resources` maps each resource the file touches to how often, so the one it
// uses most is the one that gets named.
/**
 * What one file does, in a sentence, and which symbol to draw beside it.
 *
 * `resources` maps each candidate segment the file touches to how often, so the one it uses
 * most is the one that gets named. Two things this gets right that the obvious version does
 * not:
 *
 * A route's subject is not always its first segment. `/api/workspaces/{id}/billing/consent`
 * is about billing, and looking only at the front of the path names it "workspaces" and
 * draws a board. Every segment is a candidate, so a repository that really does have billing
 * gets the billing symbol, and one that does not gets nothing about billing anywhere.
 *
 * A fixed summary is a claim about behaviour, so it is only used when the verbs support it.
 * Otherwise the symbol is still taken (the subject is right) and the sentence is built from
 * what the file actually does.
 */
export function describeFeature(verbs, resources) {
  // Count first, then the order the segments appear in the path. Alphabetical was the
  // tie-break before, and on `/api/v1/reports/monthly`, where both segments appear once,
  // it named the card "monthly". The outer segment is the subject; the inner one qualifies
  // it. Array.prototype.sort is stable, so returning 0 keeps insertion order, which is
  // path order because that is how the Map was filled.
  const ranked = [...resources.entries()].sort((left, right) => right[1] - left[1])

  /*
   * When several segments are recognisable, the DEEPEST one wins.
   *
   * A REST path narrows left to right: `/workspaces/{id}/billing/consent` is billing,
   * scoped to a workspace, and both "workspaces" and "billing" are nouns this file knows.
   * Taking the first gave the card a board symbol and the sentence "Reads workspaces" for
   * a route that is entirely about money. Depth is the tie-break rather than a hand-written
   * table of which noun outranks which, which would need a new row per repository.
   *
   * Count still leads: a file that calls billing once and users forty times is a users file.
   */
  const order = [...resources.keys()]
  const recognised = ranked
    .map(([name, count]) => [name, WELL_KNOWN.find((entry) => matches(entry, name)), count])
    .filter(([, entry]) => entry)
    .sort((left, right) => right[2] - left[2] || order.indexOf(right[0]) - order.indexOf(left[0]))[0]
  const known = recognised?.[1]

  // The symbol and the noun both follow the most-used segment that is recognisable at all,
  // not merely the most-used one, so one mention of billing among four of workspaces still
  // shows a card and still says billing.
  const resource = recognised?.[0] ?? ranked[0]?.[0] ?? 'data'

  const claimable = known?.summary
    && (!known.only || known.only.some((verb) => verbs.has(verb)))
    && [...verbs].every((verb) => !known.only || known.only.includes(verb))

  return {
    summary: claimable ? known.summary : phrase(verbs, resource),
    glyph: known?.glyph ?? 'module',
  }
}
