import { lazy, memo, Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
} from '@xyflow/react'

import '@xyflow/react/dist/style.css'
import './App.css'
import { DEMO_FIXTURES, demoGraph, demoIdFromParams } from './demo'

const ArchitectureDiagram = lazy(() => import('./ArchitectureDiagram'))
const ClientView = lazy(() => import('./ClientView'))

const ROOT_ID = 'project-root'
const HORIZONTAL_GAP = 300
const VERTICAL_GAP = 112

const TYPE_LABELS = {
  project: 'Project',
  system: 'System',
  technology: 'Technology',
  file: 'File / module',
  route: 'Endpoint',
}

function TreeNode({ data }) {
  const expandable = data.childCount > 0

  return (
    <article
      className={`tree-node tree-node--${data.tone} ${expandable ? 'tree-node--expandable' : ''}`}
      aria-label={`${TYPE_LABELS[data.treeType]}: ${data.label}`}
    >
      <Handle type="target" position={Position.Left} className="tree-node__handle" />

      <div className="tree-node__topline">
        <span>{TYPE_LABELS[data.treeType]}</span>
        {expandable && (
          <span className="tree-node__disclosure" aria-hidden="true">
            <b>{data.childCount}</b>
            <i>{data.expanded ? '−' : '+'}</i>
          </span>
        )}
      </div>

      <strong title={data.label}>{data.label}</strong>
      <small title={data.subtitle}>{data.subtitle}</small>

      <Handle type="source" position={Position.Right} className="tree-node__handle" />
    </article>
  )
}

const nodeTypes = { tree: memo(TreeNode) }

function groupBy(items, getKey) {
  return items.reduce((groups, item) => {
    const key = getKey(item)
    const group = groups.get(key) ?? []
    group.push(item)
    groups.set(key, group)
    return groups
  }, new Map())
}

function sourceDirectory(filePath) {
  const separator = filePath.lastIndexOf('/')
  return separator < 0 ? 'Project source' : filePath.slice(0, separator)
}

function sourceName(filePath) {
  return filePath.split('/').at(-1) || filePath
}

function fileBranches(items, branchId, tone, makeLeaf) {
  return [...groupBy(items, (item) => item.filePath || 'Unknown source').entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([filePath, fileItems]) => ({
      id: `${branchId}:file:${filePath}`,
      treeType: 'file',
      tone,
      label: sourceName(filePath),
      subtitle: sourceDirectory(filePath),
      children: fileItems.map(makeLeaf),
    }))
}

