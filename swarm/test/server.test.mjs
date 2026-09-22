import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createSwarmServer } from '../lib/server.mjs'
import { createBrain } from '../lib/brain.mjs'

async function withServer(run) {
  const outDir = await mkdtemp(join(tmpdir(), 'nexus-swarm-server-'))
  const server = createSwarmServer({ brain: createBrain(), outDir })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  try {
    await run(base)
  } finally {
    await fetch(`${base}/stop`, { method: 'POST' }).catch(() => {})
    server.closeAllConnections?.()
    await new Promise((resolve) => server.close(resolve))
  }
}

const post = (base, path, body) => fetch(`${base}${path}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}),
})

test('a double click starts one run, the second is refused', async () => {
  await withServer(async (base) => {
    // Nothing listens on port 9, so the run fails fast at its first page load and needs no app.
    const body = { target: 'http://127.0.0.1:9' }
    const [first, second] = await Promise.all([post(base, '/run', body), post(base, '/run', body)])
    assert.deepEqual([first.status, second.status].sort(), [202, 409])
  })
})

test('a bad target is refused and does not leave the runner locked', async () => {
  await withServer(async (base) => {
    assert.equal((await post(base, '/run', { target: 'not a url' })).status, 400)
    assert.equal((await post(base, '/replay')).status, 404, 'no recording yet, and not locked either')
  })
})
