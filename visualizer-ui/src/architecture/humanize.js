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
const WELL_KNOWN = [
  { match: /^(auth|login|signin|session|token|oauth)$/, summary: 'Signs people in', glyph: 'shield' },
  { match: /^(user|users|account|accounts|profile|profiles|me)$/, summary: 'Manages accounts', glyph: 'user' },
  { match: /^(report|reports|analytics|metric|metrics|stat|stats|insight|insights)$/, glyph: 'chart' },
  { match: /^(billing|charge|charges|payment|payments|invoice|invoices|subscription|subscriptions)$/, glyph: 'card' },
  { match: /^(event|events|telemetry|log|logs|track|tracking|audit)$/, glyph: 'pulse' },
  { match: /^(project|projects|board|boards|workspace|workspaces)$/, glyph: 'board' },
  { match: /^(task|tasks|todo|todos|item|items|note|notes|ticket|tickets)$/, glyph: 'checklist' },
]

export function humanName(fileName) {
  const words = fileName
    .replace(/\.[^.]+$/, '')
    .replace(/^use(?=[A-Z])/, '')
    .replace(/[-_.]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/\s+/)
    .filter(Boolean)

  if (words.length > 1 && CONSTRUCT_SUFFIX.test(words.at(-1))) words.pop()
  if (words.length === 0) return fileName

  return words.map((word) => word[0].toUpperCase() + word.slice(1)).join(' ')
}

export function resourceOf(routePath) {
  const segment = routePath
    .split('/')
    .find((part) => (
      part
      && part !== 'api'
      && !/^v\d+$/i.test(part)
      && !part.startsWith('{')
      && !part.startsWith(':')
    ))
  return segment ?? 'data'
}

export function verbFor(method) {
  return VERB_FOR_METHOD[(method || 'GET').toUpperCase()] ?? 'reads'
}

function phrase(verbs, resource) {
  const ordered = VERB_ORDER.filter((verb) => verbs.has(verb))
  if (ordered.length === 0) return `Works with ${resource}`
  if (ordered.length >= 3) return `Manages ${resource}`

  const spoken = ordered.join(' and ')
  return `${spoken[0].toUpperCase()}${spoken.slice(1)} ${resource}`
}

// `resources` maps each resource the file touches to how often, so the one it
// uses most is the one that gets named.
export function describeFeature(verbs, resources) {
  const resource = [...resources.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))[0]?.[0] ?? 'data'
  const known = WELL_KNOWN.find((entry) => entry.match.test(resource))

  return {
    summary: known?.summary ?? phrase(verbs, resource),
    glyph: known?.glyph ?? 'module',
  }
}
