// Turns a ProjectGraph into an absolutely positioned architecture diagram:
// external actors outside a dashed project boundary, named components inside
// labelled group boxes, and one labelled edge per detected call.

const ACTOR = { width: 168, height: 66 }
const MODULE = { width: 224, height: 58 }
const ROUTE = { width: 286, height: 66 }
const EXTERNAL = { width: 224, height: 58 }

const GROUP_PAD_X = 14
const GROUP_HEADER = 40
const GROUP_PAD_BOTTOM = 14
const ROW_GAP = 12
const GROUP_GAP = 28
const COLUMN_GAP = 132
const BOUNDARY_PAD = 32
const BOUNDARY_LABEL = 16

const Z = { boundary: 0, group: 1, component: 2 }

function fileName(path) {
  return path.split('/').at(-1) || path
}

function directory(path) {
  const cut = path.lastIndexOf('/')
  return cut < 0 ? 'Project root' : path.slice(0, cut)
}

function groupHeight(rowCount, rowHeight) {
  if (rowCount === 0) return GROUP_HEADER + rowHeight + GROUP_PAD_BOTTOM
  return GROUP_HEADER + rowCount * rowHeight + (rowCount - 1) * ROW_GAP + GROUP_PAD_BOTTOM
}

// Routes keep their technology grouping, so modules are the only column free to
// reorder. Sorting them by the mean y of what they call keeps the edges untangled.
function barycentre(module, targetTops) {
  const tops = module.targets.map((target) => targetTops.get(target.id)).filter((top) => top !== undefined)
  if (tops.length === 0) return Number.MAX_SAFE_INTEGER
  return tops.reduce((total, top) => total + top, 0) / tops.length
}

function collectRoutes(analysis) {
  const byId = new Map(analysis.nodes.map((node) => [node.id, node]))
  const handledBy = new Map()
  analysis.edges.forEach((edge) => {
    const source = byId.get(edge.source)
    const target = byId.get(edge.target)
    if (source?.kind === 'endpoint' && target?.kind === 'backend') handledBy.set(source.id, target)
  })

  const groups = new Map()
  analysis.nodes.filter((node) => node.kind === 'endpoint').forEach((endpoint) => {
    const handler = handledBy.get(endpoint.id)
    const technology = endpoint.technology || handler?.technology || 'Backend'
    const [method, ...rest] = endpoint.label.split(' ')
    const route = {
      id: endpoint.id,
      method,
      path: rest.join(' ') || endpoint.label,
      handler: handler?.label ?? 'Unresolved handler',
      filePath: handler?.filePath ?? null,
      line: handler?.line ?? null,
      technology,
    }
    const group = groups.get(technology) ?? []
    group.push(route)
    groups.set(technology, group)
  })

  return [...groups.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([technology, routes]) => ({
      technology,
      routes: routes.sort((left, right) => (
        left.path.localeCompare(right.path) || left.method.localeCompare(right.method)
      )),
    }))
}

function collectModules(analysis) {
  const byId = new Map(analysis.nodes.map((node) => [node.id, node]))
  const requests = new Map()
  analysis.edges.forEach((edge) => {
    if (byId.get(edge.source)?.kind === 'frontend') requests.set(edge.source, edge)
  })

  const modules = new Map()
  analysis.nodes.filter((node) => node.kind === 'frontend').forEach((call) => {
    const path = call.filePath || 'Unknown source'
    const entry = modules.get(path) ?? {
      id: `module:${path}`,
      path,
      name: fileName(path),
      directory: directory(path),
      callCount: 0,
      targets: [],
    }
    entry.callCount += 1

    const request = requests.get(call.id)
    const destination = request ? byId.get(request.target) : null
    if (destination) {
      const existing = entry.targets.find((target) => target.id === destination.id)
      const method = request.label || 'HTTP'
      if (existing) existing.methods.add(method)
      else entry.targets.push({ id: destination.id, kind: destination.kind, methods: new Set([method]) })
    }

    modules.set(path, entry)
  })

  return [...modules.values()]
}