function buildHierarchy(analysis) {
  const rawNodes = new Map(analysis.nodes.map((node) => [node.id, node]))
  const outgoingEdges = new Map(analysis.edges.map((edge) => [edge.source, edge]))
  const incomingEdges = new Map(analysis.edges.map((edge) => [edge.target, edge]))
  const frontendNodes = analysis.nodes.filter((node) => node.kind === 'frontend')
  const backendNodes = analysis.nodes.filter((node) => node.kind === 'backend')
  const unmatchedNodes = analysis.nodes.filter((node) => node.kind === 'unmatched')

  const frontendFiles = fileBranches(frontendNodes, 'frontend', 'frontend', (node) => {
    const requestEdge = outgoingEdges.get(node.id)
    const destination = rawNodes.get(requestEdge?.target)
    return {
      id: `frontend:request:${node.id}`,
      treeType: 'route',
      tone: destination?.kind === 'unmatched' ? 'unmatched' : 'frontend',
      label: destination?.label ?? `${requestEdge?.label ?? 'HTTP'} request`,
      subtitle: `Called at line ${node.line ?? 'unknown'}`,
      children: [],
    }
  })

  const backendByTechnology = [...groupBy(
    backendNodes,
    (node) => node.technology || 'Backend',
  ).entries()].sort(([left], [right]) => left.localeCompare(right))

  const backendTechnologies = backendByTechnology.map(([technology, technologyNodes]) => ({
    id: `backend:technology:${technology}`,
    treeType: 'technology',
    tone: 'backend',
    label: technology,
    subtitle: `${technologyNodes.length} ${technologyNodes.length === 1 ? 'handler' : 'handlers'}`,
    children: fileBranches(technologyNodes, `backend:${technology}`, 'backend', (node) => {
      const handlerEdge = incomingEdges.get(node.id)
      const route = rawNodes.get(handlerEdge?.source)
      return {
        id: `backend:route:${node.id}`,
        treeType: 'route',
        tone: 'backend',
        label: route?.label ?? node.label,
        subtitle: node.label,
        children: [],
      }
    }),
  }))

  const unmatchedOrigins = new Map()
  frontendNodes.forEach((node) => {
    const requestEdge = outgoingEdges.get(node.id)
    const destination = rawNodes.get(requestEdge?.target)
    if (destination?.kind === 'unmatched') unmatchedOrigins.set(destination.id, node)
  })

  const unmatchedFiles = fileBranches(
    unmatchedNodes.map((node) => ({
      ...node,
      filePath: unmatchedOrigins.get(node.id)?.filePath ?? 'Unknown source',
      sourceLine: unmatchedOrigins.get(node.id)?.line,
    })),
    'unmatched',
    'unmatched',
    (node) => ({
      id: `unmatched:request:${node.id}`,
      treeType: 'route',
      tone: 'unmatched',
      label: node.label,
      subtitle: `No backend match · line ${node.sourceLine ?? 'unknown'}`,
      children: [],
    }),
  )

  const systems = [
    {
      id: 'system:frontend',
      treeType: 'system',
      tone: 'frontend',
      label: 'Frontend',
      subtitle: `${analysis.stats.frontendCalls} detected API ${analysis.stats.frontendCalls === 1 ? 'call' : 'calls'}`,
      children: [{
        id: 'frontend:technology',
        treeType: 'technology',
        tone: 'frontend',
        label: 'React / TypeScript',
        subtitle: `${frontendFiles.length} source ${frontendFiles.length === 1 ? 'file' : 'files'}`,
        children: frontendFiles,
      }],
    },
    {
      id: 'system:backend',
      treeType: 'system',
      tone: 'backend',
      label: 'Backend',
      subtitle: `${analysis.stats.backendEndpoints} detected ${analysis.stats.backendEndpoints === 1 ? 'route' : 'routes'}`,
      children: backendTechnologies,
    },
  ]

  if (unmatchedNodes.length > 0) {
    systems.push({
      id: 'system:unmatched',
      treeType: 'system',
      tone: 'unmatched',
      label: 'Other / unresolved',
      subtitle: `${unmatchedNodes.length} ${unmatchedNodes.length === 1 ? 'call needs' : 'calls need'} review`,
      children: unmatchedFiles,
    })
  }

  return {
    id: ROOT_ID,
    treeType: 'project',
    tone: 'project',
    label: analysis.projectName,
    subtitle: `${systems.length} main ${systems.length === 1 ? 'branch' : 'branches'}`,
    children: systems,
  }
}

function layoutTree(root, expandedIds) {
  const nodes = []
  const edges = []
  let nextRow = 0

  function visit(item, depth, parentId = null) {
    const expanded = expandedIds.has(item.id)
    const visibleChildren = expanded ? item.children : []
    const childRows = visibleChildren.map((child) => visit(child, depth + 1, item.id))
    const row = childRows.length > 0
      ? (childRows[0] + childRows.at(-1)) / 2
      : nextRow++

    nodes.push({
      id: item.id,
      type: 'tree',
      position: { x: 40 + depth * HORIZONTAL_GAP, y: 36 + row * VERTICAL_GAP },
      data: {
        ...item,
        children: undefined,
        childCount: item.children.length,
        expanded,
      },
    })

    if (parentId) {
      edges.push({
        id: `${parentId}→${item.id}`,
        source: parentId,
        target: item.id,
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed },
      })
    }

    return row
  }

  visit(root, 0)
  return { nodes, edges }
}

