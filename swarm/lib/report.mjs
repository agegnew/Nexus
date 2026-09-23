// The one-screen summary shown after a run. Facts come from the run itself; the model, when
// there is one, only rewords the lines so they read like a person wrote them.

import { locateRequest } from './codemap.mjs'

/** The request most likely to explain a failure: a server error first, then any client error. */
function mainCause(evidence) {
  const api = evidence.apiErrors.filter((error) => error.path.startsWith('/api') || error.status >= 500)
  const pool = api.length ? api : evidence.apiErrors
  return pool.find((error) => error.status >= 500) ?? pool[0] ?? null
}

/** A crash stack from Vite dev names the source file; match it to a file the graph knows. */
function crashSite(stack, graph) {
  if (!stack || !graph?.nodes) return null
  const files = [...new Set(graph.nodes.map((node) => node.filePath).filter(Boolean))]
  for (const match of stack.matchAll(/\/((?:[\w.-]+\/)*[\w.-]+\.(?:tsx?|jsx?))(?:\?[^:\s)]*)?:(\d+)/g)) {
    const file = files.find((candidate) => candidate.endsWith(match[1]) || match[1].endsWith(candidate))
    if (file) return { file, line: Number(match[2]) }
  }
  return null
}

function templateLine(result, cause) {
  const { verdict, steps } = result
  switch (verdict.reason) {
    case 'crash':
      return cause ? `Page crashed after ${cause.method} ${cause.path} failed` : `Page crashed: ${verdict.detail}`
    case 'nonsense':
      return `Screen shows "${verdict.detail}"`
    case 'not-reached':
      return cause ? `Nothing happened: ${cause.method} ${cause.path} returned ${cause.status || 'no response'}` : `Never reached the goal in ${steps} steps`
    case 'gave-up':
      return `Gave up after ${steps} steps`
    case 'blocked':
      return verdict.detail || 'Blocked'
    default:
      return verdict.reached ? `Got "${verdict.reached}" in ${steps} steps` : (verdict.detail || `Done in ${steps} steps`)
  }
}

export function buildReport({ results, target, graph, brain, startedAt, finishedAt }) {
  const agents = results.map((result) => {
    const cause = mainCause(result.evidence)
    const located = cause ? locateRequest(graph, cause.method, cause.path) : null
    const crash = result.verdict.reason === 'crash' ? crashSite(result.evidence.crashStack, graph) : null
    return {
      id: result.mission.id,
      persona: result.mission.persona,
      emoji: result.mission.emoji,
      goal: result.mission.goal,
      status: result.verdict.status,
      reason: result.verdict.reason,
      line: templateLine(result, cause),
      steps: result.steps,
      durationMs: result.durationMs,
      mode: result.mode,
      cause: cause
        ? {
            method: cause.method,
            path: cause.path,
            status: cause.status,
            missingBackend: located?.missingBackend ?? false,
            frontend: located?.frontend ?? crash,
            backend: located?.backend ?? null,
          }
        : crash ? { method: null, path: null, status: null, missingBackend: false, frontend: crash, backend: null } : null,
      crash: result.verdict.reason === 'crash' ? result.verdict.detail : null,
      review: result.review ?? null,
      screenshot: result.screenshot,
    }
  })
  const rated = agents.filter((agent) => agent.review?.rating)

  const passed = agents.filter((agent) => agent.status === 'passed').length
  const broken = agents.filter((agent) => agent.status === 'failed')
  return {
    target,
    brain: brain.model,
    startedAt,
    finishedAt,
    durationMs: finishedAt - startedAt,
    total: agents.length,
    passed,
    warnings: agents.filter((agent) => agent.status === 'warning').length,
    failed: broken.length,
    rating: rated.length ? Math.round((rated.reduce((sum, agent) => sum + agent.review.rating, 0) / rated.length) * 10) / 10 : null,
    overall: null,
    headline: broken.length === 0
      ? 'Every journey worked.'
      : `${broken.map((agent) => agent.persona).join(', ')} ${broken.length === 1 ? 'hit a problem' : 'hit problems'}.`,
    agents,
  }
}

const POLISH_SYSTEM = `You write the summary of an automated test run for a busy developer.
You get JSON facts, including each tester's own review. Reply as JSON
{"headline":"...","overall":"...","lines":{"<agent id>":"..."}}.
headline: at most 12 words, says how many journeys worked and the single most serious problem.
overall: 2 or 3 sentences, like a QA lead's sign-off: is the app ready for real users, what to fix first.
each line: at most 12 words, plain English, what the user tried and what went wrong or right.
Never invent facts, file names or numbers that are not in the input. No emoji.`

