/* eslint-disable react-refresh/only-export-components */

// Flat symbol set for the architecture diagram. Each icon reads at a glance at
// 44px, the way a cylinder reads as a database in a reference architecture.

export function IconClient(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <rect x="4" y="9" width="40" height="30" rx="3" fill="#cfe6f7" stroke="#3b82b8" strokeWidth="1.6" />
      <path d="M4 12a3 3 0 0 1 3-3h34a3 3 0 0 1 3 3v5H4z" fill="#5fa8d8" />
      <circle cx="9.5" cy="13" r="1.4" fill="#fff" opacity=".9" />
      <circle cx="14" cy="13" r="1.4" fill="#fff" opacity=".9" />
      <path d="M30 28H17m0 0 5-5m-5 5 5 5" fill="none" stroke="#1d6ea4" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export function IconModule(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <path d="M11 5h17l9 9v29a2 2 0 0 1-2 2H11a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" fill="#e6f6fb" stroke="#2f9ec4" strokeWidth="1.6" />
      <path d="M28 5l9 9h-9z" fill="#9ed9ea" />
      <path d="M19 24l-4 5 4 5M29 24l4 5-4 5M26 22l-4 14" fill="none" stroke="#1f8bb0" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

// Isometric cube — the "one deployed unit" symbol.
export function IconService(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <path d="M24 5l17 9.5v19L24 43 7 33.5v-19z" fill="#3fb6e8" />
      <path d="M24 5l17 9.5L24 24 7 14.5z" fill="#8fdcf5" />
      <path d="M24 24l17-9.5v19L24 43z" fill="#1e88c2" />
      <path d="M24 5l17 9.5v19L24 43 7 33.5v-19z" fill="none" stroke="#12699c" strokeWidth="1.3" strokeLinejoin="round" />
    </svg>
  )
}

export function IconDatabase(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <path d="M8 12v24c0 3.3 7.2 6 16 6s16-2.7 16-6V12z" fill="#2f86c9" />
      <ellipse cx="24" cy="12" rx="16" ry="6" fill="#7cc4ea" stroke="#1d6ea4" strokeWidth="1.3" />
      <path d="M8 12v24c0 3.3 7.2 6 16 6s16-2.7 16-6V12" fill="none" stroke="#1d6ea4" strokeWidth="1.5" />
      <path d="M40 22c0 3.3-7.2 6-16 6S8 25.3 8 22M40 30c0 3.3-7.2 6-16 6S8 33.3 8 30" fill="none" stroke="#bfe2f5" strokeWidth="1.4" />
    </svg>
  )
}

export function IconExternal(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <path d="M36.5 38h-22a9.5 9.5 0 0 1-1.2-18.9A13 13 0 0 1 37 21.6a8.2 8.2 0 0 1-.5 16.4z" fill="#ffe2c2" stroke="#d98324" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M24 33v-9m0 0-3.5 3.5M24 24l3.5 3.5" fill="none" stroke="#b96c12" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export function IconGateway(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <path d="M24 4l17 10v20L24 44 7 34V14z" fill="#e8ddfa" stroke="#7e5bc4" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M15 24h18m0 0-5-5m5 5-5 5" fill="none" stroke="#6941ad" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

// --- technology marks -----------------------------------------------------

export function IconPython(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <rect x="5" y="5" width="38" height="38" rx="9" fill="#0f9d8f" />
      <path d="M26 10l-11 15h8l-3 13 11-15h-8z" fill="#ecfdf9" />
    </svg>
  )
}

export function IconSpring(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <rect x="5" y="5" width="38" height="38" rx="9" fill="#5b9a37" />
      <path d="M33 14c2 11-4 20-13 20-3 0-5-1-6-2 7 0 12-4 14-9-3 3-7 4-10 3 6-1 11-6 15-12z" fill="#eaf7e0" />
    </svg>
  )
}

export function IconNode(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <path d="M24 5l16.5 9.5v19L24 43 7.5 33.5v-19z" fill="#4a8c3f" stroke="#33682b" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M28 18v9c0 2-1.5 3-4 3s-4-1-4-2.6" fill="none" stroke="#e8f6e2" strokeWidth="2.6" strokeLinecap="round" />
    </svg>
  )
}

export function IconServer(props) {
  return (
    <svg viewBox="0 0 48 48" role="presentation" {...props}>
      <rect x="7" y="8" width="34" height="11" rx="2.5" fill="#cfd9e6" stroke="#5a6b83" strokeWidth="1.5" />
      <rect x="7" y="21" width="34" height="11" rx="2.5" fill="#cfd9e6" stroke="#5a6b83" strokeWidth="1.5" />
      <rect x="7" y="34" width="34" height="8" rx="2.5" fill="#e6ebf2" stroke="#5a6b83" strokeWidth="1.5" />
      <circle cx="13" cy="13.5" r="1.7" fill="#4aa564" />
      <circle cx="13" cy="26.5" r="1.7" fill="#4aa564" />
      <path d="M20 13.5h15M20 26.5h15" stroke="#8b99ad" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

const DATASTORE = /sql|postgres|mysql|mongo|redis|cosmos|dynamo|sqlite|oracle|cassandra|database/
const PYTHONISH = /fastapi|flask|django|python|starlette/
const SPRINGISH = /spring|kotlin|java|quarkus|micronaut/
const NODEISH = /express|node|nest|fastify|koa/

// A datastore-looking technology gets the cylinder, Spring gets the leaf, and
// so on — the symbol carries the meaning before the label is read. Glyphs are
// addressed by name so no component is ever selected during render.
export function glyphForTechnology(technology) {
  const name = (technology || '').toLowerCase()
  if (DATASTORE.test(name)) return 'database'
  if (PYTHONISH.test(name)) return 'python'
  if (SPRINGISH.test(name)) return 'spring'
  if (NODEISH.test(name)) return 'node'
  return 'server'
}

export function glyphForRoute(technology) {
  return DATASTORE.test((technology || '').toLowerCase()) ? 'database' : 'service'
}

export function Glyph({ name, className }) {
  switch (name) {
    case 'client': return <IconClient className={className} />
    case 'module': return <IconModule className={className} />
    case 'database': return <IconDatabase className={className} />
    case 'external': return <IconExternal className={className} />
    case 'gateway': return <IconGateway className={className} />
    case 'python': return <IconPython className={className} />
    case 'spring': return <IconSpring className={className} />
    case 'node': return <IconNode className={className} />
    case 'server': return <IconServer className={className} />
    default: return <IconService className={className} />
  }
}
