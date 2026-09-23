// What each of the five agents sets out to do.
//
// A mission is a persona plus a goal written the way a real user would say it. `expect` is the
// text that proves the goal was reached and `forbid` is text no healthy screen should ever show.
// Both are checked against the page itself, so a verdict never rests on the model's word alone.
// `script` is the same journey as fixed steps: it runs when there is no API key, and it is the
// fallback if the model stops answering halfway through a run on stage.

import { normalizeSteps } from './testcase.mjs'

export const UNIVERSAL_FORBID = ['undefined', 'NaN', '[object Object]']

export const DEMO_MISSIONS = [
  {
    id: 'shopper',
    persona: 'Shopper',
    emoji: '🛒',
    goal: 'Order two cold brews and make sure you get an order number.',
    expect: 'Order #\\d+ placed',
    steps: [
      { do: 'Click "Order" in the top bar', expect: 'An order form with Item and Quantity fields' },
      { do: 'Choose "Cold brew" as the item and type 2 as the quantity', expect: 'Item shows Cold brew and Quantity shows 2' },
      { do: 'Click "Place order"', expect: 'A confirmation like "Order #1234 placed"' },
    ],
    script: [
      { step: 0, type: 'click', role: 'button', name: 'Order', thought: 'The order page is in the top bar.' },
      { step: 1, type: 'select', name: 'Item', value: 'Cold brew', thought: 'Pick cold brew from the menu.' },
      { step: 1, type: 'fill', name: 'Quantity', value: '2', thought: 'Two of them.' },
      { step: 2, type: 'click', role: 'button', name: 'Place order', thought: 'Submit the order.' },
    ],
  },
  {
    id: 'explorer',
    persona: 'Explorer',
    emoji: '🔎',
    goal: "Find Grace Hopper in the customer list and open her profile to see her email.",
    expect: 'grace@harbor\\.market',
    steps: [
      { do: 'Click "Customers" in the top bar', expect: 'A list of customers that includes Grace Hopper' },
      { do: 'Click "Grace Hopper"', expect: 'Her profile with the email grace@harbor.market' },
    ],
    script: [
      { step: 0, type: 'click', role: 'button', name: 'Customers', thought: 'Customers live on the first page.' },
      { step: 1, type: 'click', role: 'button', name: 'Grace Hopper', thought: 'Grace is in the list, open her.' },
    ],
  },
  {
    id: 'settings',
    persona: 'Regular',
    emoji: '⚙️',
    goal: 'Turn off the weekly newsletter and save your settings. Confirm they were saved.',
    expect: 'Settings saved',
    steps: [
      { do: 'Click "Settings" in the top bar', expect: 'A settings form with a newsletter checkbox' },
      { do: 'Untick "Send me the weekly newsletter"', expect: 'The newsletter checkbox is unticked' },
      { do: 'Click "Save settings"', expect: 'A message "Settings saved"' },
    ],
    script: [
      { step: 0, type: 'click', role: 'button', name: 'Settings', thought: 'Preferences are under Settings.' },
      { step: 1, type: 'check', name: 'Send me the weekly newsletter', value: 'false', thought: 'Untick the newsletter.' },
      { step: 2, type: 'click', role: 'button', name: 'Save settings', thought: 'Save it.' },
      { step: 2, type: 'wait', thought: 'Waiting for the confirmation.' },
    ],
  },
  {
    id: 'manager',
    persona: 'Manager',
    emoji: '📊',
    goal: "Open Analytics and read this month's revenue figure.",
    expect: 'Revenue',
    steps: [
      { do: 'Click "Analytics" in the top bar', expect: 'The Analytics page loads without an error' },
      { do: "Read this month's revenue", expect: 'A Revenue figure with a real amount' },
    ],
    script: [
      { step: 0, type: 'click', role: 'button', name: 'Analytics', thought: 'Revenue should be on Analytics.' },
      { step: 1, type: 'wait', thought: 'Letting the numbers load.' },
    ],
  },
  {
    id: 'chaos',
    persona: 'Chaos Monkey',
    emoji: '🐒',
    goal: 'Try to break the order form: clear the quantity, submit it anyway, and see if the app copes.',
    expect: null,
    steps: [
      { do: 'Click "Order" in the top bar', expect: 'The order form' },
      { do: 'Clear the Quantity field', expect: 'Quantity is empty' },
      { do: 'Click "Place order" twice', expect: 'A clear validation message, and no "undefined" or "NaN" anywhere' },
    ],
    script: [
      { step: 0, type: 'click', role: 'button', name: 'Order', thought: 'Forms are where apps break.' },
      { step: 1, type: 'fill', name: 'Quantity', value: '', thought: 'Empty quantity. Let us see.' },
      { step: 2, type: 'click', role: 'button', name: 'Place order', thought: 'Submit it anyway.' },
      { step: 2, type: 'click', role: 'button', name: 'Place order', thought: 'And again, impatient user style.' },
    ],
  },
]

