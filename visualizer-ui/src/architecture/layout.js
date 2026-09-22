// Turns a ProjectGraph into an absolutely positioned architecture diagram.
//
// Two rules keep it readable. Groups pack their contents into a grid so the
// drawing stays wide and shallow instead of growing into one tall column, and
// edges connect a module to a whole service rather than to each route, so the
// arrows show how layers talk while the cards carry the detail.

const ACTOR = { width: 150, height: 96 }
const MODULE = { width: 200, height: 66 }
const ROUTE = { width: 248, height: 66 }
const EXTERNAL = { width: 248, height: 66 }

const GROUP_PAD_X = 18
const GROUP_HEADER = 52
const GROUP_PAD_BOTTOM = 18
const ROW_GAP = 14
const CELL_GAP = 16
const GROUP_GAP = 30
const COLUMN_GAP = 128
const BOUNDARY_PAD = 34
const BOUNDARY_LABEL = 20

const Z = { boundary: 0, group: 1, component: 2 }

function fileName(path) {
  return path.split('/').at(-1) || path
}

function directory(path) {
  const cut = path.lastIndexOf('/')
  return cut < 0 ? 'Project root' : path.slice(0, cut)
}

// Keep groups closer to a landscape block than a stack.
function columnsFor(count) {
  if (count <= 3) return 1
  if (count <= 8) return 2
  return 3
}

function gridSize(count, item, columns) {
  const rows = Math.max(1, Math.ceil(count / columns))
  return {
    width: columns * item.width + (columns - 1) * CELL_GAP + GROUP_PAD_X * 2,
    height: GROUP_HEADER + rows * item.height + (rows - 1) * ROW_GAP + GROUP_PAD_BOTTOM,
  }
}

function cellPosition(index, item, columns, originX, originY) {
  const row = Math.floor(index / columns)
  const column = index % columns
  return {
    x: originX + GROUP_PAD_X + column * (item.width + CELL_GAP),
    y: originY + GROUP_HEADER + row * (item.height + ROW_GAP),
  }
}

function collectServices(analysis) {
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
    const group = groups.get(technology) ?? []
    group.push({
      id: endpoint.id,
      method,
      path: rest.join(' ') || endpoint.label,
      handler: handler?.label ?? 'No handler found',
      filePath: handler?.filePath ?? null,
      line: handler?.line ?? null,
      technology,
    })
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
      // technology -> number of calls, so one edge can stand for many calls
      reaches: new Map(),
      unresolved: 0,
    }
    entry.callCount += 1

    const request = requests.get(call.id)
    const destination = request ? byId.get(request.target) : null
    if (destination?.kind === 'endpoint') {
      const technology = destination.technology || 'Backend'
      entry.reaches.set(technology, (entry.reaches.get(technology) ?? 0) + 1)
    } else if (destination?.kind === 'unmatched') {
      entry.unresolved += 1
    }

    modules.set(path, entry)
  })

  return [...modules.values()].sort((left, right) => left.path.localeCompare(right.path))
}

function collectExternals(analysis) {
  const byId = new Map(analysis.nodes.map((node) => [node.id, node]))
  const origin = new Map()
  analysis.edges.forEach((edge) => {
    const source = byId.get(edge.source)
    const target = byId.get(edge.target)
    if (source?.kind === 'frontend' && target?.kind === 'unmatched') origin.set(target.id, source)
  })

  return analysis.nodes.filter((node) => node.kind === 'unmatched').map((node) => {
    const [method, ...rest] = node.label.split(' ')
    const caller = origin.get(node.id)
    return {
      id: node.id,
      method,
      path: rest.join(' ') || node.label,
      // The card carries its caller so no edge has to cross the diagram to say it.
      caller: caller?.filePath ? `${fileName(caller.filePath)}:${caller.line ?? '?'}` : 'Unknown caller',
    }
  })
}

