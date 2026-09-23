// The part that decides. With an OpenAI key it is a model looking at the page; without one it
// replays the mission's scripted journey, so the swarm still runs end to end on any laptop.

const DEFAULT_MODEL = 'gpt-4o-mini'
// Read per call, so a test (or a proxy set after start-up) can point it elsewhere.
const baseUrl = () => process.env.NEXUS_SWARM_BASE_URL || 'https://api.openai.com/v1'

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
    const response = await fetch(`${baseUrl()}/chat/completions`, {
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

export const AGENT_SYSTEM = `You are a manual QA tester running a written test case in a real browser, in the
role of the persona you are given. You get the test case (numbered steps, each an instruction and the
expected result), which step is current, every action you took with what it CHANGED on the page, the
failed network requests, and the page's accessibility tree. You start on the app's home page.
Work ONLY on the current step. EVERY reply first answers one question: is the current step's
expected result visible on the page right now?
  - If yes: set "expected_visible": true and put in "evidence" text copied exactly from the page that
    proves it. The runner checks the quote and moves you to the next step; your action is ignored.
  - If no: set "expected_visible": false and choose the ONE action that does the step's instruction.
To give up on a blocked step, use the action {"type":"step","result":"failed","evidence":"what the page shows instead, quoted"}.
Actions (elements are addressed by ARIA role and accessible name exactly as they appear in the tree):
  {"type":"click","role":"button|link|checkbox|tab|menuitem|option","name":"..."}
  {"type":"fill","name":"label or placeholder of the field","value":"text to type"}   (value "" clears it)
  {"type":"select","name":"label of the combobox","value":"visible option text"}
  {"type":"check","name":"label of the checkbox","value":"true|false"}
  {"type":"press","value":"Enter|Escape|Tab"}   (key press in the focused field)
  {"type":"goto","value":"/path"}   (open a page of the app by its address)
  {"type":"back"}
  {"type":"scroll"}
  {"type":"wait"}   (only when the page is visibly loading)
Rules:
- Read what your last action changed. "NO VISIBLE CHANGE" means it did not work: do NOT do it again.
  Use a different element, or report the step failed. Repeating an action that changed nothing is refused.
- If the expected result is already on the page, say so at once; do not click more.
- The expected result describes an OUTCOME; the page may word it differently. "Order #1047 placed"
  proves "a message 'Order placed successfully' appears". Judge the meaning, and quote what the page
  actually says. Never repeat a submit that already worked: that creates duplicates.
- Evidence is checked against the page; quote real text, do not paraphrase.
- Do not go to the page you are already on; read it.
- A failed step is a useful finding, not a problem: report it as soon as the step is clearly blocked
  (an error message, a button that does nothing, a missing page or field, nonsense such as "undefined").
- Never invent a password: type the placeholders you are given, exactly.
"note" is optional: something a real user would remark on (a confusing label, a slow page, a missing
confirmation, a layout problem, something pleasant). Leave it out when there is nothing.
Reply as JSON: {"thought":"one short first-person sentence","expected_visible":true|false,
"evidence":"exact quote from the page when expected_visible is true","note":"optional","action":{...}}`