function collectExternals(analysis) {
  return analysis.nodes.filter((node) => node.kind === 'unmatched').map((node) => {
    const [method, ...rest] = node.label.split(' ')
    return { id: node.id, method, path: rest.join(' ') || node.label }
  })
}

export function buildArchitecture(analysis) {
  const routeGroups = collectRoutes(analysis)
  const modules = collectModules(analysis)
  const externals = collectExternals(analysis)

  const nodes = []
  const edges = []

  // --- column widths -------------------------------------------------------
  const clientGroupWidth = MODULE.width + GROUP_PAD_X * 2
  const serviceGroupWidth = ROUTE.width + GROUP_PAD_X * 2
  const externalGroupWidth = EXTERNAL.width + GROUP_PAD_X * 2

  const actorX = 0
  const clientX = actorX + ACTOR.width + COLUMN_GAP
  const serviceX = clientX + clientGroupWidth + COLUMN_GAP
  const externalX = serviceX + serviceGroupWidth + COLUMN_GAP

  // --- column heights ------------------------------------------------------
  const clientHeight = groupHeight(modules.length, MODULE.height)
  const serviceHeights = routeGroups.map((group) => groupHeight(group.routes.length, ROUTE.height))
  const serviceColumnHeight = serviceHeights.length > 0
    ? serviceHeights.reduce((total, height) => total + height, 0) + (serviceHeights.length - 1) * GROUP_GAP
    : groupHeight(0, ROUTE.height)
  const externalHeight = groupHeight(externals.length, EXTERNAL.height)

  const contentHeight = Math.max(clientHeight, serviceColumnHeight)
  const clientY = (contentHeight - clientHeight) / 2
  const serviceColumnY = (contentHeight - serviceColumnHeight) / 2
  const externalY = (contentHeight - externalHeight) / 2

  // --- project boundary ----------------------------------------------------
  const boundaryX = clientX - BOUNDARY_PAD
  const boundaryY = Math.min(clientY, serviceColumnY) - BOUNDARY_PAD - BOUNDARY_LABEL
  const boundaryWidth = serviceX + serviceGroupWidth + BOUNDARY_PAD - boundaryX
  const boundaryHeight = Math.max(clientY + clientHeight, serviceColumnY + serviceColumnHeight)
    - Math.min(clientY, serviceColumnY) + BOUNDARY_PAD * 2 + BOUNDARY_LABEL

  nodes.push({
    id: 'boundary',
    type: 'archBoundary',
    position: { x: boundaryX, y: boundaryY },
    style: { width: boundaryWidth, height: boundaryHeight },
    data: { label: `${analysis.projectName} · application boundary` },
    zIndex: Z.boundary,
    draggable: false,
    selectable: false,
    focusable: false,
  })

  // --- client actor --------------------------------------------------------
  nodes.push({
    id: 'actor:client',
    type: 'archActor',
    position: { x: actorX, y: clientY + clientHeight / 2 - ACTOR.height / 2 },
    style: { width: ACTOR.width, height: ACTOR.height },
    data: { label: 'Client apps', detail: 'Browser / consumer' },
    zIndex: Z.component,
    draggable: false,
  })

  // --- service groups, positioned first so modules can sort against them ----
  const routeTops = new Map()
  let cursor = serviceColumnY
  routeGroups.forEach((group, index) => {
    const height = serviceHeights[index]
    nodes.push({
      id: `group:service:${group.technology}`,
      type: 'archGroup',
      position: { x: serviceX, y: cursor },
      style: { width: serviceGroupWidth, height },
      data: {
        tone: 'backend',
        title: group.technology,
        caption: `${group.routes.length} ${group.routes.length === 1 ? 'route' : 'routes'}`,
        footnote: 'Service',
      },
      zIndex: Z.group,
      draggable: false,
      selectable: false,
      focusable: false,
    })

    group.routes.forEach((route, rowIndex) => {
      const top = cursor + GROUP_HEADER + rowIndex * (ROUTE.height + ROW_GAP)
      routeTops.set(route.id, top)
      nodes.push({
        id: route.id,
        type: 'archRoute',
        position: { x: serviceX + GROUP_PAD_X, y: top },
        style: { width: ROUTE.width, height: ROUTE.height },
        data: route,
        zIndex: Z.component,
        draggable: false,
      })
    })

    cursor += height + GROUP_GAP
  })

  if (routeGroups.length === 0) {
    nodes.push({
      id: 'group:service:none',
      type: 'archGroup',
      position: { x: serviceX, y: serviceColumnY },
      style: { width: serviceGroupWidth, height: serviceColumnHeight },
      data: { tone: 'backend', title: 'Services', caption: 'none detected', footnote: 'Service', empty: true },
      zIndex: Z.group,
      draggable: false,
      selectable: false,
      focusable: false,
    })
  }

  // --- external group ------------------------------------------------------
  if (externals.length > 0) {
    nodes.push({
      id: 'group:external',
      type: 'archGroup',
      position: { x: externalX, y: externalY },
      style: { width: externalGroupWidth, height: externalHeight },
      data: {
        tone: 'unmatched',
        title: 'External / unresolved',
        caption: `${externals.length} ${externals.length === 1 ? 'call' : 'calls'}`,
        footnote: 'Outside project',
      },
      zIndex: Z.group,
      draggable: false,
      selectable: false,
      focusable: false,
    })

    externals.forEach((external, index) => {
      const top = externalY + GROUP_HEADER + index * (EXTERNAL.height + ROW_GAP)
      routeTops.set(external.id, top)
      nodes.push({
        id: external.id,
        type: 'archExternal',
        position: { x: externalX + GROUP_PAD_X, y: top },
        style: { width: EXTERNAL.width, height: EXTERNAL.height },
        data: external,
        zIndex: Z.component,
        draggable: false,
      })
    })
  }

  // --- client group, ordered to reduce edge crossings ----------------------
  const orderedModules = [...modules].sort((left, right) => (
    barycentre(left, routeTops) - barycentre(right, routeTops) || left.path.localeCompare(right.path)
  ))

  nodes.push({
    id: 'group:client',
    type: 'archGroup',
    position: { x: clientX, y: clientY },
    style: { width: clientGroupWidth, height: clientHeight },
    data: {
      tone: 'frontend',
      title: 'Client modules',
      caption: `${modules.length} ${modules.length === 1 ? 'source file' : 'source files'}`,
      footnote: 'Namespace',
      empty: modules.length === 0,
    },
    zIndex: Z.group,
    draggable: false,
    selectable: false,
    focusable: false,
  })

  orderedModules.forEach((module, index) => {
    nodes.push({
      id: module.id,
      type: 'archModule',
      position: { x: clientX + GROUP_PAD_X, y: clientY + GROUP_HEADER + index * (MODULE.height + ROW_GAP) },
      style: { width: MODULE.width, height: MODULE.height },
      data: module,
      zIndex: Z.component,
      draggable: false,
    })

    module.targets.forEach((target) => {
      const methods = [...target.methods].sort()
      edges.push({
        id: `call:${module.id}:${target.id}`,
        source: module.id,
        target: target.id,
        label: methods.join(' · '),
        data: { method: methods[0], unresolved: target.kind === 'unmatched' },
        zIndex: Z.component,
      })
    })
  })

  edges.push({
    id: 'call:actor:client',
    source: 'actor:client',
    target: 'group:client',
    label: 'uses',
    data: { method: 'USES', unresolved: false },
    zIndex: Z.component,
  })

  const width = (externals.length > 0 ? externalX + externalGroupWidth : serviceX + serviceGroupWidth + BOUNDARY_PAD)
  const height = Math.max(contentHeight, boundaryY + boundaryHeight)

  return {
    nodes,
    edges,
    bounds: { width, height },
    summary: {
      modules: modules.length,
      calls: analysis.nodes.filter((node) => node.kind === 'frontend').length,
      routes: routeGroups.reduce((total, group) => total + group.routes.length, 0),
      services: routeGroups.length,
      externals: externals.length,
    },
  }
}
