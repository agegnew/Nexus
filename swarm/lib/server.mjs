// The swarm's local control server. The Nexus tab talks to it directly over loopback:
//   GET  /events   server-sent events: every live update, plus a snapshot on connect
//   POST /run      { target, graph?, headed?, plan?, credentials?: {username, password}, focus?, projectPath? }
//                  starts a run. The password is used by the browsers only: it is never broadcast,
//                  recorded, or sent to the model.
//   POST /stop     stops the current run
//   POST /replay   plays the last recorded run again, frames and all (the stage safety net)
//   GET  /report   the last report as JSON, /report.md as Markdown
//   GET  /health   liveness, whether a model is available, and what the runner read about the project

import { createServer } from 'node:http'
import { createWriteStream, existsSync } from 'node:fs'
import { mkdir, readFile, rename } from 'node:fs/promises'
import { join } from 'node:path'
import { runSwarm } from './swarm.mjs'
import { toMarkdown } from './report.mjs'
import { readProjectContext, contextSummary } from './context.mjs'

const RECORD_FPS = 3
const MAX_BODY = 8 * 1024 * 1024

export function createSwarmServer({ brain, outDir, pace, maxSteps, projectPath = null }) {
  const clients = new Set()
  // What the Swarm tab shows before a run: which docs were found and the URL the app likely runs on.
  const project = readProjectContext(projectPath).then(contextSummary, () => null)
  let running = null
  let replaying = null
  let state = freshState()
  let lastReport = null
  let recorder = null
  // Claimed synchronously by /run and /replay before their first await, so a double click cannot
  // slip a second run in while the first is still reading its request body.
  let busy = false

  function freshState() {
    return { phase: 'idle', message: '', target: null, app: '', missions: [], agents: {}, report: null, replay: false }
  }

  /** Keeps the snapshot a late-joining tab receives, so reopening the tool window resumes the view. */
  function remember(event) {
    switch (event.type) {
      case 'run':
        state.phase = event.phase
        if (event.message) state.message = event.message
        if (event.target) state.target = event.target
        break
      case 'missions':
        state.missions = event.missions
        state.app = event.app ?? ''
        state.agents = Object.fromEntries(event.missions.map((mission) => [mission.id, { status: 'queued', thought: '', action: '', step: 0, frame: null, calls: 0, review: null }]))
        break
      case 'agent': {
        const agent = state.agents[event.id]
        if (agent) Object.assign(agent, { status: event.status, thought: event.thought, action: event.action, step: event.step }, event.review ? { review: event.review } : {})
        break
      }
      case 'frame':
        if (state.agents[event.id]) state.agents[event.id].frame = event.data
        break
      case 'network':
        if (state.agents[event.id]) state.agents[event.id].calls += 1
        break
      case 'report':
        state.report = event.report
        break
    }
  }

  function broadcast(event) {
    remember(event)
    const line = `data: ${JSON.stringify(event)}\n\n`
    for (const client of clients) client.write(line)
  }

  /** Live events are broadcast and, frames thinned to RECORD_FPS, written to the replay log. */
  function liveEmit(startedAt) {
    const lastFrame = new Map()
    return (event) => {
      broadcast(event)
      if (!recorder) return
      if (event.type === 'frame') {
        const now = Date.now()
        if (now - (lastFrame.get(event.id) ?? 0) < 1000 / RECORD_FPS) return
        lastFrame.set(event.id, now)
      }
      recorder.write(`${JSON.stringify({ t: Date.now() - startedAt, event })}\n`)
    }
  }

  async function startRun({ target, graph, headed, plan, credentials, focus, projectPath: runProject }) {
    const controller = new AbortController()
    const startedAt = Date.now()
    state = freshState()
    await mkdir(outDir, { recursive: true })
    // Recorded to a side file and only promoted once a report exists, so a run that dies
    // halfway never overwrites the last good recording the replay button depends on.
    recorder = createWriteStream(join(outDir, 'current-run.jsonl'))
    let finished = false
    const emit = liveEmit(startedAt)
    running = {
      controller,
      done: runSwarm({
        target, graph, headed, plan, brain, emit, outDir, pace, maxSteps, signal: controller.signal,
        projectPath: runProject || projectPath, credentials, focus,
      })
        .then((report) => { lastReport = report; finished = true })
        .catch((error) => broadcast({ type: 'run', phase: 'error', message: error.message }))
        .finally(async () => {
          const stream = recorder
          recorder = null
          if (stream) await new Promise((resolve) => stream.end(resolve))
          if (finished) await rename(join(outDir, 'current-run.jsonl'), join(outDir, 'last-run.jsonl')).catch(() => {})
          running = null
          busy = false
        }),
    }
  }

  async function replay() {
    const log = await readFile(join(outDir, 'last-run.jsonl'), 'utf8').catch(() => null)
    if (!log) {
      busy = false
      return false
    }
    const entries = log.split('\n').filter(Boolean).map((line) => JSON.parse(line))
    let cancelled = false
    state = freshState()
    replaying = { cancel: () => { cancelled = true } }
    broadcast({ type: 'run', phase: 'starting', message: 'Replaying the last run', replay: true })
    state.replay = true
    const began = Date.now()
    for (const { t, event } of entries) {
      if (cancelled) break
      const wait = t - (Date.now() - began)
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
      broadcast(event.type === 'run' ? { ...event, replay: true } : event)
      if (event.type === 'report') lastReport = event.report
    }
    replaying = null
    busy = false
    return true
  }

  function send(response, status, body, type = 'application/json') {
    response.writeHead(status, { 'Content-Type': type, 'Access-Control-Allow-Origin': '*' })
    response.end(typeof body === 'string' ? body : JSON.stringify(body))
  }

  async function readJson(request) {
    const chunks = []
    let size = 0
    for await (const chunk of request) {
      size += chunk.length
      if (size > MAX_BODY) throw new Error('Request too large')
      chunks.push(chunk)
    }
    const text = Buffer.concat(chunks).toString('utf8')
    return text ? JSON.parse(text) : {}
  }

  return createServer(async (request, response) => {
    const { pathname } = new URL(request.url, 'http://local')

    if (request.method === 'OPTIONS') {
      response.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
      })
      return response.end()
    }

    try {
      if (request.method === 'GET' && pathname === '/health') {
        return send(response, 200, { ok: true, brain: brain.model, canThink: brain.canThink, running: Boolean(running), replaying: Boolean(replaying), project: await project })
      }

      if (request.method === 'GET' && pathname === '/events') {
        response.writeHead(200, {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
          'Access-Control-Allow-Origin': '*',
        })
        response.write(`data: ${JSON.stringify({ type: 'snapshot', brain: brain.model, canThink: brain.canThink, hasReplay: existsSync(join(outDir, 'last-run.jsonl')), project: await project, state })}\n\n`)
        clients.add(response)
        const ping = setInterval(() => response.write(': ping\n\n'), 15_000)
        request.on('close', () => { clearInterval(ping); clients.delete(response) })
        return undefined
      }

      if (request.method === 'POST' && pathname === '/run') {
        if (busy) return send(response, 409, { error: 'A run is already in progress' })
        busy = true
        try {
          const body = await readJson(request)
          const target = String(body.target || '').trim()
          if (!/^https?:\/\//.test(target)) {
            busy = false
            return send(response, 400, { error: 'target must be an http(s) URL' })
          }
          const credentials = body.credentials && typeof body.credentials === 'object'
            ? { username: String(body.credentials.username ?? '').slice(0, 200), password: String(body.credentials.password ?? '').slice(0, 200) }
            : null
          await startRun({
            target,
            graph: body.graph ?? null,
            headed: Boolean(body.headed),
            plan: body.plan ?? 'auto',
            credentials,
            focus: String(body.focus ?? '').slice(0, 500),
            projectPath: typeof body.projectPath === 'string' ? body.projectPath : null,
          })
        } catch (error) {
          if (!running) busy = false
          throw error
        }
        return send(response, 202, { ok: true })
      }

      if (request.method === 'POST' && pathname === '/stop') {
        running?.controller.abort()
        replaying?.cancel()
        return send(response, 200, { ok: true })
      }

      if (request.method === 'POST' && pathname === '/replay') {
        if (busy) return send(response, 409, { error: 'A run is already in progress' })
        if (!existsSync(join(outDir, 'last-run.jsonl'))) return send(response, 404, { error: 'No recorded run yet' })
        busy = true
        replay().catch((error) => {
          replaying = null
          busy = false
          broadcast({ type: 'run', phase: 'error', message: `Replay failed: ${error.message}` })
        })
        return send(response, 202, { ok: true })
      }

      if (request.method === 'GET' && pathname === '/report') {
        return lastReport ? send(response, 200, lastReport) : send(response, 404, { error: 'No report yet' })
      }

      if (request.method === 'GET' && pathname === '/report.md') {
        return lastReport ? send(response, 200, toMarkdown(lastReport), 'text/markdown; charset=utf-8') : send(response, 404, 'No report yet', 'text/plain')
      }

      return send(response, 404, { error: 'Not found' })
    } catch (error) {
      return send(response, 500, { error: error.message })
    }
  })
}
