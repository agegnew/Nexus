// The part that decides. With an OpenAI key it is a model looking at the page; without one it
// replays the mission's scripted journey, so the swarm still runs end to end on any laptop.

const DEFAULT_MODEL = 'gpt-4o-mini'
const BASE_URL = process.env.NEXUS_SWARM_BASE_URL || 'https://api.openai.com/v1'

export function createBrain({ apiKey = '', model = '' } = {}) {
  const key = apiKey.trim()
  const chosen = model.trim() || DEFAULT_MODEL
  return {
    canThink: key.length > 0,
    model: key ? chosen : 'scripted',
    json: (system, user) => chat(key, chosen, system, user),
  }
}

async function chat(key, model, system, user) {
  if (!key) throw new Error('No OpenAI key')
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 30_000)
  try {
    const response = await fetch(`${BASE_URL}/chat/completions`, {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
      body: JSON.stringify({
        model,
        temperature: 0,
        response_format: { type: 'json_object' },
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: user },
        ],
      }),
    })
    if (!response.ok) throw new Error(`OpenAI ${response.status}: ${(await response.text()).slice(0, 200)}`)
    const body = await response.json()
    return JSON.parse(body.choices?.[0]?.message?.content ?? '{}')
  } finally {
    clearTimeout(timer)
  }
}

export const AGENT_SYSTEM = `You are a user testing a web app in a real browser. You get your persona and
goal, what the app is, the test account, the page's accessibility tree, what you already did, and any
errors the app produced. Behave like that real person: read the screen, use the navigation, sign in
when the goal needs it. Choose ONE next action. Elements are addressed by ARIA role and accessible name
exactly as they appear in the tree.
Actions:
  {"type":"click","role":"button|link|checkbox|tab|menuitem|option","name":"..."}
  {"type":"fill","name":"label or placeholder of the field","value":"text to type"}   (value "" clears it)
  {"type":"select","name":"label of the combobox","value":"visible option text"}
  {"type":"check","name":"label of the checkbox","value":"true|false"}
  {"type":"press","value":"Enter|Escape|Tab"}   (key press in the focused field)
  {"type":"goto","value":"/path"}   (only for a page a user would reach by typing its address)
  {"type":"back"}
  {"type":"scroll"}
  {"type":"wait"}
  {"type":"done","status":"passed|failed","summary":"what happened, under 14 words"}
Never invent a password: type the placeholders you are given, exactly.
Say done/passed only when the page visibly proves the goal. Say done/failed as soon as the goal is
clearly blocked: an error, a blank page, a button that does nothing after you waited, a missing
feature, a login that is refused, or nonsense on screen such as "undefined". Do not repeat an action
that already did nothing twice.
"note" is optional: something a real user would remark on right now (a confusing label, a slow page,
a missing confirmation, a layout problem, something pleasant). Leave it out when there is nothing.
Reply as JSON: {"thought":"one short first-person sentence","note":"optional","action":{...}}`
