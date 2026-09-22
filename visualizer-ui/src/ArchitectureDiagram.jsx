import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { Glyph, glyphForTechnology } from './architecture/icons'

const MIN_ZOOM = 0.7
const MAX_ZOOM = 1.3
const ZOOM_STEP = 0.1

function plural(count, singular, multiple = `${singular}s`) {
  return `${count} ${count === 1 ? singular : multiple}`
}

function architectureSummary(analysis) {
  const nodesById = new Map(analysis.nodes.map((node) => [node.id, node]))
  const frontend = analysis.nodes.filter((node) => node.kind === 'frontend')
  const endpoints = analysis.nodes.filter((node) => node.kind === 'endpoint')
  const backend = analysis.nodes.filter((node) => node.kind === 'backend')
  const unresolved = analysis.nodes.filter((node) => node.kind === 'unmatched')
  const matched = analysis.edges.filter((edge) => (
    nodesById.get(edge.source)?.kind === 'frontend'
    && nodesById.get(edge.target)?.kind === 'endpoint'
  )).length
  const services = [...backend.reduce((groups, node) => {
    const technology = node.technology || 'Backend'
    const current = groups.get(technology) ?? { name: technology, handlers: 0, files: new Set() }
    current.handlers += 1
    if (node.filePath) current.files.add(node.filePath)
    groups.set(technology, current)
    return groups
  }, new Map()).values()]

  return {
    frontendCalls: frontend.length,
    frontendFiles: new Set(frontend.map((node) => node.filePath).filter(Boolean)).size,
    endpoints: endpoints.length,
    matched,
    services,
    backendFiles: new Set(backend.map((node) => node.filePath).filter(Boolean)).size,
    unresolved: unresolved.length,
  }
}

