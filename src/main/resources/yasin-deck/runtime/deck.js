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

  var dom = {};
  var thumbs = [];
  var busy = false;

  function byId(id) { return document.getElementById(id); }

  function cache() {
    dom.picker = byId('picker');
    dom.result = byId('result');
    dom.sheet = byId('sheet');
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
    status('Starting.');
    if (!send({ type: 'build', audience: audience })) {
      lock(false);
      state(button, 'failed', 'No IDE');
      status('This page is not connected to the IDE, so nothing can be built from here.', 'error');
    }
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
    // The thumbnails are a fixed coordinate space scaled to their column, so a resized
    // tool window has to rescale them rather than reflow them.
    window.addEventListener('resize', function () { window.DeckPreview.fit(thumbs); });
  }

  /* ---------------------------------------------------------- from the IDE */

  window.NexusDeck = {
    /** Called once when the tab loads, with whatever the IDE knows already. */
    ready: function (info) {
      if (info && info.projectName) dom.project.textContent = info.projectName;
      status(info && info.hint ? info.hint : 'Ready.');
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
      dom.sub.textContent = payload.fileName + '  ·  ' + payload.slides + ' slides  ·  ' +
        (payload.byAi ? 'written from the understood product' : 'built from harvested facts, no key found') +
        (payload.cacheHit ? '  ·  reused this project’s analysis' : '');

      // Shown before drawn, not after. A thumbnail is a fixed 1280px stage scaled to
      // whatever width its column ended up with, and a hidden panel has no width, so
      // rendering first meant every scale was computed against zero and skipped.
      dom.picker.hidden = true;
      dom.result.hidden = false;
      thumbs = window.DeckPreview.render(dom.sheet, payload.slides_art || []);

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
