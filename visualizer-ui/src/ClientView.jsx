import { useMemo } from 'react'

import { buildCapabilities } from './client/capabilities'
import { Glyph } from './architecture/icons'

function connectionNote(capability) {
  if (capability.missing === 0) {
    const [first, ...rest] = capability.services
    if (!first) return `${capability.calls} ${capability.calls === 1 ? 'request' : 'requests'}`
    const service = rest.length > 0 ? `${rest.length + 1} services` : first
    return `Talks to ${service}`
  }
  if (capability.connected === 0) return 'Not connected yet'
  return `${capability.missing} of ${capability.calls} not connected`
}

export default function ClientView({ analysis, presenting, onTogglePresent }) {
  const { capabilities, services, totals } = useMemo(
    () => buildCapabilities(analysis),
    [analysis],
  )

  return (
    <section className={`client-stage ${presenting ? 'client-stage--presenting' : ''}`} aria-label="What this application does">
      <div className="client-stage__inner">
        <header className="client-intro">
          <h1>{analysis.projectName}</h1>
          <p>
            {totals.capabilities === 1
              ? 'One part of this product talks to a server.'
              : `${totals.capabilities} parts of this product talk to a server.`}
            {totals.services > 0 && ` They are served by ${services.join(' and ')}.`}
          </p>
        </header>

        <ul className="capability-grid">
          {capabilities.map((capability, index) => (
            <li
              key={capability.id}
              className={`capability ${capability.missing > 0 ? 'capability--gap' : ''}`}
              style={{ '--index': index }}
            >
              <Glyph name={capability.glyph} className="capability__icon" />
              <h2>{capability.name}</h2>
              <p>{capability.summary}</p>
              <span className="capability__note">{connectionNote(capability)}</span>
            </li>
          ))}
        </ul>

        {capabilities.length === 0 && (
          <p className="client-empty">Nothing here talks to a server yet.</p>
        )}

        <footer className="client-summary">
          <span>
            {totals.connected} of {totals.calls} connections are live
          </span>
          {totals.missing > 0 && (
            <span className="client-summary__gap">
              {totals.missing} still {totals.missing === 1 ? 'needs' : 'need'} a server to answer
            </span>
          )}
        </footer>
      </div>

      <button type="button" className="client-present" onClick={onTogglePresent}>
        {presenting ? 'Leave full screen' : 'Full screen'}
      </button>
    </section>
  )
}