/** Rewords the template lines with the model. Any failure keeps the template text. */
export async function polishReport(report, brain) {
  if (!brain.canThink) return report
  const facts = report.agents.map((agent) => ({
    id: agent.id,
    persona: agent.persona,
    goal: agent.goal,
    status: agent.status,
    what_happened: agent.line,
    failing_request: agent.cause?.path ? `${agent.cause.method} ${agent.cause.path} -> ${agent.cause.status}` : null,
    backend_route_missing: agent.cause?.missingBackend ?? false,
    crash: agent.crash,
    tester_review: agent.review ? { rating: agent.review.rating, review: agent.review.review, problems: agent.review.problems.map((problem) => problem.text) } : null,
  }))
  try {
    const reply = await brain.json(POLISH_SYSTEM, JSON.stringify({ passed: report.passed, total: report.total, agents: facts }))
    const lines = reply?.lines ?? {}
    return {
      ...report,
      headline: typeof reply?.headline === 'string' && reply.headline.trim() ? reply.headline.trim().slice(0, 120) : report.headline,
      overall: typeof reply?.overall === 'string' && reply.overall.trim() ? reply.overall.trim().slice(0, 600) : report.overall,
      agents: report.agents.map((agent) => ({
        ...agent,
        line: typeof lines[agent.id] === 'string' && lines[agent.id].trim() ? lines[agent.id].trim().slice(0, 120) : agent.line,
      })),
    }
  } catch {
    return report
  }
}

const MARK = { passed: '✅', warning: '⚠️', failed: '❌' }

export function toMarkdown(report) {
  const seconds = Math.round(report.durationMs / 1000)
  const rows = report.agents.map((agent) => {
    const where = [
      agent.cause?.path ? `\`${agent.cause.method} ${agent.cause.path}\` → ${agent.cause.status || 'no response'}${agent.cause.missingBackend ? ' (no backend route)' : ''}` : null,
      agent.cause?.frontend ? `\`${agent.cause.frontend.file}:${agent.cause.frontend.line}\`` : null,
    ].filter(Boolean).join(' · ')
    return `| ${MARK[agent.status]} | ${agent.emoji} ${agent.persona} | ${agent.line} | ${where} |`
  })
  const reviews = report.agents.filter((agent) => agent.review).flatMap((agent) => {
    const review = agent.review
    return [
      `### ${agent.emoji} ${agent.persona} · ${stars(review.rating)}${review.title ? ` · ${review.title}` : ''}`,
      '',
      `> ${review.review}`,
      '',
      `*Goal:* ${agent.goal}`,
      ...(review.worked.length ? ['', '**Worked**', ...review.worked.map((item) => `- ${item}`)] : []),
      ...(review.problems.length ? ['', '**Problems**', ...review.problems.map((problem) => `- **${problem.severity}**: ${problem.text}`)] : []),
      ...(review.suggestions.length ? ['', '**Suggestions**', ...review.suggestions.map((item) => `- ${item}`)] : []),
      '',
    ]
  })
  return [
    `# Swarm test: ${report.passed} / ${report.total} passed · ${seconds}s${report.rating ? ` · ${report.rating} / 5 stars` : ''}`,
    '',
    `**${report.headline}**`,
    ...(report.overall ? ['', report.overall] : []),
    '',
    `Target: ${report.target} · Brain: ${report.brain}${report.account ? ` · Signed in as ${report.account}` : ''}${report.project?.docs?.length ? ` · Read ${report.project.docs.join(', ')}` : ''}`,
    ...(report.app ? ['', `*App under test:* ${report.app}`] : []),
    '',
    '| | Agent | Result | Where |',
    '|---|---|---|---|',
    ...rows,
    '',
    ...(reviews.length ? ['## What the testers said', '', ...reviews] : []),
  ].join('\n')
}

export function stars(rating) {
  const full = Math.max(0, Math.min(5, Math.round(rating ?? 0)))
  return `${'★'.repeat(full)}${'☆'.repeat(5 - full)}`
}

export function toText(report) {
  const seconds = Math.round(report.durationMs / 1000)
  const lines = [`Swarm result: ${report.passed} / ${report.total} passed · ${seconds}s`, report.headline, '']
  for (const agent of report.agents) {
    lines.push(`${MARK[agent.status]} ${agent.persona.padEnd(13)} ${agent.line}`)
    if (agent.cause?.frontend) lines.push(`   ${''.padEnd(13)} → ${agent.cause.frontend.file}:${agent.cause.frontend.line}`)
    if (agent.review) lines.push(`   ${''.padEnd(13)} ${stars(agent.review.rating)} "${agent.review.review}"`)
  }
  if (report.overall) lines.push('', report.overall)
  return lines.join('\n')
}
