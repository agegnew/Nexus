// Turns a request the browser actually made into the lines of code behind it, using the
// ProjectGraph the Nexus analyzer already produced. The graph is the same JSON the Map tab
// renders: frontend call nodes point at an endpoint node (matched) or an unmatched node.

/** Every frontend call in the graph, with where it lives and what serves it. */
export function callSites(graph) {
  if (!graph?.nodes || !graph?.edges) return []
  const nodes = new Map(graph.nodes.map((node) => [node.id, node]))
  const handlers = new Map(
    graph.edges.filter((edge) => edge.label === 'handles').map((edge) => [edge.source, nodes.get(edge.target)]),
  )

  return graph.edges
    .filter((edge) => nodes.get(edge.source)?.kind === 'frontend')
    .map((edge) => {
      const caller = nodes.get(edge.source)
      const target = nodes.get(edge.target)
      if (!target) return null
      const [method, ...rest] = target.label.split(' ')
      const backend = target.kind === 'endpoint' ? handlers.get(target.id) : null
      return {
        method: (edge.label || method).toUpperCase(),
        path: rest.join(' '),
        matched: target.kind === 'endpoint',
        frontend: { file: caller.filePath, line: caller.line ?? 1 },
        backend: backend?.filePath ? { file: backend.filePath, line: backend.line ?? 1, label: backend.label } : null,
      }
    })
    .filter(Boolean)
}

/** Every endpoint the backend declares, so a failing call can also be blamed on the server side. */
export function endpoints(graph) {
  if (!graph?.nodes || !graph?.edges) return []
  const nodes = new Map(graph.nodes.map((node) => [node.id, node]))
  return graph.edges
    .filter((edge) => edge.label === 'handles')
    .map((edge) => {
      const route = nodes.get(edge.source)
      const handler = nodes.get(edge.target)
      if (!route || !handler) return null
      const [method, ...rest] = route.label.split(' ')
      return { method: method.toUpperCase(), path: rest.join(' '), file: handler.filePath, line: handler.line ?? 1, label: handler.label }
    })
    .filter(Boolean)
}

/** `/api/users/{id}/settings` becomes a regex that accepts `/api/users/3/settings`. */
export function templateToRegex(template) {
  const pattern = template
    .split('/')
    .map((segment) => (/^\{.*\}$/.test(segment) || /^:/.test(segment) ? '[^/]+' : escapeRegex(segment)))
    .join('/')
  return new RegExp(`^${pattern}/?$`)
}

function escapeRegex(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function pathOf(url) {
  try {
    return new URL(url, 'http://local').pathname
  } catch {
    return url.split('?')[0]
  }
}

/**
 * Where in the code a failed request came from. Null when the graph does not know the call,
 * which happens when a URL is built too dynamically for static analysis to see.
 */
export function locateRequest(graph, method, url) {
  const path = pathOf(url)
  const verb = method.toUpperCase()
  const site = callSites(graph).find((call) => call.method === verb && templateToRegex(call.path).test(path))
  const served = endpoints(graph).find((endpoint) => endpoint.method === verb && templateToRegex(endpoint.path).test(path))
  if (!site && !served) return null
  return {
    method: verb,
    path,
    template: site?.path ?? served?.path,
    frontend: site?.frontend ?? null,
    backend: site?.backend ?? (served ? { file: served.file, line: served.line, label: served.label } : null),
    missingBackend: Boolean(site && !site.matched && !served),
  }
}

/** A short text map of the app for the planner: which screens call what, and what is broken. */
export function describeGraph(graph) {
  const sites = callSites(graph)
  if (sites.length === 0) return 'No API calls were detected.'
  return sites
    .map((call) => `${call.frontend.file}: ${call.method} ${call.path}${call.matched ? '' : '  (NO BACKEND MATCH)'}`)
    .join('\n')
}
