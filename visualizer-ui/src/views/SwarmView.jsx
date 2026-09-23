import { useCallback, useEffect, useId, useReducer, useRef, useState } from 'react'
import { connectSwarm, hasHost, openSource, params, readPreference, writePreference } from '../bridge'
import { fileName } from '../model'
import './swarm.css'

// The Swarm tab: five AI agents test the running app at once, each in its own browser, streamed
// live into five tiles; a one-screen report follows. The agents live in a local Node runner
// (swarm/run.mjs). Inside the IDE the plugin starts it and tells us its port; in a plain browser
// it is expected on 7070 (`npm run serve` in swarm/), or wherever ?swarm= points.
//
// Before planning, the runner reads the project's own docs (CLAUDE.md, README.md) and looks around
// the app. An optional test account lets the testers sign in; its password stays in this tab's
// memory and in the browsers the agents drive, never in the model's prompt or the replay file.

const DEFAULT_RUNNER = 'http://127.0.0.1:7070'
const DEFAULT_TARGET = 'http://localhost:5174'
const SLOTS = 5

const STATUS_LABEL = {
  queued: 'Waiting',
  running: 'Testing',
  passed: 'Passed',
  warning: 'Passed, with errors',
  failed: 'Failed',
}

const PHASE_LABEL = {
  idle: 'Ready',
  starting: 'Starting browsers',
  scouting: 'Reading the project',
  planning: 'Planning missions',
  running: 'Agents testing',
  reporting: 'Writing report',
  done: 'Finished',
  stopped: 'Stopped',
  error: 'Run failed',
}

const initialState = {
  connection: 'connecting',
  connectionError: '',
  brain: '',
  canThink: false,
  hasReplay: false,
  project: null,
  app: '',
  phase: 'idle',
  message: '',
  replay: false,
  missions: [],
  agents: {},
  report: null,
  startedAt: null,
  finishedAt: null,
}

const LIVE_PHASES = ['starting', 'scouting', 'planning', 'running', 'reporting']

function blankAgent() {
  return { status: 'queued', thought: '', action: '', step: 0, lastCall: null, calls: 0, failedCalls: 0, review: null, testSteps: null }
}

function reduce(state, event) {
  switch (event.type) {
    case 'connecting':
      return { ...state, connection: 'connecting', connectionError: '' }
    case 'offline':
      return { ...state, connection: 'offline', connectionError: event.error ?? '' }
    case 'snapshot': {
      const snapshot = event.state ?? {}
      const agents = Object.fromEntries(Object.entries(snapshot.agents ?? {}).map(([id, agent]) => [
        id, { ...blankAgent(), status: agent.status, thought: agent.thought, action: agent.action, step: agent.step, calls: agent.calls ?? 0, review: agent.review ?? null, testSteps: agent.testSteps ?? null },
      ]))
      const live = LIVE_PHASES.includes(snapshot.phase)
      return {
        ...state,
        connection: 'online',
        connectionError: '',
        brain: event.brain,
        canThink: event.canThink,
        hasReplay: Boolean(event.hasReplay),
        project: event.project ?? state.project,
        app: snapshot.app ?? '',
        phase: snapshot.phase ?? 'idle',
        message: snapshot.message ?? '',
        replay: Boolean(snapshot.replay),
        missions: snapshot.missions ?? [],
        agents,
        report: snapshot.report ?? null,
        startedAt: live ? state.startedAt ?? Date.now() : state.startedAt,
      }
    }
    case 'run': {
      const next = { ...state, phase: event.phase, message: event.message ?? state.message, replay: Boolean(event.replay), project: event.project ?? state.project }
      if (event.phase === 'starting') {
        return { ...next, missions: [], agents: {}, app: '', report: null, startedAt: Date.now(), finishedAt: null }
      }
      if (['done', 'stopped', 'error'].includes(event.phase)) {
        return { ...next, finishedAt: Date.now(), hasReplay: state.hasReplay || event.phase === 'done' }
      }
      return next
    }
    case 'missions':
      return { ...state, missions: event.missions, app: event.app ?? '', agents: Object.fromEntries(event.missions.map((mission) => [mission.id, blankAgent()])) }
    case 'agent': {
      const agent = state.agents[event.id] ?? blankAgent()
      return {
        ...state,
        agents: {
          ...state.agents,
          [event.id]: {
            ...agent,
            status: event.status,
            thought: event.thought || agent.thought,
            action: event.action ?? agent.action,
            step: event.step ?? agent.step,
            review: event.review ?? agent.review,
            testSteps: event.testSteps ?? agent.testSteps,
          },
        },
      }
    }
    case 'network': {
      const agent = state.agents[event.id] ?? blankAgent()
      return {
        ...state,
        agents: {
          ...state.agents,
          [event.id]: {
            ...agent,
            lastCall: { method: event.method, path: event.path, status: event.status },
            calls: agent.calls + 1,
            failedCalls: agent.failedCalls + (event.status >= 400 || event.status === 0 ? 1 : 0),
          },
        },
      }
    }
    case 'report':
      return { ...state, report: event.report }
    default:
      return state
  }
}

