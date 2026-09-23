// The runner finds the OpenAI key in the same places the plugin does, in the same order, and
// copes with the ways people write a key into a file.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { cleanKey, keyFromDotEnv, findKey } from '../lib/key.mjs'

test('key files may carry a prefix, quotes or a newline', () => {
  assert.equal(cleanKey('sk-abc\n'), 'sk-abc')
  assert.equal(cleanKey('OPENAI_API_KEY="sk-abc"'), 'sk-abc')
  assert.equal(cleanKey("export OPENAI_KEY='sk-abc' "), 'sk-abc')
  assert.equal(keyFromDotEnv('# comment\nOTHER=1\nOPENAI_KEY=sk-two # old one\n'), 'sk-two')
  assert.equal(keyFromDotEnv('OPENAI_KEY=sk-two\nOPENAI_API_KEY=sk-one'), 'sk-one', 'OPENAI_API_KEY wins')
  assert.equal(keyFromDotEnv('NOTHING=here'), '')
})

test('the environment wins, then the plugin key file, then the project .env', async () => {
  const home = await mkdtemp(join(tmpdir(), 'nexus-key-home-'))
  const project = await mkdtemp(join(tmpdir(), 'nexus-key-project-'))
  await writeFile(join(project, '.env'), 'OPENAI_KEY=sk-project\n')

  assert.deepEqual(findKey({ env: {}, home, projectPath: project }), { key: 'sk-project', source: "the tested project's .env" })

  await mkdir(join(home, '.code-visualizer'))
  await writeFile(join(home, '.code-visualizer', 'openai-key'), 'sk-file\n')
  assert.deepEqual(findKey({ env: {}, home, projectPath: project }), { key: 'sk-file', source: '~/.code-visualizer/openai-key' })

  assert.equal(findKey({ env: { OPENAI_KEY: 'sk-env2' }, home }).source, 'OPENAI_KEY')
  assert.deepEqual(findKey({ env: { OPENAI_API_KEY: ' sk-env ', OPENAI_KEY: 'sk-env2' }, home }), { key: 'sk-env', source: 'OPENAI_API_KEY' })
  assert.deepEqual(findKey({ env: {}, home: join(home, 'nowhere') }), { key: '', source: null })
})
