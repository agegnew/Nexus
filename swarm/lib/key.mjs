// Where the runner gets its OpenAI key when nobody hands it one. Inside the IDE, SwarmService passes
// the key from Settings | Tools | Nexus as OPENAI_API_KEY, so the first rule wins. Started from a
// terminal (`npm run dev`), it falls back to the same places the plugin itself reads: the
// ~/.code-visualizer/openai-key file, then the tested project's .env. The key is never printed;
// only where it came from is.

import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

/** A key file or env line may hold `OPENAI_API_KEY=sk-...`, quotes, or a trailing newline. */
export function cleanKey(raw) {
  return String(raw ?? '')
    .trim()
    .replace(/^(?:export\s+)?(?:OPENAI_API_KEY|OPENAI_KEY)\s*=\s*/, '')
    .trim()
    .replace(/^["']|["']$/g, '')
    .trim()
}

/** OPENAI_API_KEY, or else OPENAI_KEY, from the text of a .env file. */
export function keyFromDotEnv(text) {
  const lines = String(text ?? '').split(/\r?\n/)
  for (const name of ['OPENAI_API_KEY', 'OPENAI_KEY']) {
    const line = lines.find((candidate) => new RegExp(`^\\s*(?:export\\s+)?${name}\\s*=`).test(candidate))
    const key = line ? cleanKey(line.replace(/\s+#.*$/, '')) : ''
    if (key) return key
  }
  return ''
}

function read(file) {
  try {
    return readFileSync(file, 'utf8')
  } catch {
    return ''
  }
}

/**
 * The first key found, with a label for where it came from. [env] and [home] are parameters so
 * the order can be tested without touching the real machine.
 */
export function findKey({ env = process.env, home = homedir(), projectPath = env.NEXUS_SWARM_PROJECT } = {}) {
  const candidates = [
    () => [cleanKey(env.OPENAI_API_KEY), 'OPENAI_API_KEY'],
    () => [cleanKey(env.OPENAI_KEY), 'OPENAI_KEY'],
    () => [cleanKey(read(join(home, '.code-visualizer', 'openai-key'))), '~/.code-visualizer/openai-key'],
    ...(projectPath
      ? ['.env.local', '.env'].map((name) => () => [keyFromDotEnv(read(join(projectPath, name))), `the tested project's ${name}`])
      : []),
  ]
  for (const candidate of candidates) {
    const [key, source] = candidate()
    if (key) return { key, source }
  }
  return { key: '', source: null }
}
