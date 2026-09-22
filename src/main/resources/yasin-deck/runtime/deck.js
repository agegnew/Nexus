/*
 * The deck tab: two buttons, a progress line, and the finished slides.
 *
 * Everything real happens in Kotlin. This asks for a deck, reports what the pipeline
 * says while it runs, and then draws what came back. The bridge it talks over is
 * injected by DeckToolWindowFactory, which is why every call is guarded: the same page
 * opens in a plain browser during development, where there is no IDE to answer.
 */
(function () {
  'use strict';

  /*
   * Shown as a panel inside the Map page rather than as a window of its own.
   *
   * The page keeps its own header for the standalone and exported cases, where nothing
   * else says what you are looking at. Embedded, the page above already carries the
   * project name and the row of views, so repeating it is two headers deep in a tool
   * window that is narrow to begin with.
   */
  if (window.parent !== window) document.documentElement.setAttribute('data-embedded', '');

  var dom = {};
  var busy = false;

  /** The deck on screen: its slides, which one is showing, and the rail's buttons. */
  var show = { slides: [], at: 0, rail: [], notes: [] };

  /*
   * The date range, from /shared/scope.js, which the Reel tab loads too. Guarded because
   * this page is also opened straight off disk during development, where an absolute
   * path resolves to nothing, and a deck that refuses to build because a date picker is
   * missing would be a poor trade.
   */
  var scope = window.NexusScope || {
    payload: function () { return { scope: 'launch' }; },
    describe: function () { return 'the whole project'; },
    isRecap: function () { return false; },
    fillAreas: function () {},
    watch: function () {}
  };

  function byId(id) { return document.getElementById(id); }

  function cache() {
    dom.picker = byId('picker');
    dom.result = byId('result');
    dom.rail = byId('rail');
    dom.stagewrap = byId('stagewrap');
    dom.slideFrame = byId('slide-frame');
    dom.slideStage = byId('slide-stage');
    dom.slideNo = byId('slide-no');
    dom.slideTotal = byId('slide-total');
    dom.slideName = byId('slide-name');
    dom.prev = byId('prev');
    dom.next = byId('next');
    dom.notes = byId('notes');
    dom.notesToggle = byId('notes-toggle');
    dom.notesOne = byId('notes-one');
    dom.notesAll = byId('notes-all');
    dom.tabOne = byId('tab-one');
    dom.tabAll = byId('tab-all');
    dom.status = byId('status');
    dom.project = byId('project-name');
    dom.title = byId('result-title');
    dom.sub = byId('result-sub');
    dom.open = byId('open');
    dom.reveal = byId('reveal');
    dom.back = byId('back');
    dom.notice = byId('notice');
    dom.noticeHead = byId('notice-head');
    dom.noticeBody = byId('notice-body');
    dom.noticeClose = byId('notice-close');
    dom.buttons = [byId('cut-technical'), byId('cut-stakeholder')];
  }

  /* ------------------------------------------------------------- the presenter */

  /**
   * Shows slide [index], clamped, and everything that follows from which one it is.
   *
   * One function rather than a handler per control, because the rail, the counter, the
   * arrows and the notes all say the same thing and the only way to keep them agreeing
   * is for one place to set them.
   */
  function goTo(index) {
    var total = show.slides.length;
    if (!total) return;
    var at = Math.max(0, Math.min(total - 1, index));
    show.at = at;
    var art = show.slides[at] || {};

    window.DeckPreview.paint(dom.slideStage, art);
    fitSlide();

    dom.slideNo.textContent = String(at + 1);
    dom.slideTotal.textContent = String(total);
    dom.slideName.textContent = art.title || '';
    dom.prev.disabled = at === 0;
    dom.next.disabled = at === total - 1;

    show.rail.forEach(function (item, i) {
      item.el.setAttribute('data-on', i === at ? 'true' : 'false');
    });
    // Keep the current thumbnail in view when the arrows moved us, not only when clicked.
    var current = show.rail[at];
    if (current && current.el.scrollIntoView) {
      current.el.scrollIntoView({ block: 'nearest' });
    }

    dom.notesOne.textContent = art.notes || 'No notes for this slide.';
    dom.notesOne.classList.toggle('notes__body--empty', !art.notes);
    show.notes.forEach(function (note, i) {
      note.setAttribute('data-on', i === at ? 'true' : 'false');
    });
  }

  function step(by) {
    goTo(show.at + by);
  }

  /** The big slide is a fixed 1280 wide stage scaled to whatever its frame ended up. */
  function fitSlide() {
    window.DeckPreview.fitOne(dom.slideFrame, dom.slideStage);
  }

  function fitRail() {
    show.rail.forEach(function (item) {
      window.DeckPreview.fitOne(item.frame, item.stage);
    });
  }

  /**
   * A trackpad swipe or a wheel notch moves one slide, not a scroll.
   *
   * Accumulated and gated rather than one slide per event, because a trackpad emits a
   * stream of small deltas for a single flick and acting on each one would skip half the
   * deck. The gate resets once the stream stops, which is what makes one flick one slide.
   */
  var wheelAt = 0;
  var wheelSum = 0;
  function onWheel(event) {
    var now = Date.now();
    if (now - wheelAt > 220) wheelSum = 0;
    wheelAt = now;
    // Horizontal counts too: on a trackpad, sideways is the natural way to move a deck.
    var delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
    wheelSum += delta;
    if (Math.abs(wheelSum) < 40) return;
    event.preventDefault();
    step(wheelSum > 0 ? 1 : -1);
    wheelSum = 0;
  }

  function onKey(event) {
    if (dom.result.hidden) return;
    // Never steal a key from somebody typing a date into the picker.
    var tag = (event.target && event.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;

    switch (event.key) {
      case 'ArrowRight': case 'ArrowDown': case 'PageDown': case ' ': step(1); break;
      case 'ArrowLeft': case 'ArrowUp': case 'PageUp': step(-1); break;
      case 'Home': goTo(0); break;
      case 'End': goTo(show.slides.length - 1); break;
      default: return;
    }
    event.preventDefault();
  }

  /** "This slide" or "All notes". The list is the one a presenter rehearses from. */
  function notesTab(all) {
    dom.notesOne.hidden = all;
    dom.notesAll.hidden = !all;
    dom.tabOne.setAttribute('aria-selected', all ? 'false' : 'true');
    dom.tabAll.setAttribute('aria-selected', all ? 'true' : 'false');
  }

  function mount(slides) {
    show.slides = slides || [];
    show.at = 0;
    show.rail = window.DeckPreview.rail(dom.rail, show.slides, goTo);
    show.notes = window.DeckPreview.notes(dom.notesAll, show.slides, function (i) {
      goTo(i);
      notesTab(false);
    });
    fitRail();
    goTo(0);
    // So the arrow keys work without the reader having to find something to click first.
    if (dom.stagewrap.focus) dom.stagewrap.focus({ preventScroll: true });
  }

  function send(message) {
    if (window.NexusDeckBridge && window.NexusDeckBridge.post) {
      window.NexusDeckBridge.post(JSON.stringify(message));
      return true;
    }
    return false;
  }

  function status(text, tone) {
    dom.status.textContent = text;
    if (tone) dom.status.setAttribute('data-tone', tone);
    else dom.status.removeAttribute('data-tone');
  }

  function state(button, value, text) {
    if (!button) return;
    var badge = button.querySelector('.cut__state');
    if (!badge) return;
    badge.setAttribute('data-state', value);
    badge.textContent = text;
  }

  function lock(on) {
    busy = on;
    dom.buttons.forEach(function (button) { if (button) button.disabled = on; });
  }

  function notice(head, body, tone) {
    if (!head && !body) {
      dom.notice.hidden = true;
      return;
    }
    dom.noticeHead.textContent = head || '';
    dom.noticeBody.textContent = body || '';
    dom.notice.setAttribute('data-tone', tone || 'warn');
    dom.notice.hidden = false;
  }

  function ask(audience) {
    if (busy) return;
    lock(true);
    var button = audience === 'stakeholder' ? dom.buttons[1] : dom.buttons[0];
    state(button, 'working', 'Working...');

    // The range is part of what is being asked for, so it travels with the request
    // rather than being read out of the panel again on the Kotlin side.
    var message = scope.payload();
    message.type = 'build';
    message.audience = audience;

    status(scope.isRecap()
      ? 'Building the ' + audience + ' update for ' + scope.describe() + '.'
      : 'Starting.');
    if (!send(message)) {
      lock(false);
      state(button, 'failed', 'No IDE');
      status('This page is not connected to the IDE, so nothing can be built from here.', 'error');
    }
  }

  /*
   * The two buttons promise particular slides, and a progress update does not contain
   * the same ones. Leaving the copy fixed would mean the picker described a deck the
   * user had already chosen not to build.
   */
  var BLURBS = {
    launch: {
      technical: 'Architecture, one traced path, the stack and what the analysis could not find.',
      stakeholder: 'The problem, what a person can now do, and what comes next. No jargon.'
    },
    recap: {
      technical: 'What landed in the period, where it landed, and what is still open.',
      stakeholder: 'What the period produced, in plain language. No jargon.'
    }
  };

  function describeButtons() {
    var set = BLURBS[scope.isRecap() ? 'recap' : 'launch'];
    dom.buttons.forEach(function (button) {
      if (!button) return;
      var blurb = button.querySelector('.cut__blurb');
      var audience = button.getAttribute('data-audience');
      if (blurb && set[audience]) blurb.textContent = set[audience];
    });
  }

  function wire() {
    dom.buttons.forEach(function (button) {
      if (!button) return;
      button.addEventListener('click', function () { ask(button.getAttribute('data-audience')); });
    });
    dom.back.addEventListener('click', function () {
      dom.result.hidden = true;
      dom.picker.hidden = false;
    });
    dom.open.addEventListener('click', function () { send({ type: 'open' }); });
    dom.reveal.addEventListener('click', function () { send({ type: 'reveal' }); });
    dom.noticeClose.addEventListener('click', function () { notice(null, null); });

    dom.prev.addEventListener('click', function () { step(-1); });
    dom.next.addEventListener('click', function () { step(1); });
    dom.stagewrap.addEventListener('wheel', onWheel, { passive: false });
    document.addEventListener('keydown', onKey);
    dom.tabOne.addEventListener('click', function () { notesTab(false); });
    dom.tabAll.addEventListener('click', function () { notesTab(true); });
    dom.notesToggle.addEventListener('click', function () {
      var on = dom.notes.hidden;
      dom.notes.hidden = !on;
      dom.notesToggle.setAttribute('aria-pressed', on ? 'true' : 'false');
      // The slide grows into the space the notes gave back, so it has to be remeasured.
      fitSlide();
    });

    // Every stage is a fixed coordinate space scaled to its frame, so a resized tool
    // window has to rescale them rather than reflow them.
    window.addEventListener('resize', function () { fitSlide(); fitRail(); });
    scope.watch(describeButtons);
  }

  /* ---------------------------------------------------------- from the IDE */

  window.NexusDeck = {
    /** Called once when the tab loads, with whatever the IDE knows already. */
    ready: function (info) {
      if (info && info.projectName) dom.project.textContent = info.projectName;
      status(info && info.hint ? info.hint : 'Ready.');
      // The areas are the project's own modules, so they are asked for, not guessed.
      send({ type: 'scopes' });
    },

    /** The area dropdown, filled from the modules the IDE found on disk. */
    scopes: function (payload) {
      scope.fillAreas(payload && payload.areas);
    },

    progress: function (message) {
      status(message);
    },

    /** One finished deck: its slides, where the file landed, and how it was built. */
    done: function (payload) {
      lock(false);
      var audience = payload.audience || 'technical';
      var button = audience === 'stakeholder' ? dom.buttons[1] : dom.buttons[0];
      state(button, 'ready', payload.slides + ' slides');

      dom.title.textContent = payload.title || 'Deck';
      dom.sub.textContent = [
        payload.period,
        payload.fileName,
        payload.slides + ' slides',
        payload.byAi ? 'written from the understood product' : 'built from harvested facts, no key found',
        payload.cacheHit ? 'reused this project’s analysis' : null
      ].filter(Boolean).join('  ·  ');

      dom.picker.hidden = true;
      dom.result.hidden = false;
      // Shown before drawn, not after. Every stage is a fixed 1280px space scaled to
      // whatever width its frame ended up with, and a hidden panel has no width, so
      // drawing first meant every scale was computed against zero and skipped.
      mount(payload.slides_art || []);

      if (payload.issues && payload.issues.length) {
        notice(
          'Some slides were changed to fit',
          payload.issues.slice(0, 3).join(' '),
          'ok'
        );
      } else if (!payload.byAi) {
        notice(
          'No API key was found',
          'This deck was built from the facts the harvester could read on its own. ' +
            'Add a key in Settings and the next one will be written from what the project actually does.',
          'warn'
        );
      } else {
        notice(null, null);
      }
    },

    failed: function (message) {
      lock(false);
      dom.buttons.forEach(function (button) {
        var badge = button && button.querySelector('.cut__state');
        if (badge && badge.getAttribute('data-state') === 'working') state(button, 'failed', 'Failed');
      });
      status(message || 'The deck could not be built.', 'error');
    },

    /** Stamps the picker buttons with the same icons the slides use. */
    icons: function (map) {
      [['cut-technical', 'toolbox'], ['cut-stakeholder', 'target']].forEach(function (pair) {
        var button = byId(pair[0]);
        if (!button || button.querySelector('.ic')) return;
        var chip = document.createElement('span');
        chip.className = 'ic';
        chip.style.setProperty('--ic-tint', (map && map[pair[1]]) || '#2F6BFF');
        var art = document.createElement('span');
        art.className = 'ic__art';
        art.style.backgroundImage = 'url("icons/' + pair[1] + '.png")';
        chip.appendChild(art);
        button.insertBefore(chip, button.firstChild);
      });
    }
  };

  cache();
  wire();
  window.NexusDeck.icons({ toolbox: '#F8312F', target: '#F8312F' });

  // A plain browser has no IDE behind it, and saying so beats a button that does nothing.
  if (!window.NexusDeckBridge) {
    status('Open this tab inside the IDE to build a deck.');
  }
})();
