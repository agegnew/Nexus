// `npm run dev` also starts the swarm runner, so the Swarm tab works from one command. It runs
// swarm/run.mjs on 7070, the port the tab looks for outside the IDE, and stops with the dev server.
//
// The runner finds the OpenAI key itself (see swarm/lib/key.mjs): OPENAI_API_KEY, then the plugin's
// own ~/.code-visualizer/openai-key file, then the tested project's .env. These can also be set in
// the environment or in visualizer-ui/.env.local (git-ignored):
//   OPENAI_API_KEY=sk-...                     overrides the key file
//   NEXUS_SWARM_MODEL=gpt-4o-mini             optional
//   NEXUS_SWARM_PROJECT=C:\path\to\your\app   the project whose README and CLAUDE.md brief the testers

import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { connect } from 'node:net'
import { fileURLToPath } from 'node:url'
import { loadEnv } from 'vite'

const PORT = 7070
const SWARM_DIR = fileURLToPath(new URL('../../swarm/', import.meta.url))
const PASSED_ON = ['OPENAI_API_KEY', 'OPENAI_KEY', 'NEXUS_SWARM_MODEL', 'NEXUS_SWARM_PROJECT', 'NEXUS_SWARM_BASE_URL']

function portInUse(port) {
  return new Promise((resolve) => {
    const socket = connect(port, '127.0.0.1')
    socket.once('connect', () => { socket.destroy(); resolve(true) })
    socket.once('error', () => resolve(false))
  })
}

function prefixed(stream, write) {
  let buffer = ''
  stream.setEncoding('utf8')
  stream.on('data', (chunk) => {
    buffer += chunk
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop()
    for (const line of lines) if (line.trim()) write(`[swarm] ${line}`)
  })
}

export function swarmRunner() {
  let child = null
  const stop = () => {
    if (!child) return
    child.stdin.end()
    child.kill()
    child = null
  }

  return {
    name: 'nexus-swarm-runner',
    apply: 'serve',
    async configureServer(server) {
      const log = server.config.logger
      if (!existsSync(`${SWARM_DIR}run.mjs`)) return log.warn('[swarm] swarm/ folder not found; the Swarm tab will be offline.')
      if (!existsSync(`${SWARM_DIR}node_modules/playwright`)) {
        return log.warn('[swarm] Run `npm install` in the swarm/ folder once, then restart `npm run dev`.')
      }
      if (await portInUse(PORT)) return log.info(`[swarm] A runner is already listening on ${PORT}; using it.`)

      const fileEnv = loadEnv(server.config.mode, server.config.envDir ?? process.cwd(), '')
      const env = { ...process.env }
      for (const key of PASSED_ON) if (fileEnv[key]) env[key] = fileEnv[key]

      // --exit-with-stdin: if this dev server dies without cleaning up, the runner still exits.
      child = spawn(process.execPath, ['run.mjs', 'serve', '--port', String(PORT), '--exit-with-stdin'], {
        cwd: SWARM_DIR,
        env,
        stdio: ['pipe', 'pipe', 'pipe'],
      })
      prefixed(child.stdout, (line) => log.info(line))
      prefixed(child.stderr, (line) => log.warn(line))
      child.on('exit', (code) => {
        if (child && code) log.warn(`[swarm] The runner stopped (exit ${code}).`)
        child = null
      })

      server.httpServer?.once('close', stop)
      process.once('exit', stop)
    },
  }
}