function seconds(ms) {
  return `${Math.max(0, Math.round(ms / 1000))}s`
}

function jpeg(data) {
  return data?.startsWith('data:') ? data : `data:image/jpeg;base64,${data}`
}

function CallChip({ call }) {
  if (!call) return null
  const bad = call.status >= 400 || call.status === 0
  return (
    <span className={`swarm-call ${bad ? 'swarm-call--bad' : ''}`} title={`${call.method} ${call.path} → ${call.status || 'no response'}`}>
      <b>{call.method}</b> {call.path} <i>{call.status || '-'}</i>
    </span>
  )
}

function Stars({ rating, label = true }) {
  const full = Math.max(0, Math.min(5, Math.round(rating ?? 0)))
  return (
    <span className="swarm-stars" role="img" aria-label={`${rating} out of 5 stars`} title={`${rating} / 5`}>
      <span aria-hidden="true">{'★'.repeat(full)}<i>{'★'.repeat(5 - full)}</i></span>
      {label && <b>{Number(rating).toFixed(Number.isInteger(rating) ? 0 : 1)}</b>}
    </span>
  )
}

function Review({ review }) {
  return (
    <div className="swarm-review">
      <p className="swarm-review__quote">
        {review.title && <strong>{review.title}. </strong>}
        “{review.review}”
      </p>
      <div className="swarm-review__lists">
        {review.worked.length > 0 && (
          <div>
            <h4>Worked</h4>
            <ul>{review.worked.map((item) => <li key={item}>{item}</li>)}</ul>
          </div>
        )}
        {review.problems.length > 0 && (
          <div>
            <h4>Problems</h4>
            <ul>
              {review.problems.map((problem) => (
                <li key={problem.text}><span className={`swarm-severity swarm-severity--${problem.severity}`}>{problem.severity}</span> {problem.text}</li>
              ))}
            </ul>
          </div>
        )}
        {review.suggestions.length > 0 && (
          <div>
            <h4>Suggestions</h4>
            <ul>{review.suggestions.map((item) => <li key={item}>{item}</li>)}</ul>
          </div>
        )}
      </div>
    </div>
  )
}

const STEP_MARK = { passed: '✓', failed: '✕', skipped: '–', running: '›', pending: '' }

/** The steps as they stand: live results when the agent has sent any, else the plan. */
function currentSteps(mission, agent) {
  if (agent?.testSteps?.length) return agent.testSteps
  return (mission?.steps ?? []).map((step) => ({ ...step, status: 'pending', observed: null }))
}

function StepProgress({ steps }) {
  if (!steps.length) return null
  const index = [steps.findIndex((step) => step.status === 'running'), steps.findIndex((step) => step.status === 'failed')].find((found) => found >= 0) ?? -1
  const shown = index >= 0 ? index : steps.every((step) => step.status === 'passed') ? steps.length - 1 : 0
  return (
    <div className="swarm-progress">
      <span className="swarm-progress__bar" aria-hidden="true">
        {steps.map((step, stepIndex) => <i key={stepIndex} className={`swarm-progress__seg swarm-progress__seg--${step.status}`} />)}
      </span>
      <span className={`swarm-progress__now swarm-progress__now--${steps[shown].status}`}>
        <b>Step {shown + 1}/{steps.length}</b> {steps[shown].do}
      </span>
    </div>
  )
}

