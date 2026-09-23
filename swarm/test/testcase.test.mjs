// The bookkeeping that turns a vague "go test the app" into a written test case the tester has
// to work through step by step, with proof from the page for every step.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeSteps, stepsFor, changeBetween, evidenceOnPage, sameAction, testCaseText, freshStepResults, isOpeningStep, quotedProof } from '../lib/testcase.mjs'
import { settleSteps } from '../lib/agent.mjs'

test('planner steps are cleaned, and a goal-only mission becomes one step', () => {
  assert.deepEqual(normalizeSteps(['Open Orders', { do: '  Click "New"  ', expect: 'A form' }, { expect: 'orphan' }, null]), [
    { do: 'Open Orders', expect: '' },
    { do: 'Click "New"', expect: 'A form' },
  ])
  assert.deepEqual(stepsFor({ goal: 'Find Grace', expect: 'grace@' }), [{ do: 'Find Grace', expect: 'The page shows text matching /grace@/' }])
})

test('the tester is told what its action changed, or that nothing did', () => {
  const before = { url: 'http://app/orders', snapshot: '- heading "Orders"\n- button "New order"' }
  const same = changeBetween(before, { ...before })
  assert.equal(same.changed, false)
  assert.match(same.text, /NO VISIBLE CHANGE/)

  const after = { url: 'http://app/orders/new?x=1', snapshot: '- heading "New order"\n- textbox "Quantity"' }
  const moved = changeBetween(before, after, [{ method: 'POST', path: '/api/orders', status: 500 }])
  assert.equal(moved.changed, true)
  assert.match(moved.text, /URL is now \/orders\/new\?x=1/)
  assert.match(moved.text, /appeared: heading "New order" \| textbox "Quantity"/)
  assert.match(moved.text, /POST \/api\/orders -> 500/)
  assert.equal(moved.failedRequests, 1)
})

test('a pass needs proof that is really on the page', () => {
  const page = 'Order #1042 placed: 2 × Cold brew\nThanks, Ada!'
  assert.ok(evidenceOnPage('Order #1042 placed', page))
  assert.ok(evidenceOnPage('"order #1042  PLACED"', page), 'quotes, case and spacing do not matter')
  assert.ok(evidenceOnPage('Order #1042 placed, cold brew', page), 'every meaningful word is on the page')
  assert.ok(!evidenceOnPage('Order confirmed', page))
  assert.ok(!evidenceOnPage('', page))
})

test('a repeat is the same action on the same element with the same value', () => {
  assert.ok(sameAction({ type: 'click', role: 'button', name: 'Save' }, { type: 'click', role: 'button', name: 'Save' }))
  assert.ok(!sameAction({ type: 'click', name: 'Save' }, { type: 'click', name: 'Cancel' }))
  assert.ok(!sameAction({ type: 'fill', name: 'Qty', value: '1' }, { type: 'fill', name: 'Qty', value: '2' }))
})

test('the test case shows every step and marks the current one', () => {
  const results = freshStepResults([{ do: 'Open Orders', expect: 'A list' }, { do: 'Click New', expect: 'A form' }])
  results[0].status = 'passed'
  results[0].observed = 'Orders'
  results[1].status = 'running'
  const text = testCaseText(results, 1)
  assert.match(text, /\[x\] Step 1: Open Orders\n {6}Expected: A list \(seen: "Orders"\)/)
  assert.match(text, /\[>\] Step 2: Click New\n {6}Expected: A form {3}<-- CURRENT STEP/)
})

test('a failed run always names the step it failed on; later steps were never tried', () => {
  const results = freshStepResults([{ do: 'a' }, { do: 'b' }, { do: 'c' }])
  results[0].status = 'passed'
  results[1].status = 'running'
  settleSteps(results, { status: 'failed' }, 'The page crashed')
  assert.deepEqual(results.map((step) => step.status), ['passed', 'failed', 'skipped'])
  assert.equal(results[1].observed, 'The page crashed')

  const scripted = freshStepResults([{ do: 'a' }, { do: 'b' }])
  settleSteps(scripted, { status: 'passed' }, 'Done')
  assert.deepEqual(scripted.map((step) => step.status), ['passed', 'passed'])
})

test('a reload that only fires requests is not a visible change', () => {
  const page = { url: 'http://app/', snapshot: '- heading "Harbor Market"' }
  const reload = changeBetween(page, { ...page }, [{ method: 'GET', path: '/api/users', status: 200 }])
  assert.equal(reload.changed, false)
  assert.match(reload.text, /^NO VISIBLE CHANGE.*requests: GET \/api\/users -> 200/)
})

test('"open the home page" steps are dropped: every tester starts there', () => {
  assert.ok(isOpeningStep({ do: 'Open the home page.' }))
  assert.ok(isOpeningStep({ do: "Go to the app's homepage" }))
  assert.ok(isOpeningStep({ do: 'Visit the website' }))
  assert.ok(!isOpeningStep({ do: 'Open the Orders page from the top bar' }))
  assert.ok(!isOpeningStep({ do: 'Go to /settings' }))
  assert.deepEqual(normalizeSteps([{ do: 'Open the home page.', expect: 'banner' }, { do: 'Click "Order"', expect: 'form' }]).map((step) => step.do), ['Click "Order"'])
})

test('quoted text in the expected result proves the step once it is on the page', () => {
  const page = 'Settings\nSettings saved\nNickname'
  assert.equal(quotedProof("A message 'Settings saved' appears", page), 'Settings saved')
  assert.equal(quotedProof('A message “Settings saved” and "Nickname"', page), 'Settings saved / Nickname')
  assert.equal(quotedProof("A message 'Order placed' appears", page), null, 'a missing quote proves nothing')
  assert.equal(quotedProof("The quantity shows '2'", '2 items'), null, 'short or numeric quotes are ignored')
  assert.equal(quotedProof('The settings form', page), null, 'no quote, no automatic proof')
})
