// Communication with the IntelliJ plugin. Inside the IDE, the plugin injects
// window.__CODE_VISUALIZER_HOST__ and pushes graphs through DOM events. In a plain
// browser (npm run dev) there is no host and the UI falls back to demo data.

export const params = new URLSearchParams(window.location.search)

export function hasHost() {
  return Boolean(window.__CODE_VISUALIZER_HOST__)
}

export function isEmbedded() {
  return params.get('host') === 'intellij' || hasHost()
}

function post(message) {
  const host = window.__CODE_VISUALIZER_HOST__
  if (!host) return false
  host.post(JSON.stringify(message))
  return true
}

export function openSource(filePath, line) {
  if (!filePath) return false
  return post({ type: 'open', filePath, line: line ?? 1 })
}

export function requestRefresh() {
  return post({ type: 'refresh' })
}

// Asks the IDE to summarise a range of git history. The answer arrives on 'code-visualizer:activity'.
export function requestActivity({ since, until, scope, mine, uncommitted }) {
  return post({
    type: 'activity',
    since,
    until,
    scope: scope ?? 'all',
    mine: mine !== false,
    uncommitted: uncommitted !== false
  })
}

export function subscribe({ onGraph, onStatus, onHost, onActivity, onActivityMeta, onTrust }) {
  const graph = (event) => onGraph(event.detail)
  const status = (event) => onStatus(event.detail?.state)
  const host = () => onHost()
  const activity = (event) => onActivity?.(event.detail)
  const meta = (event) => onActivityMeta?.(event.detail)
  // Which code has actually executed. Optional: the plugin only sends it when a
  // coverage report exists, and the map renders unchanged when it never arrives.
  const trust = (event) => onTrust?.(event.detail)
  window.addEventListener('code-visualizer:graph', graph)
  window.addEventListener('code-visualizer:status', status)
  window.addEventListener('code-visualizer:host-ready', host)
  window.addEventListener('code-visualizer:activity', activity)
  window.addEventListener('code-visualizer:activity-meta', meta)
  window.addEventListener('code-visualizer:trust', trust)
  return () => {
    window.removeEventListener('code-visualizer:graph', graph)
    window.removeEventListener('code-visualizer:status', status)
    window.removeEventListener('code-visualizer:host-ready', host)
    window.removeEventListener('code-visualizer:activity', activity)
    window.removeEventListener('code-visualizer:activity-meta', meta)
    window.removeEventListener('code-visualizer:trust', trust)
  }
}

export function readPreference(key, fallback) {
  try {
    const value = window.localStorage.getItem(`code-visualizer:${key}`)
    return value === null ? fallback : JSON.parse(value)
  } catch {
    return fallback
  }
}

export function writePreference(key, value) {
  try {
    window.localStorage.setItem(`code-visualizer:${key}`, JSON.stringify(value))
  } catch {
    // Storage can be unavailable in embedded browsers; preferences are only a convenience.
  }
}