export default function ArchitectureDiagram({ analysis }) {
  const workspaceRef = useRef(null)
  const [zoom, setZoom] = useState(1)
  const summary = useMemo(() => architectureSummary(analysis), [analysis])
  const serviceCount = Math.max(summary.services.length, 1)
  const serviceGap = Math.min(112, 250 / serviceCount)
  const serviceStart = 350 - ((serviceCount - 1) * serviceGap) / 2

  const changeZoom = (amount) => {
    setZoom((current) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Number((current + amount).toFixed(2)))))
  }

  const fitDiagram = useCallback(() => {
    if (!workspaceRef.current) return
    const widthZoom = (workspaceRef.current.clientWidth - 64) / 1000
    const heightZoom = (workspaceRef.current.clientHeight - 120) / 620
    setZoom(Math.min(1, Math.max(MIN_ZOOM, Number(Math.min(widthZoom, heightZoom).toFixed(2)))))
  }, [])

  useEffect(() => {
    fitDiagram()
    const observer = new ResizeObserver(fitDiagram)
    observer.observe(workspaceRef.current)
    return () => observer.disconnect()
  }, [fitDiagram])

  return (
    <section ref={workspaceRef} className="architecture-workspace" aria-label="System architecture diagram">
      <div className="architecture-heading">
        <span>System map</span>
        <strong>{analysis.projectName}</strong>
      </div>

      <div className="architecture-toolbar" aria-label="Architecture zoom controls">
        <button type="button" onClick={() => changeZoom(-ZOOM_STEP)} aria-label="Zoom out">−</button>
        <button type="button" onClick={fitDiagram} aria-label="Fit diagram to window">{Math.round(zoom * 100)}%</button>
        <button type="button" onClick={() => changeZoom(ZOOM_STEP)} aria-label="Zoom in">+</button>
      </div>

      <div className="architecture-scroll">
        <div
          className="architecture-map-frame"
          style={{ width: 1000 * zoom, height: 620 * zoom }}
        >
          <div className="architecture-map" style={{ transform: `scale(${zoom})` }}>
            <div className="architecture-boundary">
              <span className="architecture-boundary__label">{analysis.projectName} · application boundary</span>
            </div>

            <section className="architecture-zone architecture-zone--client" aria-label="Client layer">
              <header>
                <span>01</span>
                <div><strong>Client layer</strong><small>{plural(summary.frontendFiles, 'source module')}</small></div>
              </header>
            </section>

            <section className="architecture-zone architecture-zone--interface" aria-label="Interface layer">
              <header>
                <span>02</span>
                <div><strong>Interface layer</strong><small>{plural(summary.endpoints, 'detected route')}</small></div>
              </header>
            </section>

            <section className="architecture-zone architecture-zone--services" aria-label="Service layer">
              <header>
                <span>03</span>
                <div><strong>Service layer</strong><small>{plural(summary.backendFiles, 'source module')}</small></div>
              </header>
            </section>

            <article className="architecture-component architecture-component--frontend">
              <span className="architecture-component__type">Application</span>
              <Glyph name="client" className="architecture-component__icon" />
              <strong>Frontend</strong>
              <small>{plural(summary.frontendCalls, 'API call')}</small>
            </article>

            <article className="architecture-component architecture-component--api">
              <span className="architecture-component__type">HTTP interface</span>
              <Glyph name="gateway" className="architecture-component__icon" />
              <strong>API routes</strong>
              <small>{plural(summary.matched, 'matched request')}</small>
            </article>

            <div className="architecture-service-list">
              {summary.services.length > 0 ? summary.services.map((service, index) => (
                <article
                  className="architecture-component architecture-component--service"
                  key={service.name}
                  style={{ top: serviceStart + index * serviceGap - 39 }}
                >
                  <span className="architecture-component__type">Service</span>
                  <Glyph name={glyphForTechnology(service.name)} className="architecture-component__icon" />
                  <strong>{service.name}</strong>
                  <small>{plural(service.handlers, 'handler')}</small>
                </article>
              )) : (
                <article className="architecture-component architecture-component--service architecture-component--empty">
                  <span className="architecture-component__type">Service</span>
                  <Glyph name="server" className="architecture-component__icon" />
                  <strong>No backend detected</strong>
                </article>
              )}
            </div>

            {summary.unresolved > 0 && (
              <section className="architecture-external" aria-label="External or unresolved APIs">
                <span className="architecture-component__type">Outside project</span>
                <Glyph name="external" className="architecture-component__icon" />
                <strong>External / unresolved API</strong>
                <small>{plural(summary.unresolved, 'call')}</small>
              </section>
            )}

            <svg className="architecture-connections" viewBox="0 0 1000 620" aria-hidden="true">
              <defs>
                <marker id="architecture-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" />
                </marker>
                <marker id="architecture-arrow-muted" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" />
                </marker>
              </defs>

              <path className="architecture-connection" d="M 276 350 H 419" markerEnd="url(#architecture-arrow)" />
              <g className="architecture-connection-label" transform="translate(347 331)">
                <rect x="-58" y="-13" width="116" height="26" rx="4" />
                <text textAnchor="middle" dominantBaseline="central">{plural(summary.matched, 'HTTP request')}</text>
              </g>

              {summary.services.map((service, index) => {
                const targetY = serviceStart + index * serviceGap
                return (
                  <g key={service.name}>
                    <path className="architecture-connection" d={`M 581 350 H 650 V ${targetY} H 724`} markerEnd="url(#architecture-arrow)" />
                    <g className="architecture-connection-label" transform={`translate(654 ${targetY - 19})`}>
                      <rect x="-48" y="-13" width="96" height="26" rx="4" />
                      <text textAnchor="middle" dominantBaseline="central">{plural(service.handlers, 'route')}</text>
                    </g>
                  </g>
                )
              })}

              {summary.unresolved > 0 && (
                <>
                  <path className="architecture-connection architecture-connection--unresolved" d="M 188 311 V 118 H 718" markerEnd="url(#architecture-arrow-muted)" />
                  <g className="architecture-connection-label architecture-connection-label--unresolved" transform="translate(448 118)">
                    <rect x="-72" y="-13" width="144" height="26" rx="4" />
                    <text textAnchor="middle" dominantBaseline="central">{plural(summary.unresolved, 'unresolved request')}</text>
                  </g>
                </>
              )}
            </svg>
          </div>
        </div>
      </div>

      <div className="architecture-key" aria-label="Architecture legend">
        <span><i className="architecture-key__frontend" />Client</span>
        <span><i className="architecture-key__api" />Interface</span>
        <span><i className="architecture-key__backend" />Service</span>
        {summary.unresolved > 0 && <span><i className="architecture-key__unresolved" />External</span>}
      </div>
    </section>
  )
}
