// Indexes over the analysis graph shared by every view.

export const KIND_LABELS = {
  frontend: 'Frontend',
  client: 'Client',
  backend: 'Backend',
  fullstack: 'Full-stack',
  library: 'Library',
}

export const STATUS_LABELS = {
  matched: 'Matched',
  external: 'External',
  unresolved: 'Unresolved',
  dynamic: 'Dynamic URL',
}

export function plural(count, singular, multiple = `${singular}s`) {
  return `${count} ${count === 1 ? singular : multiple}`
}

export function fileName(path) {
  return path?.split('/').at(-1) ?? ''
}

export function directory(path) {
  const index = path?.lastIndexOf('/') ?? -1
  return index < 0 ? '' : path.slice(0, index)
}

/** Builds lookup tables once per graph. */
export function indexGraph(graph) {
  const modules = new Map(graph.modules.map((module) => [module.id, module]))
  const endpoints = new Map(graph.endpoints.map((endpoint) => [endpoint.id, endpoint]))
  const calls = new Map(graph.calls.map((call) => [call.id, call]))
  const externals = new Map(graph.externals.map((external) => [external.id, external]))
  const datastores = new Map(graph.datastores.map((store) => [store.id, store]))

  const callsByModule = groupBy(graph.calls, (call) => call.moduleId)
  const endpointsByModule = groupBy(graph.endpoints, (endpoint) => endpoint.moduleId)
  const callsByFile = groupBy(graph.calls, (call) => call.filePath)
  const endpointsByFile = groupBy(graph.endpoints, (endpoint) => endpoint.filePath)

  return {
    graph,
    modules,
    endpoints,
    calls,
    externals,
    datastores,
    callsByModule,
    endpointsByModule,
    callsByFile,
    endpointsByFile,
    moduleName: (id) => modules.get(id)?.name ?? id,
  }
}

export function groupBy(items, key) {
  const groups = new Map()
  for (const item of items) {
    const k = key(item)
    const list = groups.get(k)
    if (list) list.push(item)
    else groups.set(k, [item])
  }
  return groups
}

export function matchesQuery(query, ...values) {
  if (!query) return true
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return values.some((value) => value && String(value).toLowerCase().includes(needle))
}

/** The problems a developer may want to act on. */
export function issues(index) {
  const { graph } = index
  const unresolved = graph.calls.filter((call) => call.status === 'unresolved')
  const dynamic = graph.calls.filter((call) => call.status === 'dynamic')
  const uncertain = graph.calls.filter((call) => call.status === 'matched' && call.confidence !== 'exact')
  const uncalled = graph.endpoints.filter((endpoint) => endpoint.callerIds.length === 0)
  return { unresolved, dynamic, uncertain, uncalled }
}