const PLANNER_SYSTEM = `You are the lead of a team of 5 manual QA testers about to test a real web app.
You get the project's own documentation (README, CLAUDE.md and similar), the pages its code declares,
a map of its API calls from static analysis, the pages a quick crawl found (with their accessibility
trees), whether a test account exists, and what the developer wants tested.
First understand what the app is for and who uses it. Then write exactly 5 test cases, one per tester,
each played by a different kind of real user of THIS app. Together they must cover the app's most
important features.
A test case is a short list of clear, simple steps, like a manual QA script:
  - 2 to 6 steps. The tester is ALREADY on the home page: never write a step that only opens it.
  - Each step is ONE small thing a user does: click a link or button, fill a field, pick an option,
    tick a box, or go to a /path. A step that only looks at the screen is allowed only as the last.
    Name the exact link, button or field as it appears in the crawl, and give the exact values to type.
  - Each step has an expected result the tester can SEE on the page BECAUSE of that step, naming the
    text or element to look for ("the Orders page lists at least one order", "a message 'Settings
    saved' appears"). Never expect something that was already on the screen before the step.
  - The last step checks the outcome that matters (the data was saved, the item appears, the total is right).
  - Put quotes only around text you have SEEN in the crawl (a heading, a button, a field label); the
    runner checks quoted text against the page. Text you have not seen, such as the confirmation
    after a submit, you must not guess: describe it without quotes ("a confirmation that the order
    was placed, with an order number"). A step that only types into a field expects the field
    to show that value; a validation or error message is expected on the step that submits.
Rules:
- If the app has a login and a test account exists, one test case checks signing in itself (then
  something only a signed-in user can see), and test cases that need an account start by signing in:
  type {{username}} and {{password}}.
- If the app has sign-up but no test account, one test case signs up a new user with {{new_name}},
  {{new_email}}, {{new_username}} and {{new_password}}.
- Prefer features whose API calls are marked NO BACKEND MATCH: they are likely broken.
- Exactly one tester is a "Chaos Monkey": its steps feed a form bad input (empty required fields, a
  huge number, odd characters, a double submit) and expect a clear validation message, not a crash.
- Follow the developer's focus when one is given.
- Only use screens, links, buttons and fields that the crawl or the documentation show exist.
Reply as JSON: {"app":"one sentence: what the app is and who it is for",
"missions":[{"id":"short-kebab-id","persona":"one or two words","emoji":"one emoji",
"goal":"the test's title: what it proves, one sentence",
"login":true|false,
"steps":[{"do":"the instruction","expect":"what must be visible afterwards"}],
"expect":"a short regex of on-screen text that proves the final result, or null when unsure"}]}`

/**
 * Asks the model for five missions tailored to this app. Returns null when it cannot.
 * [context] is the project brief, [pages] what the scout saw, [account] the test account.
 */
export async function planMissions(brain, { graphText, homeSnapshot, context = null, pages = [], account = null, focus = '' }) {
  if (!brain.canThink) return null
  const crawl = pages.length
    ? pages.map((page) => `## ${page.path} (${page.title || 'untitled'})${page.hasPassword ? ' [has a password field]' : ''}\n${page.snapshot}`).join('\n\n')
    : `## / (home)\n${homeSnapshot.slice(0, 6000)}`
  const brief = [
    context?.text ? `PROJECT DOCUMENTATION\n${context.text}` : 'PROJECT DOCUMENTATION\n(none found)',
    `API MAP\n${graphText}`,
    `CRAWLED PAGES\n${crawl.slice(0, 14000)}`,
    `TEST ACCOUNT\n${account?.hasLogin ? `yes, username "${account.username}"` : 'none'}`,
    `DEVELOPER FOCUS\n${focus?.trim() || '(none, cover the main features)'}`,
  ].join('\n\n')
  try {
    const reply = await brain.json(PLANNER_SYSTEM, brief)
    const missions = (reply?.missions ?? []).slice(0, 5).map((mission, index) => ({
      id: String(mission.id || `agent-${index + 1}`).replace(/[^a-z0-9-]/gi, '-').toLowerCase(),
      persona: String(mission.persona || `Agent ${index + 1}`).slice(0, 24),
      emoji: String(mission.emoji || '🤖').slice(0, 4),
      goal: String(mission.goal || 'Explore the app.').slice(0, 280),
      login: Boolean(mission.login),
      steps: normalizeSteps(mission.steps),
      expect: validRegex(mission.expect) ? mission.expect : null,
      script: null,
    })).map((mission) => ({ ...mission, maxSteps: actionBudget(mission) }))
    if (missions.length !== 5 || missions.some((mission) => mission.steps.length === 0)) return null
    return { missions: dedupeIds(missions), app: typeof reply.app === 'string' ? reply.app.slice(0, 240) : '' }
  } catch {
    return null
  }
}

/** Room for a few actions per step, and a little slack; never enough to wander for minutes. */
function actionBudget(mission) {
  return Math.min(30, Math.max(8, mission.steps.length * 4 + 2))
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
