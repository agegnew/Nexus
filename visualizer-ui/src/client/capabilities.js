// Turns the analyser's file-level findings into something a non-developer can
// read. "LoginForm.tsx calls POST /api/auth/login" becomes "Sign in - signs
// users in", which is the same fact told to a different audience.

// Trailing words that name a code construct rather than the capability itself.
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

// Nouns whose plain-English meaning is universal enough to name outright.
const WELL_KNOWN = [
  { match: /^(auth|login|signin|session|token|oauth)$/, summary: 'Signs people in', glyph: 'shield' },
  { match: /^(user|users|account|accounts|profile|profiles|me)$/, summary: 'Manages accounts', glyph: 'user' },
  { match: /^(report|reports|analytics|metric|metrics|stat|stats|insight|insights)$/, glyph: 'chart' },
  { match: /^(billing|charge|charges|payment|payments|invoice|invoices|subscription|subscriptions)$/, glyph: 'card' },
  { match: /^(event|events|telemetry|log|logs|track|tracking|audit)$/, glyph: 'pulse' },
  { match: /^(project|projects|board|boards|workspace|workspaces)$/, glyph: 'board' },
  { match: /^(task|tasks|todo|todos|item|items|note|notes|ticket|tickets)$/, glyph: 'checklist' },
]

function fileName(path) {
  return path.split('/').at(-1) || path
}

export function humanName(file) {
  const base = fileName(file)
    .replace(/\.[^.]+$/, '')
    .replace(/^use(?=[A-Z])/, '')
    .replace(/[-_.]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')

  const words = base.split(/\s+/).filter(Boolean)
  if (words.length > 1 && CONSTRUCT_SUFFIX.test(words.at(-1))) words.pop()
  if (words.length === 0) return fileName(file)

  return words
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ')
}

export function resourceOf(routePath) {
  const segments = routePath
    .split('/')
    .filter((segment) => (
      segment
      && segment !== 'api'
      && !/^v\d+$/i.test(segment)
      && !segment.startsWith('{')
      && !segment.startsWith(':')
    ))
  return segments[0] ?? 'data'
}

function phrase(verbs, resource) {
  const ordered = VERB_ORDER.filter((verb) => verbs.has(verb))
  if (ordered.length === 0) return `Works with ${resource}`
  if (ordered.length >= 3) return `Manages ${resource}`

  const spoken = ordered.join(' and ')
  return `${spoken[0].toUpperCase()}${spoken.slice(1)} ${resource}`
}

function describe(verbs, resource) {
  const known = WELL_KNOWN.find((entry) => entry.match.test(resource))
  return {
    summary: known?.summary ?? phrase(verbs, resource),
    glyph: known?.glyph ?? 'module',
  }
}

// One capability per source file that talks to the outside world.
export function buildCapabilities(analysis) {
  const byId = new Map(analysis.nodes.map((node) => [node.id, node]))
  const requestFor = new Map()
  analysis.edges.forEach((edge) => {
    if (byId.get(edge.source)?.kind === 'frontend') requestFor.set(edge.source, edge)
  })

  const capabilities = new Map()

  analysis.nodes.filter((node) => node.kind === 'frontend').forEach((call) => {
    const path = call.filePath || 'Unknown source'
    const entry = capabilities.get(path) ?? {
      id: path,
      path,
      name: humanName(path),
      calls: 0,
      connected: 0,
      missing: 0,
      verbs: new Set(),
      resources: new Map(),
      services: new Set(),
    }
    entry.calls += 1

    const request = requestFor.get(call.id)
    const destination = request ? byId.get(request.target) : null
    const method = (request?.label ?? 'GET').toUpperCase()
    entry.verbs.add(VERB_FOR_METHOD[method] ?? 'reads')

    if (destination) {
      const [, ...rest] = destination.label.split(' ')
      const resource = resourceOf(rest.join(' ') || destination.label)
      entry.resources.set(resource, (entry.resources.get(resource) ?? 0) + 1)

      if (destination.kind === 'unmatched') entry.missing += 1
      else {
        entry.connected += 1
        if (destination.technology) entry.services.add(destination.technology)
      }
    }

    capabilities.set(path, entry)
  })

  const list = [...capabilities.values()].map((entry) => {
    // The resource it touches most is the one worth naming.
    const resource = [...entry.resources.entries()]
      .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))[0]?.[0] ?? 'data'
    const { summary, glyph } = describe(entry.verbs, resource)

    return {
      id: entry.id,
      name: entry.name,
      summary,
      glyph,
      resource,
      calls: entry.calls,
      connected: entry.connected,
      missing: entry.missing,
      services: [...entry.services].sort(),
    }
  })

  // Fully connected capabilities first; anything with a gap sinks to the end
  // where it reads as the exception rather than the norm.
  list.sort((left, right) => (
    left.missing - right.missing
    || right.calls - left.calls
    || left.name.localeCompare(right.name)
  ))

  const services = [...new Set(list.flatMap((item) => item.services))].sort()

  return {
    capabilities: list,
    services,
    totals: {
      capabilities: list.length,
      calls: list.reduce((sum, item) => sum + item.calls, 0),
      connected: list.reduce((sum, item) => sum + item.connected, 0),
      missing: list.reduce((sum, item) => sum + item.missing, 0),
      services: services.length,
    },
  }
}