function TestCase({ steps }) {
  return (
    <ol className="swarm-case">
      {steps.map((step, index) => (
        <li key={index} className={`swarm-case__step swarm-case__step--${step.status}`}>
          <span className="swarm-case__mark" aria-label={step.status}>{STEP_MARK[step.status] ?? ''}</span>
          <span className="swarm-case__do"><b>{index + 1}.</b> {step.do}</span>
          <span className="swarm-case__expect">Expected: {step.expect || 'the action visibly worked'}</span>
          {step.observed && <span className="swarm-case__seen">{step.status === 'passed' ? 'Seen' : 'Got'}: “{step.observed}”</span>}
        </li>
      ))}
    </ol>
  )
}

function Tile({ index, mission, agent, focused, onFocus, registerImage }) {
  const status = agent?.status ?? 'queued'
  const color = mission?.color ?? 'var(--border-subtle)'
  return (
    <article
      className={`swarm-tile swarm-tile--${status} ${focused ? 'swarm-tile--focus' : ''} ${mission ? '' : 'swarm-tile--empty'}`}
      style={{ '--agent': color }}
      aria-label={mission ? `${mission.persona}: ${STATUS_LABEL[status]}` : `Agent slot ${index + 1}`}
    >
      <header className="swarm-tile__head">
        <span className="swarm-tile__emoji" aria-hidden="true">{mission?.emoji ?? '·'}</span>
        <strong>{mission?.persona ?? `Agent ${index + 1}`}</strong>
        <span className={`swarm-status swarm-status--${status}`}>{STATUS_LABEL[status]}</span>
      </header>

      <button
        type="button"
        className="swarm-tile__screen"
        onClick={onFocus}
        disabled={!mission}
        title={focused ? 'Back to the grid' : 'Enlarge this agent'}
      >
        <img ref={registerImage} alt="" />
        {status === 'queued' && <span className="swarm-tile__boot"><span />{mission ? 'Opening browser' : 'Idle'}</span>}
        {status === 'passed' && <span className="swarm-tile__stamp swarm-tile__stamp--pass" aria-hidden="true">✓</span>}
        {status === 'warning' && <span className="swarm-tile__stamp swarm-tile__stamp--warn" aria-hidden="true">!</span>}
        {status === 'failed' && <span className="swarm-tile__stamp swarm-tile__stamp--fail" aria-hidden="true">✕</span>}
      </button>

      <footer className="swarm-tile__foot">
        <p className="swarm-tile__goal" title={mission?.goal}>{mission?.goal ?? 'Waiting for a mission'}</p>
        <StepProgress steps={currentSteps(mission, agent)} />
        {focused && currentSteps(mission, agent).length > 0 && <TestCase steps={currentSteps(mission, agent)} />}
        <div className="swarm-tile__live">
          {agent?.action && <code className="swarm-tile__action">{agent.step > 0 ? `${agent.step}. ` : ''}{agent.action}</code>}
          <CallChip call={agent?.lastCall} />
        </div>
        {agent?.review
          ? <p className="swarm-tile__thought swarm-tile__thought--review"><Stars rating={agent.review.rating} label={false} /> “{agent.review.review}”</p>
          : agent?.thought && <p className="swarm-tile__thought">“{agent.thought}”</p>}
      </footer>
    </article>
  )
}

function Where({ place, label }) {
  if (!place?.file) return null
  return (
    <button type="button" className="swarm-jump" onClick={() => openSource(place.file, place.line)} title={`${place.file}:${place.line}`}>
      {label} <code>{fileName(place.file)}:{place.line}</code>
    </button>
  )
}

