import { useCallback, useEffect, useMemo, useState } from 'react'
import { openSource, readPreference, requestActivity, writePreference } from '../bridge'
import { directory, fileName, plural } from '../model'
import { RANGES, rangeDates } from '../activityRanges'

function Stats({ stats }) {
  return (
    <div className="activity__chips">
      <span className="activity__chip activity__chip--primary">{plural(stats.commits, 'commit')}</span>
      <span className="activity__chip">{plural(stats.files, 'file')}</span>
      <span className="activity__chip activity__added">+{stats.added}</span>
      <span className="activity__chip activity__deleted">−{stats.deleted}</span>
      {stats.authors.length > 1 && <span className="activity__chip">{stats.authors.length} authors</span>}
      {stats.pendingFiles > 0 && (
        <span className="activity__chip activity__chip--pending">{stats.pendingFiles} uncommitted</span>
      )}
    </div>
  )
}

const STATUS_LABEL = {
  modified: 'modified',
  added: 'added',
  deleted: 'deleted',
  renamed: 'renamed',
  copied: 'copied',
  untracked: 'new'
}

/**
 * One changed file. Committed files take the date of the commit that carried them; uncommitted
 * files take the date the file was last saved. Either way every row says when.
 */
function FileRow({ path, status, added, deleted, binary, date, time, staged, approximate }) {
  return (
    <li>
      <span className={`activity__status activity__status--${status}`}>
        {STATUS_LABEL[status] ?? status}
      </span>
      <button type="button" className="activity__path" onClick={() => openSource(path, 1)} title={path}>
        {fileName(path)}
      </button>
      <small className="activity__dir">{directory(path)}</small>
      {staged && <span className="activity__staged">staged</span>}
      <small className="activity__counts">
        {binary ? <span className="activity__binary">binary</span> : (
          <>
            {added > 0 && <span className="activity__added">+{added}</span>}
            {deleted > 0 && <span className="activity__deleted"> −{deleted}</span>}
          </>
        )}
      </small>
      <small className="activity__when" title={approximate ? 'Inferred from the folder, since the file is gone' : undefined}>
        {approximate ? '~' : ''}{date || '—'}{time ? ` ${time}` : ''}
      </small>
    </li>
  )
}

function CommitRow({ commit }) {
  return (
    <li className="activity__commit">
      <div className="activity__commit-head">
        <code className="activity__hash">{commit.shortHash}</code>
        <span className="activity__subject" title={commit.subject}>{commit.subject}</span>
        <small className="activity__when">{commit.date} {commit.time}</small>
      </div>
      <ul className="activity__pending activity__pending--nested">
        {commit.files.slice(0, 12).map((file) => (
          <FileRow key={file.path} {...file} date={commit.date} time={commit.time} />
        ))}
        {commit.files.length > 12 && (
          <li><small className="activity__dir">and {commit.files.length - 12} more files</small></li>
        )}
      </ul>
    </li>
  )
}

function Pending({ changes }) {
  const [open, setOpen] = useState(true)
  const staged = changes.filter((change) => change.staged).length

  return (
    <section className="activity__theme activity__theme--pending">
      <button type="button" className="activity__theme-head" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        <span className={`activity__caret ${open ? 'activity__caret--open' : ''}`} aria-hidden="true">›</span>
        <h3>Uncommitted</h3>
        <span className="activity__count">
          {plural(changes.length, 'file')}{staged > 0 ? ` · ${staged} staged` : ''}
        </span>
      </button>
      <p>Work on disk that is not in the history yet. Dates come from when each file was last saved.</p>
      {open && (
        <ul className="activity__pending">
          {changes.map((change) => <FileRow key={change.path} {...change} />)}
        </ul>
      )}
    </section>
  )
}

function Theme({ theme, byHash }) {
  const [open, setOpen] = useState(false)
  const commits = theme.commits.map((hash) => byHash.get(hash)).filter(Boolean)

  return (
    <section className="activity__theme">
      <button type="button" className="activity__theme-head" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        <span className={`activity__caret ${open ? 'activity__caret--open' : ''}`} aria-hidden="true">›</span>
        <h3>{theme.title}</h3>
        <span className="activity__count">{plural(commits.length || theme.commits.length, 'commit')}</span>
      </button>
      <p>{theme.summary}</p>
      {theme.scopes.length > 0 && (
        <div className="activity__scopes">
          {theme.scopes.map((scope) => <span key={scope} className="activity__chip">{scope}</span>)}
        </div>
      )}
      {open && (
        commits.length > 0
          ? <ul className="activity__commits">{commits.map((commit) => <CommitRow key={commit.hash} commit={commit} />)}</ul>
          : <p className="activity__empty">These commits could not be matched back to the history.</p>
      )}
    </section>
  )
}

