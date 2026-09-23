// What the testers know before they open the app: the project's own words about itself
// (CLAUDE.md, README.md, docs), its package manifests, the pages its router declares, and the
// URL it most likely runs on. The planner reads this the way a new tester reads the brief.

import { readFile, readdir, stat } from 'node:fs/promises'
import { basename, join, relative } from 'node:path'

const DOC_NAMES = ['CLAUDE.md', 'AGENTS.md', 'README.md', 'readme.md', 'Readme.md']
const SKIP_DIRS = new Set(['node_modules', '.git', '.idea', '.gradle', 'build', 'dist', 'out', 'target', '.next', '.nuxt', 'coverage', '.venv', 'venv', '__pycache__', '.cache'])
const SOURCE_EXT = /\.(jsx?|tsx?|vue|svelte)$/
const MAX_DOC = 6000
const MAX_TOTAL = 16000
const MAX_SOURCE_FILES = 600

async function readText(file, limit = MAX_DOC) {
  try {
    const info = await stat(file)
    if (!info.isFile() || info.size > 2_000_000) return null
    return (await readFile(file, 'utf8')).slice(0, limit)
  } catch {
    return null
  }
}

async function listDir(dir) {
  try {
    return await readdir(dir, { withFileTypes: true })
  } catch {
    return []
  }
}

/** The project root and its direct subfolders: monorepos keep the web app's README one level down. */
async function candidateDirs(root) {
  const children = (await listDir(root))
    .filter((entry) => entry.isDirectory() && !SKIP_DIRS.has(entry.name) && !entry.name.startsWith('.'))
    .map((entry) => join(root, entry.name))
  return [root, ...children]
}

async function readDocs(root) {
  const docs = []
  for (const dir of await candidateDirs(root)) {
    for (const name of DOC_NAMES) {
      const text = await readText(join(dir, name))
      if (text?.trim() && !docs.some((doc) => doc.text === text)) docs.push({ file: relative(root, join(dir, name)).replace(/\\/g, '/'), text })
    }
  }
  const docsDir = join(root, 'docs')
  for (const entry of (await listDir(docsDir)).filter((item) => item.isFile() && /\.md$/i.test(item.name)).slice(0, 6)) {
    const text = await readText(join(docsDir, entry.name), 3000)
    if (text?.trim()) docs.push({ file: `docs/${entry.name}`, text })
  }
  // The project's own instructions first, the READMEs next, loose docs last.
  const rank = (file) => (/CLAUDE|AGENTS/i.test(basename(file)) ? 0 : /readme/i.test(basename(file)) ? (file.includes('/') ? 2 : 1) : 3)
  return docs.sort((a, b) => rank(a.file) - rank(b.file))
}

async function readManifests(root) {
  const manifests = []
  for (const dir of await candidateDirs(root)) {
    const text = await readText(join(dir, 'package.json'), 200_000)
    if (!text) continue
    try {
      const json = JSON.parse(text)
      manifests.push({
        file: relative(root, join(dir, 'package.json')).replace(/\\/g, '/'),
        name: json.name ?? '',
        description: json.description ?? '',
        scripts: json.scripts ?? {},
        dependencies: Object.keys({ ...json.dependencies, ...json.devDependencies }),
      })
    } catch {
      // A broken package.json says nothing useful about the app.
    }
  }
  return manifests
}

async function walkSources(dir, root, found) {
  if (found.length >= MAX_SOURCE_FILES) return
  for (const entry of await listDir(dir)) {
    if (found.length >= MAX_SOURCE_FILES) return
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name) && !entry.name.startsWith('.')) await walkSources(path, root, found)
    } else if (SOURCE_EXT.test(entry.name)) {
      found.push(relative(root, path).replace(/\\/g, '/'))
    }
  }
}

