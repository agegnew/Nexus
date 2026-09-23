/*
 * Where the film, the deck and the trust picture are served from, and the panels that show them.
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

/**
 * Every view in the row, in the order they appear.
 *
 * One list, written out, and the panels are the entries that have a page. It was two lists
 * with a spread between them for about ten minutes, which read fine and was wrong: the Reel
 * and the Deck recreate a project's own navigation for a stakeholder by reading its source,
 * and a list assembled at runtime from a spread is a list they cannot read. Half a row is a
 * worse answer than no row, so the row says what it is.
 */
export const VIEWS = [
  {
    id: 'code',
    label: 'Code tree',
  },
  {
    id: 'architecture',
    label: 'Architecture',
  },
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
  {
    id: 'trust',
    label: 'Trust',
    frame: 'nexus-trust',
    path: 'trust/index.html',
    blurb: 'Which of this codebase has never been executed.',
  },
  {
    id: 'swarm',
    label: 'Swarm',
  },
]

/**
 * The views that are the plugin's own pages, and therefore arrive in an iframe.
 *
 * Code tree, Architecture and Swarm are React and are drawn by this app; the rest are served
 * by the plugin. Having the one list say which is which is the whole reason it is one list.
 */
export const PANELS = VIEWS.filter((view) => view.path)

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