export default function ActivityView({ meta, embedded }) {
  const [rangeId, setRangeId] = useState(() => readPreference('activityRange', 'last-week'))
  const [custom, setCustom] = useState(() => rangeDates('last-week') ?? { since: '', until: '' })
  const [scope, setScope] = useState(() => readPreference('activityScope', 'all'))
  const [mine, setMine] = useState(() => readPreference('activityMine', true))
  const [uncommitted, setUncommitted] = useState(() => readPreference('activityUncommitted', true))
  const [report, setReport] = useState(null)

  useEffect(() => writePreference('activityRange', rangeId), [rangeId])
  useEffect(() => writePreference('activityScope', scope), [scope])
  useEffect(() => writePreference('activityMine', mine), [mine])
  useEffect(() => writePreference('activityUncommitted', uncommitted), [uncommitted])

  const scopes = meta?.scopes?.length ? meta.scopes : [{ id: 'all', label: 'All', kind: 'all' }]
  const dates = rangeId === 'custom' ? custom : (rangeDates(rangeId) ?? custom)
  const valid = Boolean(dates.since && dates.until && dates.since <= dates.until)
  // A scope can disappear when the analysis re-runs; fall back rather than request a dead value.
  const selected = scopes.some((option) => option.id === scope) ? scope : 'all'

  const summarize = useCallback(() => {
    if (!valid) return
    setReport({ status: 'loading' })
    if (!requestActivity({ ...dates, scope: selected, mine, uncommitted })) {
      setReport({ status: 'error', message: 'Git summaries run inside the IDE tool window.' })
    }
  }, [dates, selected, mine, uncommitted, valid])

  useEffect(() => {
    const handler = (event) => setReport(event.detail)
    window.addEventListener('code-visualizer:activity', handler)
    return () => window.removeEventListener('code-visualizer:activity', handler)
  }, [])

  const byHash = useMemo(() => {
    const map = new Map()
    ;(report?.commits ?? []).forEach((commit) => map.set(commit.shortHash, commit))
    return map
  }, [report])

  const loading = report?.status === 'loading'

  return (
    <div className="activity-workspace">
      <div className="activity">
        <div className="activity__controls">
          <label className="activity__field">
            <span>Period</span>
            <select className="activity__select" value={rangeId} onChange={(event) => setRangeId(event.target.value)}>
              {RANGES.map((range) => <option key={range.id} value={range.id}>{range.label}</option>)}
            </select>
          </label>

          {rangeId === 'custom' ? (
            <>
              <label className="activity__field">
                <span>From</span>
                <input className="activity__select" type="date" value={custom.since} max={custom.until || undefined}
                       onChange={(event) => setCustom((value) => ({ ...value, since: event.target.value }))} />
              </label>
              <label className="activity__field">
                <span>To</span>
                <input className="activity__select" type="date" value={custom.until} min={custom.since || undefined}
                       onChange={(event) => setCustom((value) => ({ ...value, until: event.target.value }))} />
              </label>
            </>
          ) : (
            <span className="activity__dates">{dates.since} → {dates.until}</span>
          )}

          <label className="activity__field">
            <span>Scope</span>
            <select className="activity__select" value={selected} onChange={(event) => setScope(event.target.value)}>
              {scopes.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
            </select>
          </label>

          <label className="activity__toggle">
            <input type="checkbox" checked={mine} onChange={(event) => setMine(event.target.checked)} />
            Only my commits
          </label>

          <label className="activity__toggle">
            <input type="checkbox" checked={uncommitted} onChange={(event) => setUncommitted(event.target.checked)} />
            Include uncommitted
          </label>

          <button type="button" className="activity__go" onClick={summarize} disabled={loading || !valid}>
            {loading ? 'Summarising…' : 'Summarise'}
          </button>
        </div>

        {embedded && meta && !meta.gitAvailable && (
          <p className="activity__empty">This project is not inside a git repository, so there is no history to read.</p>
        )}

        {!report && (
          <div className="activity__state" role="status">
            <strong>What did you work on?</strong>
            <p>Pick a period and a part of the project, and the commits in that range are read and summarised.</p>
          </div>
        )}

        {loading && (
          <div className="activity__state" role="status">
            <span className="activity__scanner" aria-hidden="true" />
            <strong>Reading your commits</strong>
            <p>{dates.since} to {dates.until}</p>
          </div>
        )}

        {report?.status === 'empty' && (
          <div className="activity__state" role="status">
            <strong>Nothing in this range</strong>
            <p>No commits between {dates.since} and {dates.until}{mine ? ' by you' : ''}{selected !== 'all' ? ` in ${selected.replace('module:', '')}` : ''}.</p>
          </div>
        )}

        {report?.status === 'error' && (
          <div className="activity__state activity__state--error" role="alert">
            <strong>The summary could not be generated</strong>
            <p>{report.message}</p>
          </div>
        )}

        {report?.status === 'ready' && (
          <>
            <div className="activity__summary">
              <p className="activity__headline">{report.headline}</p>
              <Stats stats={report.stats} />
            </div>
            {report.uncommitted?.length > 0 && <Pending changes={report.uncommitted} />}
            {report.themes.map((theme) => <Theme key={theme.title} theme={theme} byHash={byHash} />)}
            {report.themes.length === 0 && report.uncommitted?.length === 0 && (
              <p className="activity__empty">No themes came back for this range.</p>
            )}
          </>
        )}
      </div>
    </div>
  )
}
