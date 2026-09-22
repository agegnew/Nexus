// What each of the five agents sets out to do.
//
// A mission is a persona plus a goal written the way a real user would say it. `expect` is the
// text that proves the goal was reached and `forbid` is text no healthy screen should ever show.
// Both are checked against the page itself, so a verdict never rests on the model's word alone.
// `script` is the same journey as fixed steps: it runs when there is no API key, and it is the
// fallback if the model stops answering halfway through a run on stage.

export const UNIVERSAL_FORBID = ['undefined', 'NaN', '[object Object]']

export const DEMO_MISSIONS = [
  {
    id: 'shopper',
    persona: 'Shopper',
    emoji: '🛒',
    goal: 'Order two cold brews and make sure you get an order number.',
    expect: 'Order #\\d+ placed',
    script: [
      { type: 'click', role: 'button', name: 'Order', thought: 'The order page is in the top bar.' },
      { type: 'select', name: 'Item', value: 'Cold brew', thought: 'Pick cold brew from the menu.' },
      { type: 'fill', name: 'Quantity', value: '2', thought: 'Two of them.' },
      { type: 'click', role: 'button', name: 'Place order', thought: 'Submit the order.' },
    ],
  },
  {
    id: 'explorer',
    persona: 'Explorer',
    emoji: '🔎',
    goal: "Find Grace Hopper in the customer list and open her profile to see her email.",
    expect: 'grace@harbor\\.market',
    script: [
      { type: 'click', role: 'button', name: 'Customers', thought: 'Customers live on the first page.' },
      { type: 'click', role: 'button', name: 'Grace Hopper', thought: 'Grace is in the list, open her.' },
    ],
  },
  {
    id: 'settings',
    persona: 'Regular',
    emoji: '⚙️',
    goal: 'Turn off the weekly newsletter and save your settings. Confirm they were saved.',
    expect: 'Settings saved',
    script: [
      { type: 'click', role: 'button', name: 'Settings', thought: 'Preferences are under Settings.' },
      { type: 'check', name: 'Send me the weekly newsletter', value: 'false', thought: 'Untick the newsletter.' },
      { type: 'click', role: 'button', name: 'Save settings', thought: 'Save it.' },
      { type: 'wait', thought: 'Waiting for the confirmation.' },
    ],
  },
  {
    id: 'manager',
    persona: 'Manager',
    emoji: '📊',
    goal: "Open Analytics and read this month's revenue figure.",
    expect: 'Revenue',
    script: [
      { type: 'click', role: 'button', name: 'Analytics', thought: 'Revenue should be on Analytics.' },
      { type: 'wait', thought: 'Letting the numbers load.' },
    ],
  },
  {
    id: 'chaos',
    persona: 'Chaos Monkey',
    emoji: '🐒',
    goal: 'Try to break the order form: clear the quantity, submit it anyway, and see if the app copes.',
    expect: null,
    script: [
      { type: 'click', role: 'button', name: 'Order', thought: 'Forms are where apps break.' },
      { type: 'fill', name: 'Quantity', value: '', thought: 'Empty quantity. Let us see.' },
      { type: 'click', role: 'button', name: 'Place order', thought: 'Submit it anyway.' },
      { type: 'click', role: 'button', name: 'Place order', thought: 'And again, impatient user style.' },
    ],
  },
]

const PLANNER_SYSTEM = `You plan exploratory end-to-end tests for a web app. You will get a map of the
app's API calls (from static analysis) and the accessibility tree of its home page.
Invent exactly 5 test agents, each a different kind of real user with one concrete goal that can be
reached in under 10 clicks from the home page. Cover the most important features. Prefer features
whose API calls are marked NO BACKEND MATCH, because those are likely broken. Exactly one agent must
be a "Chaos Monkey" that tries to break a form with empty, huge or odd input.
Reply as JSON: {"missions":[{"id":"short-kebab-id","persona":"one or two words","emoji":"one emoji",
"goal":"what the user wants, one sentence","expect":"a short regex of on-screen text that proves success, or null"}]}`

/** Asks the model for five missions tailored to this app. Returns null when it cannot. */
export async function planMissions(brain, { graphText, homeSnapshot }) {
  if (!brain.canThink) return null
  try {
    const reply = await brain.json(PLANNER_SYSTEM, `API map:\n${graphText}\n\nHome page:\n${homeSnapshot.slice(0, 6000)}`)
    const missions = (reply?.missions ?? []).slice(0, 5).map((mission, index) => ({
      id: String(mission.id || `agent-${index + 1}`).replace(/[^a-z0-9-]/gi, '-').toLowerCase(),
      persona: String(mission.persona || `Agent ${index + 1}`).slice(0, 24),
      emoji: String(mission.emoji || '🤖').slice(0, 4),
      goal: String(mission.goal || 'Explore the app.').slice(0, 200),
      expect: validRegex(mission.expect) ? mission.expect : null,
      script: null,
    }))
    return missions.length === 5 ? dedupeIds(missions) : null
  } catch {
    return null
  }
}

function validRegex(source) {
  if (!source || typeof source !== 'string') return false
  try {
    new RegExp(source, 'i')
    return true
  } catch {
    return false
  }
}

function dedupeIds(missions) {
  const seen = new Set()
  return missions.map((mission, index) => {
    const id = seen.has(mission.id) ? `${mission.id}-${index + 1}` : mission.id
    seen.add(id)
    return { ...mission, id }
  })
}

/** The built-in journeys suit the Harbor Market demo; anything else needs the planner. */
export function looksLikeDemoApp(graphText, homeText) {
  return /Harbor Market/i.test(homeText) || /Settings\.tsx: PUT \/api\/users/.test(graphText)
}