export default function App() {
  const projectParams = useMemo(() => new URLSearchParams(window.location.search), [])
  const fallbackProjectName = projectParams.get('projectName') || 'Unknown project'
  const fallbackProjectPath = projectParams.get('projectPath') || 'Path unavailable'
  const [demoId, setDemoId] = useState(() => demoIdFromParams(projectParams))
  const [analysis, setAnalysis] = useState(() => (
    window.__CODE_VISUALIZER_GRAPH__ ?? (demoId ? demoGraph(demoId) : null)
  ))
  const [expandedIds, setExpandedIds] = useState(() => new Set([ROOT_ID]))
  const [flowInstance, setFlowInstance] = useState(null)
  const [activeView, setActiveView] = useState(() => {
    const requested = projectParams.get('view')
    return ['code', 'architecture', 'client'].includes(requested) ? requested : 'code'
  })
  const [presenting, setPresenting] = useState(() => projectParams.get('present') === '1')

  useEffect(() => {
    if (!presenting) return undefined
    const leaveOnEscape = (event) => { if (event.key === 'Escape') setPresenting(false) }
    window.addEventListener('keydown', leaveOnEscape)
    return () => window.removeEventListener('keydown', leaveOnEscape)
  }, [presenting])

  useEffect(() => {
    const receiveGraph = (event) => {
      setDemoId(null)
      setAnalysis(event.detail)
      setExpandedIds(new Set([ROOT_ID]))
    }
    window.addEventListener('code-visualizer:graph', receiveGraph)
    return () => window.removeEventListener('code-visualizer:graph', receiveGraph)
  }, [])

  const hierarchy = useMemo(
    () => analysis?.status === 'ready' ? buildHierarchy(analysis) : null,
    [analysis],
  )
  const tree = useMemo(
    () => hierarchy ? layoutTree(hierarchy, expandedIds) : { nodes: [], edges: [] },
    [expandedIds, hierarchy],
  )

  useEffect(() => {
    if (!flowInstance || tree.nodes.length === 0) return undefined
    const frame = window.requestAnimationFrame(() => {
      flowInstance.fitView({ padding: 0.22, duration: 420, maxZoom: 1.05 })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [expandedIds, flowInstance, tree.nodes.length])

  const handleNodeClick = useCallback((_, node) => {
    if (node.data.childCount === 0) return
    setExpandedIds((current) => {
      const next = new Set(current)
      if (next.has(node.id)) next.delete(node.id)
      else next.add(node.id)
      return next
    })
  }, [])

  const collapseAll = useCallback(() => setExpandedIds(new Set([ROOT_ID])), [])

  const selectDemo = useCallback((fixtureId) => {
    setDemoId(fixtureId)
    setAnalysis(demoGraph(fixtureId))
    setExpandedIds(new Set([ROOT_ID]))
  }, [])

  const projectName = analysis?.projectName ?? fallbackProjectName
  const projectPath = analysis?.projectPath ?? fallbackProjectPath
  const isReady = analysis?.status === 'ready'

  return (
    <main className={`visualizer ${presenting ? 'visualizer--presenting' : ''}`}>
      <header className="visualizer-header">
        <div className="project-heading">
          <span className={`analysis-indicator ${analysis ? 'analysis-indicator--ready' : ''}`} aria-hidden="true" />
          <div className="project-heading__text">
            <span className="project-heading__eyebrow">Code Visualizer</span>
            <h1>{projectName}</h1>
            <span className="project-heading__path" title={projectPath}>{projectPath}</span>
          </div>
        </div>

        {isReady && (
          <div className="header-actions">
            <span>{analysis.stats.matchedCalls} of {analysis.stats.frontendCalls} calls matched</span>
            {activeView === 'code' && expandedIds.size > 1 && <button type="button" onClick={collapseAll}>Collapse all</button>}
          </div>
        )}
      </header>

      <nav className="view-switcher" aria-label="Diagram view">
        <button
          type="button"
          className={activeView === 'code' ? 'view-switcher__active' : ''}
          aria-pressed={activeView === 'code'}
          onClick={() => setActiveView('code')}
        >
          Code tree
        </button>
        <button
          type="button"
          className={activeView === 'architecture' ? 'view-switcher__active' : ''}
          aria-pressed={activeView === 'architecture'}
          onClick={() => setActiveView('architecture')}
        >
          Architecture
        </button>
        <button
          type="button"
          className={activeView === 'client' ? 'view-switcher__active' : ''}
          aria-pressed={activeView === 'client'}
          onClick={() => setActiveView('client')}
        >
          Client view
        </button>

        {demoId && (
          <div className="demo-picker" role="group" aria-label="Demo data set">
            <span className="demo-picker__badge">Demo data</span>
            {DEMO_FIXTURES.map((fixture) => (
              <button
                key={fixture.id}
                type="button"
                className={demoId === fixture.id ? 'demo-picker__active' : ''}
                aria-pressed={demoId === fixture.id}
                onClick={() => selectDemo(fixture.id)}
              >
                {fixture.label}
              </button>
            ))}
          </div>
        )}
      </nav>

      {activeView === 'code' && <section className="tree-workspace" aria-label="Application code tree">
        <ReactFlow
          nodes={tree.nodes}
          edges={tree.edges}
          nodeTypes={nodeTypes}
          onInit={setFlowInstance}
          onNodeClick={handleNodeClick}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          deleteKeyCode={null}
          minZoom={0.2}
          maxZoom={1.6}
          fitView
          fitViewOptions={{ padding: 0.22, maxZoom: 1.05 }}
          proOptions={{ hideAttribution: true }}
        >
          <Background color="oklch(31% 0.01 185)" gap={28} size={1} />
          <Controls position="bottom-left" showInteractive={false} />
        </ReactFlow>

        {isReady && (
          <div className="tree-instruction">
            <span aria-hidden="true">↳</span>
            Select a branch to reveal its children
          </div>
        )}

        {!analysis && (
          <div className="analysis-state" role="status">
            <span className="analysis-state__scanner" aria-hidden="true" />
            <strong>Reading project structure</strong>
            <span>Building a simple architecture tree from the project.</span>
          </div>
        )}

        {analysis && !isReady && (
          <div className={`analysis-state analysis-state--${analysis.status}`} role="status">
            <strong>{analysis.status === 'error' ? 'Analysis failed' : 'No architecture detected'}</strong>
            <span>{analysis.message}</span>
            <small>Supported now: fetch, Axios, typed HTTP wrappers, FastAPI, and Spring routes.</small>
          </div>
        )}
      </section>}

      {activeView === 'architecture' && isReady && (
        <Suspense fallback={(
          <section className="architecture-workspace" aria-label="System architecture diagram">
            <div className="analysis-state" role="status">
              <strong>Preparing architecture</strong>
              <span>Loading the architecture renderer.</span>
            </div>
          </section>
        )}>
          <ArchitectureDiagram analysis={analysis} />
        </Suspense>
      )}

      {activeView === 'client' && isReady && (
        <Suspense fallback={(
          <section className="client-stage" aria-label="What this application does">
            <div className="analysis-state" role="status"><strong>Preparing</strong></div>
          </section>
        )}>
          <ClientView
            analysis={analysis}
            presenting={presenting}
            onTogglePresent={() => setPresenting((current) => !current)}
          />
        </Suspense>
      )}

      {activeView === 'client' && !isReady && (
        <section className="client-stage" aria-label="What this application does">
          <div className="analysis-state" role="status">
            <strong>Nothing to show yet</strong>
            <span>{analysis?.message ?? 'Reading the project.'}</span>
          </div>
        </section>
      )}

      {activeView === 'architecture' && !isReady && (
        <section className="architecture-workspace" aria-label="System architecture diagram">
          <div className="analysis-state" role="status">
            <strong>{analysis?.status === 'error' ? 'Analysis failed' : 'Nothing to map yet'}</strong>
            <span>{analysis?.message ?? 'Reading detected services and API relationships.'}</span>
          </div>
        </section>
      )}
    </main>
  )
}