function Report({ report, colors, onZoom, onCopy, copied }) {
  const ok = report.failed === 0
  return (
    <section className="swarm-report" aria-label="Swarm report">
      <header className="swarm-report__head">
        <div className={`swarm-report__score ${ok ? 'swarm-report__score--ok' : ''}`}>
          <strong>{report.passed}<span>/{report.total}</span></strong>
          <small>passed · {seconds(report.durationMs)}</small>
        </div>
        {report.rating && (
          <div className="swarm-report__score">
            <Stars rating={report.rating} />
            <small>average tester rating</small>
          </div>
        )}
        <div className="swarm-report__headline">
          <p>{report.headline}</p>
          <small>
            {report.target} · {report.brain === 'scripted' ? 'scripted journeys' : report.brain}
            {report.missionSource === 'planned' ? ` · planned from ${report.project?.docs?.length ? report.project.docs.join(', ') : 'the Nexus map'}` : ''}
            {report.account ? ` · signed in as ${report.account}` : ''}
          </small>
        </div>
        <button type="button" className="swarm-button swarm-button--quiet" onClick={onCopy}>{copied ? 'Copied' : 'Copy as Markdown'}</button>
      </header>

      {(report.overall || report.app) && (
        <div className="swarm-report__overall">
          {report.app && <p className="swarm-report__app">{report.app}</p>}
          {report.overall && <p>{report.overall}</p>}
        </div>
      )}

      <ol className="swarm-report__rows">
        {report.agents.map((agent) => (
          <li key={agent.id} className={`swarm-row swarm-row--${agent.status}`} style={{ '--agent': colors[agent.id] }}>
            <span className="swarm-row__mark" aria-label={STATUS_LABEL[agent.status]}>{agent.status === 'passed' ? '✓' : agent.status === 'warning' ? '!' : '✕'}</span>
            <span className="swarm-row__who"><span aria-hidden="true">{agent.emoji}</span> {agent.persona}</span>
            <span className="swarm-row__line">
              {agent.line}
              {agent.cause?.path && (
                <span className="swarm-row__cause">
                  <CallChip call={{ method: agent.cause.method, path: agent.cause.path, status: agent.cause.status }} />
                  {agent.cause.missingBackend && <em>no backend route</em>}
                </span>
              )}
            </span>
            <span className="swarm-row__links">
              <Where place={agent.cause?.frontend} label="Caller" />
              <Where place={agent.cause?.backend} label="Handler" />
            </span>
            {agent.screenshot && (
              <button type="button" className="swarm-row__shot" onClick={() => onZoom(jpeg(agent.screenshot))} title="See what the agent saw last">
                <img src={jpeg(agent.screenshot)} alt={`Last screen of ${agent.persona}`} />
              </button>
            )}
            {agent.testSteps?.length > 0 && (
              <details className="swarm-row__review" open={agent.status === 'failed'}>
                <summary>Test case · {agent.testSteps.filter((step) => step.status === 'passed').length}/{agent.testSteps.length} steps passed</summary>
                <TestCase steps={agent.testSteps} />
              </details>
            )}
            {agent.review && (
              <details className="swarm-row__review" open={agent.status !== 'passed'}>
                <summary><Stars rating={agent.review.rating} label={false} /> {agent.persona}'s review</summary>
                <Review review={agent.review} />
              </details>
            )}
          </li>
        ))}
      </ol>
    </section>
  )
}

