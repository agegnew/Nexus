/*
 * Bridge between the player and the IDE.
 *
 * Kotlin pushes state in as DOM events and the player calls back out through
 * window.__reelCallback, which ReelToolWindowFactory installs via JBCefJSQuery. Until
 * that exists the calls are no-ops and the page falls back to the bundled fixture, so
 * index.html still renders and plays correctly in a plain browser during development.
 *
 * Incoming:  yasin-reel:state | :progress | :error | :notice | :storyboard
 * Outgoing:  {type:'generate', audience} | {type:'openFile', file, line} | {type:'ready'}
 *
 * Export and toolchain messages belong to the transport and live in player.js.
 */
(function () {
  'use strict';

  var params = new URLSearchParams(window.location.search);
  var nameEl = document.getElementById('project-name');
  var statusEl = document.getElementById('status');
  var buttons = {};
  var built = {};
  var notices = {};
  var announced = false;

  var noticeEl = document.getElementById('notice');
  var noticeHead = document.getElementById('notice-head');
  var noticeBody = document.getElementById('notice-body');
  var noticeFix = document.getElementById('notice-fix');


  // ---- reel scope ----------------------------------------------------------
  // The same periods the Activity tab offers, so "last week" means one thing in
  // this plugin. Kept inline because this runtime is plain scripts, not modules.

  function isoDay(date) {
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  }

  function startOfWeek(date) {
    var start = new Date(date);
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
    return start;
  }

  function shiftDays(date, days) {
    var next = new Date(date);
    next.setDate(next.getDate() + days);
    return next;
  }

  function periodDates(id) {
    var today = new Date();
    switch (id) {
      case 'this-week': return [startOfWeek(today), today];
      case 'last-week': return [shiftDays(startOfWeek(today), -7), shiftDays(startOfWeek(today), -1)];
      case 'this-month': return [new Date(today.getFullYear(), today.getMonth(), 1), today];
      case 'last-month': return [
        new Date(today.getFullYear(), today.getMonth() - 1, 1),
        new Date(today.getFullYear(), today.getMonth(), 0)
      ];
      case 'last-30': return [shiftDays(today, -29), today];
      default: return null;
    }
  }

  function el(id) { return document.getElementById(id); }

  function scopeMode() {
    var checked = document.querySelector('input[name="reel-scope"]:checked');
    return checked ? checked.value : 'launch';
  }

  function currentRange() {
    var period = el('scope-period');
    var chosen = period ? period.value : 'last-week';
    if (chosen === 'custom') {
      var from = el('scope-from');
      var to = el('scope-to');
      return { since: from && from.value, until: to && to.value };
    }
    var pair = periodDates(chosen);
    return pair ? { since: isoDay(pair[0]), until: isoDay(pair[1]) } : { since: '', until: '' };
  }

  function refreshScopeUi() {
    var recap = scopeMode() === 'recap';
    var range = el('scope-range');
    if (range) range.hidden = !recap;

    var custom = el('scope-period') && el('scope-period').value === 'custom';
    var fromField = el('scope-from-field');
    var toField = el('scope-to-field');
    if (fromField) fromField.hidden = !custom;
    if (toField) toField.hidden = !custom;

    var dates = el('scope-dates');
    if (dates) {
      var current = currentRange();
      dates.textContent = custom || !current.since ? '' : current.since + ' → ' + current.until;
    }
  }

  // The scope is part of a film's identity: a recap of last week is not the film a
  // recap of last month is, so replaying by audience alone would show the wrong one.
  function scopePayload() {
    if (scopeMode() !== 'recap') return { scope: 'launch' };
    var range = currentRange();
    var area = el('scope-area');
    var mine = el('scope-mine');
    var uncommitted = el('scope-uncommitted');
    return {
      scope: 'recap',
      since: range.since || '',
      until: range.until || '',
      area: area ? area.value : 'all',
      mine: mine ? mine.checked : true,
      uncommitted: uncommitted ? uncommitted.checked : true
    };
  }

  function scopeKey(audience) {
    var payload = scopePayload();
    return payload.scope === 'launch'
      ? audience + ':launch'
      : audience + ':recap:' + payload.since + ':' + payload.until + ':' + payload.area +
        ':' + payload.mine + ':' + payload.uncommitted;
  }

  function fillAreas(areas) {
    var select = el('scope-area');
    if (!select || !areas || !areas.length) return;
    var previous = select.value;
    select.innerHTML = '';
    areas.forEach(function (area) {
      var option = document.createElement('option');
      option.value = area.id;
      option.textContent = area.label;
      select.appendChild(option);
    });
    if (previous) select.value = previous;
    if (!select.value) select.value = 'all';
  }

  document.addEventListener('change', function (event) {
    if (!event.target.closest || !event.target.closest('#scope')) return;
    refreshScopeUi();
  });

  window.addEventListener('yasin-reel:scopes', function (event) {
    var detail = event.detail || {};
    fillAreas(detail.areas);
  });

  Array.prototype.forEach.call(document.querySelectorAll('.cut'), function (button) {
    buttons[button.dataset.audience] = button;
  });

  nameEl.textContent = params.get('projectName') || 'this project';

  function connected() {
    return typeof window.__reelCallback === 'function';
  }

  function toIde(message) {
    if (!connected()) return false;
    window.__reelCallback(JSON.stringify(message));
    return true;
  }

  function setState(button, state, label) {
    if (!button) return;
    var el = button.querySelector('.cut__state');
    el.dataset.state = state;
    el.textContent = label;
    button.disabled = state === 'working';
  }

  function hideNotice() {
    if (noticeEl) noticeEl.hidden = true;
  }

  /*
   * The honesty banner: this cut was not written by a model, and here is why.
   *
   * Deliberately not routed through say(), whose target sits inside #picker and is
   * hidden the moment a reel plays, and deliberately not on a timer. A viewer who
   * looks up thirty seconds in still deserves to know what they are watching.
   */
  function showNotice(detail) {
    if (!noticeEl) return;
    var d = detail;
    if (typeof d === 'string') {
      try { d = JSON.parse(d); } catch (e) { return; }
    }
    if (!d) return;
    if (d.audience) notices[d.audience] = d;
    if (d.aiRan) { hideNotice(); return; }
    noticeEl.dataset.tone = 'warn';
    noticeHead.textContent = d.headline || 'The AI did not run';
    noticeBody.textContent = d.message || '';
    noticeFix.textContent = d.remedy || '';
    noticeEl.hidden = false;
  }

  if (noticeEl) {
    document.getElementById('notice-close').addEventListener('click', hideNotice);
  }

  function say(message, tone) {
    statusEl.textContent = message;
    if (tone) statusEl.dataset.tone = tone;
    else statusEl.removeAttribute('data-tone');
  }

  /*
   * Kotlin sends the Storyboard as the event detail, but a hand fired event or a
   * pre-load global may hand over a JSON string or a wrapper, so all three are taken.
   */
  function normalise(detail) {
    var data = detail;
    if (typeof data === 'string') {
      try { data = JSON.parse(data); } catch (e) { return null; }
    }
    if (data && !data.scenes && data.storyboard) data = data.storyboard;
    if (!data || Object.prototype.toString.call(data.scenes) !== '[object Array]') return null;
    return data;
  }

  function playStoryboard(raw) {
    var storyboard = normalise(raw);
    if (!storyboard) {
      say('The IDE sent something this player could not read.', 'error');
      return false;
    }
    var button = buttons[storyboard.audience];
    // Keyed by scope as well as audience: a recap of last week is not the film a recap
    // of last month is, and replaying by audience alone would show the wrong one.
    built[scopeKey(storyboard.audience)] = storyboard;
    setState(button, 'ready', 'Built, click to replay');
    try {
      window.NexusReel.play(storyboard);
      say('Playing the ' + (storyboard.audience || 'reel') + ' cut.');
      return true;
    } catch (error) {
      // A thrown builder must not leave the viewer on a blank stage with no reason.
      window.NexusReel.stop();
      say('That cut could not be played: ' + (error && error.message ? error.message : error), 'error');
      setState(button, 'failed', 'Failed, click to retry');
      return false;
    }
  }

  Array.prototype.forEach.call(document.querySelectorAll('.cut'), function (button) {
    button.addEventListener('click', function () {
      var audience = button.dataset.audience;
      var key = scopeKey(audience);
      // A rebuild starts clean, so a stale verdict never describes the new run.
      hideNotice();

      // Already built in this session, so replay rather than pay for it twice. The
      // banner is restored with it, otherwise a replayed offline cut looks AI written.
      if (built[key]) {
        playStoryboard(built[key]);
        if (notices[audience]) showNotice(notices[audience]);
        return;
      }

      setState(button, 'working', 'Working...');
      var payload = scopePayload();
      payload.type = 'generate';
      payload.audience = audience;
      say(payload.scope === 'recap'
        ? 'Asking the IDE for the ' + audience + ' recap of ' + payload.since + ' to ' + payload.until + '.'
        : 'Asking the IDE for the ' + audience + ' launch video.');
      if (toIde(payload)) return;

      setState(button, 'idle', 'Not connected to the IDE');
      if (window.ReelFixture) {
        say('No IDE attached, so this is the bundled fixture rather than your project.');
        playStoryboard(window.ReelFixture.pick(audience));
        fixtureNotice(audience);
      } else {
        say('Opened outside the IDE, so generating is unavailable.', 'error');
      }
    });
  });

  window.addEventListener('yasin-reel:state', function (event) {
    var detail = event.detail || {};
    if (detail.status) say(detail.status);
  });

  window.addEventListener('yasin-reel:progress', function (event) {
    var detail = event.detail || {};
    setState(buttons[detail.audience], 'working', detail.message || 'Working...');
    if (detail.message) say(detail.message);
  });

  /*
   * The banner, not an error. A reel built offline still plays, and routing this
   * through the error path would mark the cut failed and hide a perfectly good film.
   */
  window.addEventListener('yasin-reel:notice', function (event) {
    showNotice(event.detail);
  });

  window.addEventListener('yasin-reel:error', function (event) {
    var detail = event.detail || {};
    setState(buttons[detail.audience], 'failed', 'Failed, click to retry');
    say(detail.message || 'The IDE could not build that cut.', 'error');
  });

  window.addEventListener('yasin-reel:storyboard', function (event) {
    playStoryboard(event.detail);
  });

  /** The offline preview must never pass for a real run either. */
  function fixtureNotice(audience) {
    showNotice({
      audience: audience,
      aiRan: false,
      headline: 'The AI did not run',
      message: 'This is the bundled fixture, not your project.',
      remedy: 'Open the Reel tool window inside the IDE to build a cut from real code.'
    });
  }

  function announce() {
    if (announced || !connected()) return;
    announced = true;
    // The fixture may already be on screen, and the handshake is no reason to talk
    // over whatever the player is saying about it.
    if (!window.NexusReel.composition()) say('Ready. Pick a cut to build.');
    refreshScopeUi();
    toIde({ type: 'ready' });
    // The area list comes from the project, so it is asked for rather than hard-coded.
    toIde({ type: 'scopes' });
  }

  /*
   * The bridge is injected on load end, which lands after this script runs. Watching
   * the assignment beats polling for it: the page reacts on the exact tick the IDE
   * attaches, and no timer has to exist at all.
   */
  (function watchForIde() {
    if (connected()) {
      announce();
      return;
    }
    var installed = null;
    try {
      Object.defineProperty(window, '__reelCallback', {
        configurable: true,
        get: function () { return installed; },
        set: function (fn) { installed = fn; announce(); }
      });
    } catch (error) {
      window.addEventListener('load', announce);
    }
  })();

  // A storyboard handed over before this script ran wins over everything else.
  if (window.__REEL_STORYBOARD__) {
    playStoryboard(window.__REEL_STORYBOARD__);
  } else if (params.get('fixture') && window.ReelFixture) {
    var wanted = params.get('fixture') === 'stakeholder' ? 'stakeholder' : 'technical';
    say('Playing the bundled fixture, no IDE required.');
    playStoryboard(window.ReelFixture.pick(wanted));
    fixtureNotice(wanted);
  } else if (!connected()) {
    say('Waiting for the IDE. Add ?fixture=1 to preview the player without one.');
  }
})();
