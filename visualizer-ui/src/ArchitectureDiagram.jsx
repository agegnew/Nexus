import { memo, useCallback, useEffect, useMemo, useState } from 'react'
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
} from '@xyflow/react'

import { buildArchitecture } from './architecture/layout'

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
        <strong>{data.title}</strong>
        <small>{data.caption}</small>
      </header>
      {data.empty && <p className="arch-group__empty">Nothing detected</p>}
      <span className="arch-group__footnote">{data.footnote}</span>
    </div>
  )
}

function ArchActor({ data }) {
  return (
    <div className="arch-actor">
      <strong>{data.label}</strong>
      <small>{data.detail}</small>
      <Handle type="source" position={Position.Right} className="arch-handle" />
    </div>
  )
}

function ArchModule({ data }) {
  return (
    <article className="arch-card arch-card--frontend" title={data.path}>
      <div className="arch-card__row">
        <strong>{data.name}</strong>
        <span className="arch-card__count">{data.callCount}</span>
      </div>
      <small className="arch-card__path">{data.directory}</small>
      <Handle type="source" position={Position.Right} className="arch-handle" />
    </article>
  )
}

function ArchRoute({ data }) {
  return (
    <article className="arch-card arch-card--backend" title={data.filePath ? `${data.filePath}:${data.line}` : data.path}>
      <Handle type="target" position={Position.Left} className="arch-handle" />
      <div className="arch-card__row">
        <span className={`arch-method arch-method--${data.method.toLowerCase()}`}>{data.method}</span>
        <code className="arch-card__route">{data.path}</code>
      </div>
      <small className="arch-card__handler">
        {data.handler}
        {data.line ? <span className="arch-card__line">:{data.line}</span> : null}
      </small>
    </article>
  )
}

function ArchExternal({ data }) {
  return (
    <article className="arch-card arch-card--unmatched" title={data.path}>
      <Handle type="target" position={Position.Left} className="arch-handle" />
      <div className="arch-card__row">
        <span className={`arch-method arch-method--${data.method.toLowerCase()}`}>{data.method}</span>
        <code className="arch-card__route">{data.path}</code>
      </div>
      <small className="arch-card__handler">No backend match</small>
    </article>
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

const FIT_OPTIONS = { padding: 0.14, maxZoom: 1 }

function plural(count, singular, multiple = `${singular}s`) {
  return `${count} ${count === 1 ? singular : multiple}`
}

export default function ArchitectureDiagram({ analysis }) {
  const [flowInstance, setFlowInstance] = useState(null)
  const { nodes, edges, summary } = useMemo(() => buildArchitecture(analysis), [analysis])

  const styledEdges = useMemo(() => edges.map((edge) => ({
    ...edge,
    type: 'smoothstep',
    className: `arch-edge${edge.data?.unresolved ? ' arch-edge--unresolved' : ''}`,
    labelShowBg: false,
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
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
        <Background color="oklch(31% 0.01 185)" gap={28} size={1} />
        <Controls position="bottom-left" showInteractive={false} />
      </ReactFlow>

      <div className="arch-key" aria-label="Architecture legend">
        <span><i className="arch-key__frontend" />Client module</span>
        <span><i className="arch-key__backend" />Route → handler</span>
        {summary.externals > 0 && <span><i className="arch-key__unmatched" />Unresolved</span>}
      </div>
    </section>
  )
}
