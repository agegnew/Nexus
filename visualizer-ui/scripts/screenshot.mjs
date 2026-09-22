import { mkdir } from 'node:fs/promises'
import { chromium } from 'playwright'

// Screenshots the visualizer against the demo fixtures so a change to the
// diagram can be looked at, not just reasoned about. Needs `npm run dev`.
//
//   node scripts/screenshot.mjs                      every view, default sizes
//   node scripts/screenshot.mjs taskflow architecture 900 620

const VIEWS = [
  { fixture: 'taskflow', view: 'architecture', width: 1440, height: 900 },
  { fixture: 'taskflow', view: 'architecture', width: 900, height: 620 },
  { fixture: 'taskflow', view: 'code', width: 1440, height: 900 },
  { fixture: 'minimal', view: 'architecture', width: 1440, height: 900 },
  { fixture: 'empty', view: 'architecture', width: 1440, height: 900 },
]

const OUT = 'screenshots'

// Outside the IDE there is no plugin on 5174, so vite's proxy for the Reel and
// Deck paths fails. nexusPanels.js expects that and shows a message; it is not a
// regression, so it must not fail a run.
const PLUGIN_PATH = /\/(nexus\.json|reel|deck|shared)(\/|$|\?)/i

async function capture(browser, { fixture, view, width, height }) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 2 })
  const errors = []
  page.on('console', (message) => {
    if (message.type() !== 'error') return
    // The path is on the location, not in the text ("Failed to load resource...").
    if (PLUGIN_PATH.test(message.location()?.url ?? '')) return
    errors.push(message.text())
  })
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))

  await page.goto(`http://localhost:5173/?demo=${fixture}`, { waitUntil: 'networkidle' })

  if (view === 'architecture') {
    await page.getByRole('button', { name: 'Architecture' }).click()
    await page.waitForSelector('.architecture-workspace')
  }
  await page.waitForTimeout(1000) // let the fitView transition settle

  const file = `${OUT}/${fixture}-${view}-${width}x${height}.png`
  await page.screenshot({ path: file })
  await page.close()

  return { file, errors }
}

const [fixture, view, width, height] = process.argv.slice(2)
const targets = fixture
  ? [{ fixture, view: view ?? 'architecture', width: Number(width ?? 1440), height: Number(height ?? 900) }]
  : VIEWS

await mkdir(OUT, { recursive: true })
const browser = await chromium.launch()
let failed = false

for (const target of targets) {
  const { file, errors } = await capture(browser, target)
  console.log(`${errors.length ? 'FAIL' : ' ok '} ${file}`)
  errors.forEach((error) => { failed = true; console.log(`       ${error}`) })
}

await browser.close()
process.exit(failed ? 1 : 0)