export function buildArchitecture(analysis) {
  const services = collectServices(analysis)
  const modules = collectModules(analysis)
  const externals = collectExternals(analysis)

  const nodes = []
  const edges = []

  // --- sizes ---------------------------------------------------------------
  const routeColumns = columnsFor(Math.max(0, ...services.map((group) => group.routes.length)))
  // Unresolved calls sit in one wide row under the boundary, not a tall stack.
  const externalColumns = Math.min(Math.max(externals.length, 1), 3)

  const moduleColumns = columnsFor(modules.length)
  const clientSize = gridSize(Math.max(modules.length, 1), MODULE, moduleColumns)
  const serviceSizes = services.map((group) => gridSize(group.routes.length, ROUTE, routeColumns))
  const serviceWidth = Math.max(
    gridSize(1, ROUTE, routeColumns).width,
    ...serviceSizes.map((size) => size.width),
  )
  const serviceColumnHeight = serviceSizes.length > 0
    ? serviceSizes.reduce((total, size) => total + size.height, 0) + (serviceSizes.length - 1) * GROUP_GAP
    : gridSize(0, ROUTE, 1).height
  const externalSize = gridSize(Math.max(externals.length, 1), EXTERNAL, externalColumns)

  // --- columns -------------------------------------------------------------
  const actorX = 0
  const clientX = actorX + ACTOR.width + COLUMN_GAP
  const serviceX = clientX + clientSize.width + COLUMN_GAP

  const insideHeight = Math.max(clientSize.height, serviceColumnHeight)
  const clientY = (insideHeight - clientSize.height) / 2
  const serviceColumnY = (insideHeight - serviceColumnHeight) / 2

  // --- boundary ------------------------------------------------------------
  const boundaryX = clientX - BOUNDARY_PAD
  const boundaryY = -BOUNDARY_PAD
  const boundaryWidth = serviceX + serviceWidth + BOUNDARY_PAD - boundaryX
  const boundaryHeight = insideHeight + BOUNDARY_PAD * 2 + BOUNDARY_LABEL

  nodes.push({
    id: 'boundary',
    type: 'archBoundary',
    position: { x: boundaryX, y: boundaryY },
    style: { width: boundaryWidth, height: boundaryHeight },
    data: { label: analysis.projectName },
    zIndex: Z.boundary,
    draggable: false,
    selectable: false,
    focusable: false,
  })

  // Unresolved work sits below the boundary, so its edge never crosses a service.
  const externalY = boundaryY + boundaryHeight + GROUP_GAP + 16
  // Sits under the client column so its edge drops almost straight down, and
  // the bottom-right stays clear for the legend.
  const externalX = clientX

  // --- client actor --------------------------------------------------------
  nodes.push({
    id: 'actor:client',
    type: 'archActor',
    position: { x: actorX, y: clientY + clientSize.height / 2 - ACTOR.height / 2 },
    style: { width: ACTOR.width, height: ACTOR.height },
    data: { label: 'Client apps', detail: 'Browser' },
    zIndex: Z.component,
    draggable: false,
  })

  // --- client modules ------------------------------------------------------
  nodes.push({
    id: 'group:client',
    type: 'archGroup',
    position: { x: clientX, y: clientY },
    style: { width: clientSize.width, height: clientSize.height },
    data: {
      tone: 'frontend',
      title: 'Client',
      caption: modules.length === 1 ? '1 source file' : `${modules.length} source files`,
      empty: modules.length === 0,
    },
    zIndex: Z.group,
    draggable: false,
    selectable: false,
    focusable: false,
  })

  modules.forEach((module, index) => {
    nodes.push({
      id: module.id,
      type: 'archModule',
      position: cellPosition(index, MODULE, moduleColumns, clientX, clientY),
      style: { width: MODULE.width, height: MODULE.height },
      data: {
        path: module.path,
        name: module.name,
        directory: module.directory,
        callCount: module.callCount,
      },
      zIndex: Z.component,
      draggable: false,
    })
  })

  // --- services ------------------------------------------------------------
  let cursor = serviceColumnY
  services.forEach((group, index) => {
    const size = serviceSizes[index]
    const groupId = `group:service:${group.technology}`

    nodes.push({
      id: groupId,
      type: 'archGroup',
      position: { x: serviceX, y: cursor },
      style: { width: serviceWidth, height: size.height },
      data: {
        tone: 'backend',
        technology: group.technology,
        title: group.technology,
        caption: group.routes.length === 1 ? '1 route' : `${group.routes.length} routes`,
      },
      zIndex: Z.group,
      draggable: false,
      selectable: false,
      focusable: false,
    })

    group.routes.forEach((route, rowIndex) => {
      nodes.push({
        id: route.id,
        type: 'archRoute',
        position: cellPosition(rowIndex, ROUTE, routeColumns, serviceX, cursor),
        style: { width: ROUTE.width, height: ROUTE.height },
        data: route,
        zIndex: Z.component,
        draggable: false,
      })
    })

    cursor += size.height + GROUP_GAP
  })

  if (services.length === 0) {
    nodes.push({
      id: 'group:service:none',
      type: 'archGroup',
      position: { x: serviceX, y: serviceColumnY },
      style: { width: serviceWidth, height: serviceColumnHeight },
      data: { tone: 'backend', title: 'Services', caption: 'none found', empty: true },
      zIndex: Z.group,
      draggable: false,
      selectable: false,
      focusable: false,
    })
  }

  // --- unresolved ----------------------------------------------------------
  if (externals.length > 0) {
    nodes.push({
      id: 'group:external',
      type: 'archGroup',
      position: { x: externalX, y: externalY },
      style: { width: externalSize.width, height: externalSize.height },
      data: {
        tone: 'unmatched',
        title: 'Outside the project',
        caption: externals.length === 1 ? '1 call with no match' : `${externals.length} calls with no match`,
      },
      zIndex: Z.group,
      draggable: false,
      selectable: false,
      focusable: false,
    })

    externals.forEach((external, index) => {
      nodes.push({
        id: external.id,
        type: 'archExternal',
        position: cellPosition(index, EXTERNAL, externalColumns, externalX, externalY),
        style: { width: EXTERNAL.width, height: EXTERNAL.height },
        data: external,
        zIndex: Z.component,
        draggable: false,
      })
    })

    const unresolvedTotal = modules.reduce((total, module) => total + module.unresolved, 0)
    edges.push({
      id: 'call:client:external',
      source: 'group:client',
      target: 'group:external',
      sourceHandle: 'bottom',
      targetHandle: 'top',
      label: unresolvedTotal === 1 ? '1 call' : `${unresolvedTotal} calls`,
      data: { unresolved: true },
      zIndex: Z.component,
    })
  }

  // --- edges ---------------------------------------------------------------
  edges.push({
    id: 'call:actor:client',
    source: 'actor:client',
    target: 'group:client',
    label: 'uses',
    data: { unresolved: false },
    zIndex: Z.component,
  })

  const callsPerService = new Map()
  modules.forEach((module) => {
    module.reaches.forEach((count, technology) => {
      callsPerService.set(technology, (callsPerService.get(technology) ?? 0) + count)
    })
  })

  services.forEach((group) => {
    const count = callsPerService.get(group.technology) ?? 0
    edges.push({
      id: `call:client:${group.technology}`,
      source: 'group:client',
      sourceHandle: 'right',
      target: `group:service:${group.technology}`,
      label: count === 1 ? '1 call' : `${count} calls`,
      data: { unresolved: false },
      zIndex: Z.component,
    })
  })

  const width = serviceX + serviceWidth + BOUNDARY_PAD
  const height = Math.max(boundaryY + boundaryHeight, externals.length > 0 ? externalY + externalSize.height : 0)

  return {
    nodes,
    edges,
    bounds: { width, height },
    summary: {
      modules: modules.length,
      calls: analysis.nodes.filter((node) => node.kind === 'frontend').length,
      routes: services.reduce((total, group) => total + group.routes.length, 0),
      services: services.length,
      externals: externals.length,
    },
  }
}
