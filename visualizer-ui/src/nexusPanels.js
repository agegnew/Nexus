/*
 * Where the film and the deck are served from, and the two panels that show them.
 *
 * They are iframes rather than React views because they are not React: they are the
 * plugin's own pages, served by the plugin's own HTTP server out of the plugin jar, with
 * their own bridge to the IDE. Rebuilding them here would mean two implementations of the
 * same player and two chances to disagree about what a storyboard is.
 *
 * Finding them is the only part that needs thought, because this page is served from one
 * of two places:
 *  - by the plugin itself on 5173, when no dev server is running. Everything is one
 *    origin and a relative path is already correct.
 *  - by Vite on 5173, when a developer is running `npm run dev`. vite.config.js proxies
 *    these paths to the plugin, so a relative path is still correct.
 * Either way the answer is a relative path, and /nexus.json is how we find out whether
 * there is a plugin behind it at all. Outside the IDE there is not, and the panel says so
 * rather than showing an empty frame.
 */

export const PANELS = [
  {
    id: 'reel',
    label: 'Reel',
    frame: 'nexus-reel',
    path: 'reel/index.html',
    blurb: 'A short product video, generated from this codebase.',
  },
  {
    id: 'deck',
    label: 'Deck',
    frame: 'nexus-deck',
    path: 'deck/index.html',
    blurb: 'A presentation, generated from this codebase.',
  },
]

export const PANEL_IDS = PANELS.map((panel) => panel.id)

/**
 * Asks the plugin where it is answering.
 *
 * Resolves to a base URL, or null when nothing answers, which is the ordinary case for
 * this page opened in a browser with no IDE behind it.
 */
export async function findPlugin(signal) {
  try {
    const response = await fetch('/nexus.json', { signal, cache: 'no-store' })
    if (!response.ok) return null
    const body = await response.json()
    return typeof body?.base === 'string' ? body.base : null
  } catch {
    return null
  }
}

/**
 * The src for a panel.
 *
 * Relative on purpose. An absolute URL taken from /nexus.json would be a second origin
 * the moment the plugin fell back off its preferred port, and a cross origin frame cannot
 * be reached by name from the IDE side, which is how every reply gets back into it.
 */
export function panelSrc(panel, params) {
  const query = new URLSearchParams()
  const name = params.get('projectName')
  const path = params.get('projectPath')
  if (name) query.set('projectName', name)
  if (path) query.set('projectPath', path)
  const tail = query.toString()
  return tail ? `/${panel.path}?${tail}` : `/${panel.path}`
}
