import { memo, useCallback, useEffect, useMemo, useState } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
} from '@xyflow/react'

import { buildArchitecture } from './architecture/layout'
import { Glyph, glyphForRoute, glyphForTechnology } from './architecture/icons'

function ArchBoundary({ data }) {
  return (
    <div className="arch-boundary">
      <span className="arch-boundary__label">{data.label}</span>
    </div>
  )
}

function ArchGroup({ data }) {
  return (
    <div className={`arch-group arch-group--${data.tone}`}>
      <Handle type="target" position={Position.Left} className="arch-handle" />
      <header className="arch-group__header">
        {data.technology && (
          <Glyph name={glyphForTechnology(data.technology)} className="arch-group__icon" />
        )}
        <span className="arch-group__title">
          <strong>{data.title}</strong>
          <small>{data.caption}</small>
        </span>
      </header>
      {data.empty && <p className="arch-group__empty">Nothing detected</p>}
      <span className="arch-group__footnote">{data.footnote}</span>
    </div>
  )
}

function ArchActor({ data }) {
  return (
    <figure className="arch-figure">
      <Glyph name="client" className="arch-figure__icon" />
      <figcaption>
        <strong>{data.label}</strong>
        <small>{data.detail}</small>
      </figcaption>
      <Handle type="source" position={Position.Right} className="arch-handle" />
    </figure>
  )
}

function ArchModule({ data }) {
  return (
    <figure className="arch-figure arch-figure--module" title={data.path}>
      <Glyph name="module" className="arch-figure__icon" />
      {data.callCount > 1 && <span className="arch-figure__badge">{data.callCount}</span>}
      <figcaption>
        <strong>{data.name}</strong>
        <small>{data.directory}</small>
      </figcaption>
      <Handle type="source" position={Position.Right} className="arch-handle" />
    </figure>
  )
}

function ArchRoute({ data }) {
  const location = data.filePath ? `${data.filePath}:${data.line}` : data.path

  return (
    <figure className="arch-figure arch-figure--route" title={location}>
      <Handle type="target" position={Position.Left} className="arch-handle" />
      <Glyph name={glyphForRoute(data.technology)} className="arch-figure__icon" />
      <figcaption>
        <span className="arch-route">
          <b className={`arch-method arch-method--${data.method.toLowerCase()}`}>{data.method}</b>
          <code>{data.path}</code>
        </span>
        <small>{data.handler}</small>
      </figcaption>
    </figure>
  )
}

function ArchExternal({ data }) {
  return (
    <figure className="arch-figure arch-figure--external" title={data.path}>
      <Handle type="target" position={Position.Left} className="arch-handle" />
      <Glyph name="external" className="arch-figure__icon" />
      <figcaption>
        <span className="arch-route">
          <b className={`arch-method arch-method--${data.method.toLowerCase()}`}>{data.method}</b>
          <code>{data.path}</code>
        </span>
        <small>No backend match</small>
      </figcaption>
    </figure>
  )
}

const nodeTypes = {
  archBoundary: memo(ArchBoundary),
  archGroup: memo(ArchGroup),
  archActor: memo(ArchActor),
  archModule: memo(ArchModule),
  archRoute: memo(ArchRoute),
  archExternal: memo(ArchExternal),
}

const FIT_OPTIONS = { padding: 0.12, maxZoom: 1 }

function plural(count, singular, multiple = `${singular}s`) {
  return `${count} ${count === 1 ? singular : multiple}`
}

export default function ArchitectureDiagram({ analysis }) {
  const [flowInstance, setFlowInstance] = useState(null)
  const { nodes, edges, summary } = useMemo(() => buildArchitecture(analysis), [analysis])

  const styledEdges = useMemo(() => edges.map((edge) => ({
    ...edge,
    type: 'smoothstep',
    pathOptions: { borderRadius: 14 },
    className: `arch-edge${edge.data?.unresolved ? ' arch-edge--unresolved' : ''}`,
    labelBgPadding: [6, 3],
    labelBgBorderRadius: 3,
    labelBgStyle: { fill: '#ffffff', fillOpacity: 0.92 },
    markerEnd: { type: MarkerType.ArrowClosed, width: 15, height: 15, color: '#2b3340' },
  })), [edges])

  const fit = useCallback(() => {
    window.requestAnimationFrame(() => flowInstance?.fitView(FIT_OPTIONS))
  }, [flowInstance])

  useEffect(() => { fit() }, [fit, nodes])

  return (
    <section className="architecture-workspace" aria-label="System architecture diagram">
      <div className="arch-heading">
        <span>System map</span>
        <strong>{analysis.projectName}</strong>
        <small>
          {plural(summary.modules, 'client module')} · {plural(summary.routes, 'route')} ·{' '}
          {plural(summary.services, 'service')}
          {summary.externals > 0 ? ` · ${plural(summary.externals, 'unresolved call')}` : ''}
        </small>
      </div>

      <ReactFlow
        nodes={nodes}
        edges={styledEdges}
        nodeTypes={nodeTypes}
        onInit={setFlowInstance}
        onNodesInitialized={fit}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        deleteKeyCode={null}
        minZoom={0.15}
        maxZoom={1.6}
        fitView
        fitViewOptions={FIT_OPTIONS}
        proOptions={{ hideAttribution: true }}
      >
        <Background variant={BackgroundVariant.Dots} color="#c9d2dd" gap={22} size={1.2} />
        <Controls position="bottom-left" showInteractive={false} />
      </ReactFlow>

      <div className="arch-key" aria-label="Architecture legend">
        <span><Glyph name="module" /> Client module</span>
        <span><Glyph name="service" /> Route handler</span>
        {summary.externals > 0 && <span><Glyph name="external" /> Unresolved</span>}
      </div>
    </section>
  )
}