/** Page routes from file-based routers (Next, Nuxt, SvelteKit) and from router config in code. */
export function routesFrom(files, readSource = () => '') {
  const routes = new Set()
  for (const file of files) {
    const page = file.match(/(?:^|\/)(?:src\/)?app\/(.*?)\/?page\.(?:jsx?|tsx?)$/)
      ?? file.match(/(?:^|\/)(?:src\/)?pages\/(.*?)\.(?:jsx?|tsx?|vue)$/)
      ?? file.match(/(?:^|\/)src\/routes\/(.*?)\/?\+page\.svelte$/)
    if (page && !/(^|\/)(_app|_document|api)(\/|$)/.test(page[1])) {
      const route = `/${page[1].replace(/(^|\/)index$/, '').replace(/\([^)]*\)\/?/g, '')}`.replace(/\/+$/, '') || '/'
      routes.add(route)
    }
    const text = readSource(file)
    for (const match of text.matchAll(/\bpath\s*[:=]\s*\{?\s*["'`](\/[\w\-/:.*[\]]*)["'`]/g)) routes.add(match[1])
  }
  return [...routes].filter((route) => !route.startsWith('/api')).sort().slice(0, 60)
}

/** A port the docs, the scripts or a dev-server config names, as a URL to test. */
export function suggestTarget(texts) {
  const joined = texts.join('\n')
  const explicit = [...joined.matchAll(/https?:\/\/(?:localhost|127\.0\.0\.1):(\d{2,5})(\/[\w\-/]*)?/g)]
    .map((match) => Number(match[1]))
  const configured = [...joined.matchAll(/(?:--port[ =]|\bport\s*:\s*|PORT=)(\d{2,5})/g)].map((match) => Number(match[1]))
  const ports = [...explicit, ...configured].filter((port) => port > 80 && port < 65536)
  // API servers usually sit on 8000/8080/5000; a web front end on the Vite, Next or CRA defaults.
  const webFirst = [...new Set(ports)].sort((a, b) => webScore(b) - webScore(a))
  if (webFirst.length) return `http://localhost:${webFirst[0]}`
  if (/\bvite\b/.test(joined)) return 'http://localhost:5173'
  if (/\bnext\b|react-scripts|\bnuxt\b/.test(joined)) return 'http://localhost:3000'
  return null
}

function webScore(port) {
  if ([5173, 5174, 3000, 4200, 8081, 4321, 5500].includes(port)) return 2
  if ([8000, 8080, 5000, 8888].includes(port)) return 0
  return 1
}

/**
 * Everything about the project the planner should know, as one bounded text block. [projectPath]
 * may be missing (a plain browser run); the result is then empty but well formed.
 */
export async function readProjectContext(projectPath) {
  if (!projectPath) return emptyContext()
  const info = await stat(projectPath).catch(() => null)
  if (!info?.isDirectory()) return emptyContext()

  const docs = await readDocs(projectPath)
  const manifests = await readManifests(projectPath)
  const sources = []
  await walkSources(projectPath, projectPath, sources)
  const sourceCache = new Map()
  for (const file of sources.filter((path) => /rout|app\.|main\.|index\./i.test(path)).slice(0, 80)) {
    sourceCache.set(file, await readText(join(projectPath, file), 60_000) ?? '')
  }
  const routes = routesFrom(sources, (file) => sourceCache.get(file) ?? '')

  const configTexts = []
  for (const dir of await candidateDirs(projectPath)) {
    for (const name of ['vite.config.js', 'vite.config.ts', 'vite.config.mjs', '.env', '.env.development', 'angular.json']) {
      const text = await readText(join(dir, name), 20_000)
      if (text) configTexts.push(text)
    }
  }
  const suggestedTarget = suggestTarget([
    ...docs.map((doc) => doc.text),
    ...manifests.map((manifest) => `${Object.values(manifest.scripts).join('\n')}\n${manifest.dependencies.join(' ')}`),
    ...configTexts,
  ])

  const name = manifests.find((manifest) => manifest.file === 'package.json')?.name || basename(projectPath)
  return { name, path: projectPath, docs, manifests, routes, suggestedTarget, text: contextText({ name, docs, manifests, routes }) }
}

function emptyContext() {
  return { name: '', path: null, docs: [], manifests: [], routes: [], suggestedTarget: null, text: '' }
}

function contextText({ name, docs, manifests, routes }) {
  const parts = [`Project: ${name}`]
  for (const manifest of manifests) {
    const deps = manifest.dependencies.filter((dep) => !dep.startsWith('@types/')).slice(0, 25).join(', ')
    parts.push(`${manifest.file}: ${[manifest.description, deps && `uses ${deps}`].filter(Boolean).join(' · ')}`)
  }
  if (routes.length) parts.push(`Pages declared in the code: ${routes.join(', ')}`)
  let budget = MAX_TOTAL - parts.join('\n').length
  for (const doc of docs) {
    if (budget <= 400) break
    const body = doc.text.slice(0, Math.min(doc.text.length, budget - 60))
    parts.push(`--- ${doc.file} ---\n${body}`)
    budget -= body.length + 60
  }
  return parts.join('\n')
}

/** What the Swarm tab shows about the brief, without the document bodies. */
export function contextSummary(context) {
  return {
    name: context.name,
    path: context.path,
    docs: context.docs.map((doc) => doc.file),
    routes: context.routes.length,
    suggestedTarget: context.suggestedTarget,
  }
}
