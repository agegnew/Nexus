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
  { fixture: 'taskflow', view: 'client', width: 1440, height: 900 },
  { fixture: 'taskflow', view: 'client', width: 1440, height: 900, present: true },
  { fixture: 'taskflow', view: 'client', width: 760, height: 900 },
  { fixture: 'minimal', view: 'client', width: 1440, height: 900, present: true },
]

const TAB_LABEL = { architecture: 'Architecture', client: 'Client view' }

const OUT = 'screenshots'

async function capture(browser, { fixture, view, width, height, present = false }) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 2 })
  const errors = []
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))

  const query = `demo=${fixture}&view=${view}${present ? '&present=1' : ''}`
  await page.goto(`http://localhost:5173/?${query}`, { waitUntil: 'networkidle' })

  if (TAB_LABEL[view] && !present) {
    await page.getByRole('button', { name: TAB_LABEL[view], exact: true }).click()
  }
  if (view === 'architecture') await page.waitForSelector('.architecture-workspace')
  if (view === 'client') await page.waitForSelector('.capability-grid')

  await page.waitForTimeout(1000) // let fitView and the card reveal settle

  const file = `${OUT}/${fixture}-${view}${present ? '-present' : ''}-${width}x${height}.png`
  await page.screenshot({ path: file })
  await page.close()

  return { file, errors }
}

const [fixture, view, width, height] = process.argv.slice(2)
const targets = fixture
  ? [{
      fixture,
      view: (view ?? 'architecture').replace(/-present$/, ''),
      present: (view ?? '').endsWith('-present'),
      width: Number(width ?? 1440),
      height: Number(height ?? 900),
    }]
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