function Setup({ project, account, setAccount, focus, setFocus, disabled }) {
  const [reveal, setReveal] = useState(false)
  const passwordId = useId()
  return (
    <div className="swarm-setup">
      <div className="swarm-setup__brief">
        <span className="swarm-setup__label">Brief</span>
        {project?.docs?.length ? (
          <p>The testers read <strong>{project.docs.join(', ')}</strong>{project.routes ? ` and ${project.routes} page routes in the code` : ''}, then look around the app before planning.</p>
        ) : (
          <p>No README or CLAUDE.md found{project?.name ? ` in ${project.name}` : ''}. The testers plan from the Nexus map and what they see in the app.</p>
        )}
      </div>
      <label className="swarm-field">
        <span>Test user (optional)</span>
        <input
          value={account.username}
          onChange={(event) => setAccount((current) => ({ ...current, username: event.target.value }))}
          placeholder="email or username"
          autoComplete="off"
          spellCheck={false}
          disabled={disabled}
        />
      </label>
      <div className="swarm-field">
        <label htmlFor={passwordId}>Password</label>
        <span className="swarm-field__row">
          <input
            id={passwordId}
            type={reveal ? 'text' : 'password'}
            value={account.password}
            onChange={(event) => setAccount((current) => ({ ...current, password: event.target.value }))}
            placeholder="never sent to the AI"
            autoComplete="new-password"
            disabled={disabled}
          />
          <button type="button" className="swarm-field__eye" onClick={() => setReveal((value) => !value)} aria-label={reveal ? 'Hide password' : 'Show password'} title={reveal ? 'Hide' : 'Show'}>
            {reveal ? 'Hide' : 'Show'}
          </button>
        </span>
      </div>
      <label className="swarm-field swarm-field--wide">
        <span>What should they test? (optional)</span>
        <input
          value={focus}
          onChange={(event) => setFocus(event.target.value)}
          placeholder="e.g. sign-up, checkout, the admin dashboard"
          disabled={disabled}
        />
      </label>
    </div>
  )
}

function Offline({ error, embedded, runner }) {
  return (
    <div className="swarm-offline" role="status">
      <strong>Swarm runner is not reachable</strong>
      {error && <p className="swarm-offline__error">{error}</p>}
      {embedded ? (
        <p>Nexus starts it with Node from the <code>swarm/</code> folder. Run <code>npm install</code> there once, then reopen this tab.</p>
      ) : (
        <p>Start it with <code>cd swarm &amp;&amp; npm install &amp;&amp; npm run serve</code>. Looking for it at <code>{runner}</code>.</p>
      )}
    </div>
  )
}

