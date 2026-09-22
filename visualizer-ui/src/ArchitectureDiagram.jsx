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
      <Handle type="target" position={Position.Top} id="top" className="arch-handle" />
      <Handle type="source" position={Position.Right} id="right" className="arch-handle" />
      <Handle type="source" position={Position.Bottom} id="bottom" className="arch-handle" />
      <header className="arch-group__header">
        {data.technology && (
          <Glyph name={glyphForTechnology(data.technology)} className="arch-group__icon" />
        )}
        <strong>{data.title}</strong>
        <small>{data.caption}</small>
      </header>
      {data.empty && <p className="arch-group__empty">Nothing found yet</p>}
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
    <figure className="arch-figure arch-figure--row" title={data.path}>
      <Glyph name="module" className="arch-figure__icon" />
      <figcaption>
        <strong>{data.name}</strong>
        <small>{data.directory}</small>
      </figcaption>
    </figure>
  )
}

function ArchRoute({ data }) {
  return (
    <figure className="arch-figure arch-figure--row" title={data.filePath ? `${data.filePath}:${data.line}` : data.path}>
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
    <figure className="arch-figure arch-figure--row" title={data.path}>
      <Handle type="target" position={Position.Left} className="arch-handle" />
      <Glyph name="external" className="arch-figure__icon" />
      <figcaption>
        <span className="arch-route">
          <b className={`arch-method arch-method--${data.method.toLowerCase()}`}>{data.method}</b>
          <code>{data.path}</code>
        </span>
        <small>called from {data.caller}</small>
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

const FIT_OPTIONS = { padding: 0.1, maxZoom: 1 }

export default function ArchitectureDiagram({ analysis }) {
  const [flowInstance, setFlowInstance] = useState(null)
  const { nodes, edges, summary } = useMemo(() => buildArchitecture(analysis), [analysis])

  const styledEdges = useMemo(() => edges.map((edge) => ({
    ...edge,
    type: 'smoothstep',
    pathOptions: { borderRadius: 12 },
    className: `arch-edge${edge.data?.unresolved ? ' arch-edge--unresolved' : ''}`,
    markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
  })), [edges])

  const fit = useCallback(() => {
    window.requestAnimationFrame(() => flowInstance?.fitView(FIT_OPTIONS))
  }, [flowInstance])

  useEffect(() => { fit() }, [fit, nodes])

  return (
    <section className="architecture-workspace" aria-label="System architecture diagram">
      <ReactFlow
        nodes={nodes}
        edges={styledEdges}
        nodeTypes={nodeTypes}
        onInit={setFlowInstance}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        deleteKeyCode={null}
        minZoom={0.2}
        maxZoom={1.8}
        fitView
        fitViewOptions={FIT_OPTIONS}
        proOptions={{ hideAttribution: true }}
      >
        <Background variant={BackgroundVariant.Dots} color="oklch(30% 0.012 185)" gap={24} size={1.4} />
        <Controls position="bottom-left" showInteractive={false} />
      </ReactFlow>

      <div className="arch-key" aria-label="Legend">
        <span><Glyph name="module" /> Source file</span>
        <span><Glyph name="service" /> Route and handler</span>
        {summary.externals > 0 && <span><Glyph name="external" /> No match found</span>}
      </div>
    </section>
  )
}
