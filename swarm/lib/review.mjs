// After a journey, the agent writes the review a real user would leave: a star rating, what
// worked, what went wrong, and what they would change. The facts (verdict, failed requests,
// crashes) come from the run; the model only puts them in the persona's words, and the rating
// is kept honest against the verdict.

const REVIEW_SYSTEM = `You just finished testing a web app as a real user. Write your honest review of
the experience, in the first person, as the persona you played. You get your goal, every step you took
with your thoughts, remarks you noted along the way, the automated verdict, failed network requests,
and page crashes. Base every statement on those facts; never invent screens or features.
Reply as JSON:
{"rating":1-5,
 "title":"a short review title, under 8 words",
 "review":"2 to 4 sentences, first person, like an app-store review from this persona",
 "worked":["what went well, short phrases"],
 "problems":[{"severity":"blocker|major|minor","text":"what went wrong, concrete, one sentence"}],
 "suggestions":["one concrete improvement a developer could make"]}
A failed goal is at most 2 stars. Keep lists to at most 4 items each; empty lists are fine.`

const SEVERITIES = new Set(['blocker', 'major', 'minor'])

function strings(list, limit = 4) {
  return (Array.isArray(list) ? list : []).filter((item) => typeof item === 'string' && item.trim()).slice(0, limit).map((item) => item.trim().slice(0, 200))
}

/** Cleans a model reply into a review, or null when it said nothing usable. */
export function normalizeReview(reply, verdictStatus) {
  if (!reply || typeof reply.review !== 'string' || !reply.review.trim()) return null
  let rating = Math.round(Number(reply.rating))
  if (!Number.isFinite(rating)) rating = verdictStatus === 'passed' ? 4 : verdictStatus === 'warning' ? 3 : 2
  rating = Math.min(5, Math.max(1, rating))
  if (verdictStatus === 'failed') rating = Math.min(rating, 2)
  if (verdictStatus === 'warning') rating = Math.min(rating, 4)
  const problems = (Array.isArray(reply.problems) ? reply.problems : [])
    .map((problem) => (typeof problem === 'string' ? { severity: 'minor', text: problem } : problem))
    .filter((problem) => typeof problem?.text === 'string' && problem.text.trim())
    .slice(0, 4)
    .map((problem) => ({ severity: SEVERITIES.has(problem.severity) ? problem.severity : 'minor', text: problem.text.trim().slice(0, 220) }))
  return {
    rating,
    title: typeof reply.title === 'string' ? reply.title.trim().slice(0, 80) : '',
    review: reply.review.trim().slice(0, 700),
    worked: strings(reply.worked),
    problems,
    suggestions: strings(reply.suggestions),
  }
}

/** Asks the model for the persona's review. Null without a model, or when it fails. */
export async function writeReview(brain, { mission, app, journal, notes, verdict, evidence, finalText, mask = (text) => text, testSteps = null }) {
  if (!brain.canThink) return null
  const facts = [
    `Persona: ${mission.persona}`,
    `Goal: ${mission.goal}`,
    app ? `The app: ${app}` : '',
    testSteps?.length ? `Test case results:\n${testSteps.map((step, index) => `${index + 1}. [${step.status}] ${step.do} (expected: ${step.expect})${step.observed ? ` seen: "${step.observed}"` : ''}`).join('\n')}` : '',
    `Steps:\n${journal.map((entry, index) => `${index + 1}. ${entry.thought ? `(${entry.thought}) ` : ''}${entry.what}${entry.error ? ` -> FAILED: ${entry.error}` : ''}`).join('\n') || '(none)'}`,
    `Remarks noted along the way:\n${notes.map((note) => `- ${note}`).join('\n') || '(none)'}`,
    `Automated verdict: ${verdict.status} (${verdict.reason})${verdict.detail ? `: ${verdict.detail}` : ''}`,
    `Failed network requests:\n${evidence.apiErrors.slice(0, 8).map((error) => `${error.method} ${error.path} -> ${error.status || 'no response'}`).join('\n') || 'none'}`,
    `Page crashes:\n${evidence.pageErrors.slice(0, 3).join('\n') || 'none'}`,
    `Console errors:\n${evidence.consoleErrors.slice(0, 3).join('\n') || 'none'}`,
    `Last screen text:\n${finalText.slice(0, 1500)}`,
  ].filter(Boolean).join('\n\n')
  try {
    const review = normalizeReview(await brain.json(REVIEW_SYSTEM, mask(facts)), verdict.status)
    if (!review) return null
    return {
      ...review,
      title: mask(review.title),
      review: mask(review.review),
      worked: review.worked.map(mask),
      problems: review.problems.map((problem) => ({ ...problem, text: mask(problem.text) })),
      suggestions: review.suggestions.map(mask),
    }
  } catch {
    return null
  }
}