export default function SwarmView({ analysis }) {
  const embedded = hasHost() || params.get('host') === 'intellij'
  const [runner, setRunner] = useState(() => params.get('swarm') || (embedded ? null : DEFAULT_RUNNER))
  const [state, dispatch] = useReducer(reduce, initialState)
  const [target, setTarget] = useState(() => readPreference('swarmTarget', ''))
  const [headed, setHeaded] = useState(() => readPreference('swarmHeaded', false))
  // The user name is remembered; the password lives only in memory for this session.
  const [account, setAccount] = useState(() => ({ username: readPreference('swarmUser', ''), password: '' }))
  const [focusText, setFocusText] = useState(() => readPreference('swarmFocus', ''))
  const [focus, setFocus] = useState(null)
  const [zoom, setZoom] = useState(null)
  const [notice, setNotice] = useState('')
  const [copied, setCopied] = useState(false)
  // Set on click, before the server has answered, so a double click cannot send a second run.
  const [submitting, setSubmitting] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const frames = useRef(new Map())
  const images = useRef(new Map())
  const reportRef = useRef(null)

  useEffect(() => writePreference('swarmTarget', target), [target])
  useEffect(() => writePreference('swarmHeaded', headed), [headed])
  useEffect(() => writePreference('swarmUser', account.username), [account.username])
  useEffect(() => writePreference('swarmFocus', focusText), [focusText])

  // The URL the project's docs or dev config name, unless the user typed one. The demo shop's
  // port counts as "not chosen" for any other project, so a real app is never tested against
  // the demo by accident.
  const suggested = state.project?.suggestedTarget
  const isDemoProject = /nexus-demo/i.test(state.project?.name ?? '')
  const chosen = target.trim() && !(target.trim() === DEFAULT_TARGET && suggested && !isDemoProject)
  const url = chosen ? target.trim() : (suggested || target.trim() || DEFAULT_TARGET)

  // Inside the IDE, ask the plugin for the runner. The bridge may not be installed yet when
  // this mounts, so ask again once the host announces itself.
  useEffect(() => {
    if (!embedded || params.get('swarm')) return undefined
    const answer = (event) => {
      const detail = event.detail ?? {}
      if (detail.ok && detail.port) setRunner(`http://127.0.0.1:${detail.port}`)
      else dispatch({ type: 'offline', error: detail.error || 'The IDE could not start the swarm runner.' })
    }
    const ask = () => connectSwarm()
    window.addEventListener('code-visualizer:swarm', answer)
    window.addEventListener('code-visualizer:host-ready', ask)
    ask()
    return () => {
      window.removeEventListener('code-visualizer:swarm', answer)
      window.removeEventListener('code-visualizer:host-ready', ask)
    }
  }, [embedded])

  const paint = useCallback((id, data) => {
    frames.current.set(id, data)
    const image = images.current.get(id)
    if (image) image.src = jpeg(data)
  }, [])

  useEffect(() => {
    if (!runner) return undefined
    dispatch({ type: 'connecting' })
    const source = new EventSource(`${runner}/events`)
    source.onmessage = (message) => {
      let event
      try { event = JSON.parse(message.data) } catch { return }
      if (event.type === 'frame') return paint(event.id, event.data)
      if (event.type === 'snapshot') {
        Object.entries(event.state?.agents ?? {}).forEach(([id, agent]) => agent.frame && paint(id, agent.frame))
      }
      if (event.type === 'run' && event.phase === 'starting') frames.current.clear()
      dispatch(event)
    }
    source.onerror = () => {
      if (source.readyState !== EventSource.OPEN) dispatch({ type: 'offline', error: '' })
    }
    return () => source.close()
  }, [runner, paint])

  const live = LIVE_PHASES.includes(state.phase)

  useEffect(() => {
    if (!live) return undefined
    const timer = window.setInterval(() => setNow(Date.now()), 500)
    return () => window.clearInterval(timer)
  }, [live])

  useEffect(() => {
    if (!state.report) return undefined
    const frame = window.requestAnimationFrame(() => reportRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
    return () => window.cancelAnimationFrame(frame)
  }, [state.report])

  const call = useCallback(async (path, body) => {
    setNotice('')
    setSubmitting(true)
    try {
      const response = await fetch(`${runner}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body ?? {}),
      })
      if (!response.ok) {
        const reply = await response.json().catch(() => ({}))
        setNotice(reply.error || `The runner answered ${response.status}.`)
      }
    } catch {
      setNotice('The swarm runner did not answer.')
    } finally {
      setSubmitting(false)
    }
  }, [runner])

  const start = useCallback(() => {
    setFocus(null)
    frames.current.clear()
    images.current.forEach((image) => image.removeAttribute('src'))
    const username = account.username.trim()
    call('/run', {
      target: url,
      headed,
      graph: analysis?.status === 'ready' ? analysis : null,
      credentials: username ? { username, password: account.password } : null,
      focus: focusText.trim(),
    })
  }, [account, analysis, call, focusText, headed, url])

  const replay = useCallback(() => {
    setFocus(null)
    frames.current.clear()
    images.current.forEach((image) => image.removeAttribute('src'))
    call('/replay')
  }, [call])

  const copyReport = useCallback(async () => {
    try {
      const markdown = await fetch(`${runner}/report.md`).then((response) => response.text())
      try {
        await navigator.clipboard.writeText(markdown)
      } catch {
        // The embedded browser can refuse the async clipboard; the old path still works there.
        const area = document.createElement('textarea')
        area.value = markdown
        document.body.appendChild(area)
        area.select()
        document.execCommand('copy')
        area.remove()
      }
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    } catch {
      setNotice('Could not copy the report.')
    }
  }, [runner])

  const registerImage = useCallback((id) => (element) => {
    if (!element) return images.current.delete(id)
    images.current.set(id, element)
    const frame = frames.current.get(id)
    if (frame) element.src = jpeg(frame)
    return undefined
  }, [])

  const colors = Object.fromEntries(state.missions.map((mission) => [mission.id, mission.color]))
  const slots = Array.from({ length: Math.max(SLOTS, state.missions.length) }, (_, index) => state.missions[index] ?? null)
  const done = state.missions.filter((mission) => ['passed', 'failed', 'warning'].includes(state.agents[mission.id]?.status)).length
  const elapsed = state.startedAt ? (state.finishedAt ?? now) - state.startedAt : 0
  const offline = state.connection === 'offline' || (!runner && state.connection !== 'online')

  return (
    <section className="swarm-workspace" aria-label="Swarm test">
      <div className="swarm">
        <div className="swarm-bar">
          <div className="swarm-bar__title">
            <span className="swarm-bar__eyebrow">Swarm test</span>
            <strong>5 AI testers use your running app like real users, then review it</strong>
          </div>
          <label className="swarm-field">
            <span>App URL</span>
            <input value={url} onChange={(event) => setTarget(event.target.value)} spellCheck={false} disabled={live} />
          </label>
          <label className="swarm-toggle" title="Also open five real Chromium windows, tiled across the screen">
            <input type="checkbox" checked={headed} onChange={(event) => setHeaded(event.target.checked)} disabled={live} />
            Real windows
          </label>
          <div className="swarm-bar__actions">
            {live ? (
              <button type="button" className="swarm-button swarm-button--stop" onClick={() => call('/stop')}>Stop</button>
            ) : (
              <>
                {state.hasReplay && (
                  <button type="button" className="swarm-button swarm-button--quiet" onClick={replay} disabled={offline || submitting}>Replay last run</button>
                )}
                <button type="button" className="swarm-button swarm-button--go" onClick={start} disabled={offline || submitting}>
                  <span aria-hidden="true">▶</span> Run 5 testers
                </button>
              </>
            )}
          </div>
        </div>

        <Setup project={state.project} account={account} setAccount={setAccount} focus={focusText} setFocus={setFocusText} disabled={live} />

        <div className="swarm-status-line" role="status">
          <span className={`swarm-dot swarm-dot--${offline ? 'off' : live ? 'live' : 'on'}`} aria-hidden="true" />
          <span>{offline ? 'Runner offline' : state.connection === 'connecting' ? 'Connecting to runner' : PHASE_LABEL[state.phase] ?? state.phase}</span>
          {state.replay && <span className="swarm-chip swarm-chip--replay">Replay</span>}
          {state.message && !offline && <span className="swarm-status-line__message">{state.message}</span>}
          {state.missions.length > 0 && <span className="swarm-chip">{done}/{state.missions.length} done</span>}
          {state.startedAt && <span className="swarm-chip">{seconds(elapsed)}</span>}
          {state.brain && !offline && <span className="swarm-chip swarm-chip--brain">{state.brain === 'scripted' ? 'Scripted (no API key)' : state.brain}</span>}
          {analysis?.status === 'ready' && <span className="swarm-chip" title="Failures are traced to code through the Nexus map">Map linked</span>}
        </div>

        {state.app && <p className="swarm-app"><span>The testers' understanding of the app</span>{state.app}</p>}

        {notice && <p className="swarm-notice" role="alert">{notice}</p>}
        {state.phase === 'error' && state.message && <p className="swarm-notice" role="alert">{state.message}</p>}
        {offline && <Offline error={state.connectionError} embedded={embedded} runner={runner ?? DEFAULT_RUNNER} />}

        <div className={`swarm-grid ${focus ? 'swarm-grid--focus' : ''}`}>
          {slots.map((mission, index) => (
            <Tile
              key={mission?.id ?? `slot-${index}`}
              index={index}
              mission={mission}
              agent={mission ? state.agents[mission.id] : null}
              focused={Boolean(mission) && focus === mission.id}
              onFocus={() => mission && setFocus((current) => (current === mission.id ? null : mission.id))}
              registerImage={registerImage(mission?.id ?? `slot-${index}`)}
            />
          ))}
        </div>

        {state.report && (
          <div ref={reportRef}>
            <Report report={state.report} colors={colors} onZoom={setZoom} onCopy={copyReport} copied={copied} />
          </div>
        )}
      </div>

      {zoom && (
        <button type="button" className="swarm-zoom" onClick={() => setZoom(null)} aria-label="Close screenshot">
          <img src={zoom} alt="" />
        </button>
      )}
    </section>
  )
}
