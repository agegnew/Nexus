/*
 * The transport: everything around the film that is not the film.
 *
 * Nothing here ever advances state on its own. The timeline is the only clock, and
 * this module reads it (through the timeline's own onUpdate, which fires on play and
 * on seek alike) and writes the scrubber, the clock and the chapter label. Keeping the
 * chrome strictly downstream of the playhead is what lets a headless renderer ignore
 * this file completely and still get an identical frame at any t.
 */
window.NexusReel = (function () {
  'use strict';

  var SIZE = window.ReelTimeline.SIZE;

  var dom = {};
  var comp = null;
  var sceneEls = [];
  var activeIndex = -1;
  var mode = 'paused';
  var resumeAfterScrub = false;
  var observer = null;
  var exporting = false;

  function byId(id) { return document.getElementById(id); }

  function cacheDom() {
    if (dom.frame) return;
    dom.reel = byId('reel');
    dom.picker = byId('picker');
    dom.stage = byId('stage');
    dom.frame = byId('stage-frame');
    dom.transport = byId('transport');
    dom.play = byId('tp-play');
    dom.range = byId('tp-range');
    dom.fill = byId('tp-fill');
    dom.ticks = byId('tp-ticks');
    dom.time = byId('tp-time');
    dom.chapter = byId('tp-chapter');
    dom.note = byId('tp-note');
    dom.line = byId('tp-line');
    dom.cc = byId('tp-cc');
    dom.mute = byId('tp-mute');
    dom.back = byId('tp-back');
    dom.export = byId('tp-export');
    dom.exportMenu = byId('tp-export-menu');
    dom.exportMp4 = dom.exportMenu ? dom.exportMenu.querySelector('[data-format="mp4"]') : null;
    dom.exportMp4Why = byId('tp-export-mp4-why');
    wire();
  }

  function clock(seconds) {
    var whole = Math.max(0, Math.floor(seconds));
    var mins = Math.floor(whole / 60);
    var secs = whole % 60;
    return mins + ':' + (secs < 10 ? '0' : '') + secs;
  }

  function setMode(next) {
    mode = next;
    dom.play.setAttribute('data-mode', next === 'playing' ? 'pause' : 'play');
    dom.play.setAttribute('aria-label', next === 'playing' ? 'Pause' : (next === 'ended' ? 'Replay' : 'Play'));
  }

  function note(message) {
    dom.note.textContent = message || '';
  }

  /*
   * A faded out scene keeps opacity 0 but still occupies the frame, and an element at
   * zero opacity is still a click target. Gating pointer events on the playhead means a
   * path can only ever be clicked while its own scene is the one on screen.
   */
  function markActive(time) {
    if (!comp) return;
    var chapters = comp.chapters;
    var index = chapters.length - 1;
    for (var i = 0; i < chapters.length; i++) {
      if (time < chapters[i].start + chapters[i].duration) { index = i; break; }
    }
    if (index === activeIndex) return;
    activeIndex = index;
    for (var s = 0; s < sceneEls.length; s++) {
      sceneEls[s].style.pointerEvents = s === index ? 'auto' : 'none';
    }
    var marks = dom.ticks.children;
    for (var m = 0; m < marks.length; m++) {
      marks[m].setAttribute('data-active', String(Number(marks[m].getAttribute('data-index')) === index));
    }
    dom.chapter.textContent = chapters[index] ? chapters[index].label : '';
    dom.line.textContent = (chapters[index] && chapters[index].narration) || '';
    note('');
  }

  function tick() {
    if (!comp) return;
    var progress = comp.tl.progress();
    var time = comp.tl.time();
    dom.range.value = String(Math.round(progress * 1000));
    dom.fill.style.width = (progress * 100) + '%';
    dom.time.textContent = clock(time) + ' / ' + clock(comp.total);
    markActive(time);
  }

  /** The stage is a fixed 1200x675 coordinate space scaled to whatever the panel gives us. */
  function fit() {
    if (!comp) return;
    var width = dom.frame.clientWidth;
    var height = dom.frame.clientHeight;
    if (!width || !height) return;
    var scale = Math.min(width / SIZE.w, height / SIZE.h);
    comp.root.style.transform = 'scale(' + scale + ')';
    comp.root.style.left = ((width - SIZE.w * scale) / 2) + 'px';
    comp.root.style.top = ((height - SIZE.h * scale) / 2) + 'px';
  }

  function buildTicks(chapters, total) {
    dom.ticks.textContent = '';
    if (!total) return;
    for (var i = 0; i < chapters.length; i++) {
      var mark = document.createElement('span');
      mark.className = 'tp__tick';
      mark.setAttribute('data-index', String(i));
      mark.style.left = ((chapters[i].start / total) * 100) + '%';
      mark.title = chapters[i].label;
      dom.ticks.appendChild(mark);
    }
  }

  function toggle() {
    if (!comp) return;
    if (mode === 'playing') {
      comp.tl.pause();
      setMode('paused');
      return;
    }
    if (comp.tl.progress() >= 1) comp.tl.restart();
    else comp.tl.play();
    setMode('playing');
  }

  /*
   * gsap.seek() suppresses callbacks unless told otherwise, and the transport reads
   * the playhead through the timeline's own onUpdate, so every seek here passes false.
   */
  function seekTo(seconds) {
    if (!comp) return;
    comp.tl.seek(Math.max(0, Math.min(comp.total, seconds)), false);
    if (mode === 'ended' && comp.tl.progress() < 1) setMode('paused');
  }

  function seekBy(delta) {
    if (!comp) return;
    seekTo(comp.tl.time() + delta);
  }

  /*
   * Click to source. The timeline pauses first: the whole point is that the viewer can
   * stop on a frame, land in the editor and read the code the film is talking about.
   */
  function openSource(file, line) {
    if (!file) return;
    if (comp && mode === 'playing') {
      comp.tl.pause();
      setMode('paused');
    }
    var payload = { type: 'openFile', file: String(file) };
    if (line !== undefined && line !== null && line !== '') payload.line = Number(line);
    if (typeof window.__reelCallback === 'function') {
      window.__reelCallback(JSON.stringify(payload));
      note('Opened ' + file + (payload.line ? ':' + payload.line : ''));
    } else {
      note('No IDE attached, so ' + file + ' cannot be opened here.');
    }
  }

  function toIde(payload) {
    if (typeof window.__reelCallback !== 'function') return false;
    window.__reelCallback(JSON.stringify(payload));
    return true;
  }

  function closeExportMenu() {
    if (!dom.exportMenu) return;
    dom.exportMenu.hidden = true;
    dom.export.setAttribute('aria-expanded', 'false');
  }

  /*
   * The answer is asked for every time the menu opens, not once at load: the first
   * reply from the IDE is deliberately unsettled ("checking this machine") while it
   * looks for Node in the background, so one question would leave MP4 disabled forever.
   */
  function openExportMenu() {
    if (!dom.exportMenu) return;
    dom.exportMenu.hidden = false;
    dom.export.setAttribute('aria-expanded', 'true');
    toIde({ type: 'toolchain' });
  }

  function setMp4(enabled, why) {
    if (!dom.exportMp4) return;
    dom.exportMp4.disabled = !enabled;
    if (dom.exportMp4Why) dom.exportMp4Why.textContent = why || (enabled ? 'Rendered on this machine' : 'Not available here');
  }

  function requestExport(format) {
    if (!comp || exporting) return;
    if (!toIde({ type: 'export', audience: comp.audience, format: format })) {
      note('No IDE attached, so this cut cannot be exported from here.');
      return;
    }
    exporting = true;
    dom.export.disabled = true;
    closeExportMenu();
    note(format === 'mp4' ? 'Rendering a video...' : 'Writing a standalone page...');
  }

  function exportFinished(message, path) {
    exporting = false;
    dom.export.disabled = false;
    note(message || '');
    // The path alone is not an answer: it is a long string in a small strip that the
    // reader has to select and paste somewhere. One click that opens the folder is.
    var stale = dom.note.parentNode && dom.note.parentNode.querySelector('.tp__reveal');
    if (stale) stale.parentNode.removeChild(stale);
    if (path && dom.note.parentNode) {
      var reveal = document.createElement('button');
      reveal.type = 'button';
      reveal.className = 'tp__reveal';
      reveal.textContent = 'Show in Finder';
      reveal.title = path;
      reveal.addEventListener('click', function () {
        if (!toIde({ type: 'reveal', path: path })) note('No IDE attached, so the folder cannot be opened from here.');
      });
      dom.note.parentNode.insertBefore(reveal, dom.note.nextSibling);
    }
  }

  /** Hidden rather than shown-but-dead when a cut has no narration at all. */
  function syncMute() {
    if (!dom.mute) return;
    var audio = window.ReelAudio;
    var available = !!(audio && audio.isAvailable());
    dom.mute.hidden = !available;
    if (!available) return;
    var muted = audio.isMuted();
    dom.mute.setAttribute('aria-pressed', String(!muted));
    dom.mute.textContent = muted ? 'Muted' : 'Sound';
  }

  function wire() {
    dom.play.addEventListener('click', toggle);
    dom.back.addEventListener('click', function () { stop(); });

    dom.cc.addEventListener('click', function () {
      var off = comp && comp.root.classList.toggle('reel-stage--nocc');
      dom.cc.setAttribute('aria-pressed', String(!off));
    });

    if (dom.mute) {
      dom.mute.addEventListener('click', function () {
        if (!window.ReelAudio) return;
        window.ReelAudio.setMuted(!window.ReelAudio.isMuted());
        syncMute();
      });
    }

    if (dom.export) {
      dom.export.addEventListener('click', function (event) {
        event.stopPropagation();
        if (dom.exportMenu.hidden) openExportMenu();
        else closeExportMenu();
      });
      dom.exportMenu.addEventListener('click', function (event) {
        var item = event.target && event.target.closest ? event.target.closest('[data-format]') : null;
        if (!item || item.disabled) return;
        requestExport(item.getAttribute('data-format'));
      });
      // A menu that only closes on its own button is a menu people leave open over
      // the film, so any click elsewhere dismisses it.
      document.addEventListener('click', function (event) {
        if (dom.exportMenu.hidden) return;
        if (dom.export.contains(event.target) || dom.exportMenu.contains(event.target)) return;
        closeExportMenu();
      });
    }

    window.addEventListener('yasin-reel:toolchain', function (event) {
      var d = event.detail;
      if (typeof d === 'string') { try { d = JSON.parse(d); } catch (e) { return; } }
      if (!d) return;
      setMp4(!!d.canMp4, d.detail);
    });

    window.addEventListener('yasin-reel:export-progress', function (event) {
      var d = event.detail || {};
      note(d.message || 'Exporting...');
    });
    window.addEventListener('yasin-reel:export-done', function (event) {
      var d = event.detail || {};
      exportFinished(d.message || 'Exported.', d.path);
    });
    window.addEventListener('yasin-reel:export-error', function (event) {
      var d = event.detail || {};
      exportFinished(d.message || 'That export did not finish.');
    });

    dom.range.addEventListener('pointerdown', function () {
      resumeAfterScrub = mode === 'playing';
      if (comp) comp.tl.pause();
      setMode('paused');
    });
    dom.range.addEventListener('input', function () {
      if (!comp) return;
      comp.tl.pause();
      comp.tl.progress(Number(dom.range.value) / 1000);
    });
    var release = function () {
      if (resumeAfterScrub && comp && comp.tl.progress() < 1) {
        comp.tl.play();
        setMode('playing');
      }
      resumeAfterScrub = false;
    };
    dom.range.addEventListener('pointerup', release);
    dom.range.addEventListener('pointercancel', release);

    dom.frame.addEventListener('click', function (event) {
      var target = event.target;
      var node = target && target.closest ? target.closest('[data-file]') : null;
      if (!node) return;
      event.preventDefault();
      openSource(node.getAttribute('data-file'), node.getAttribute('data-line'));
    });

    document.addEventListener('keydown', function (event) {
      if (!comp || dom.stage.hidden) return;
      var tag = event.target && event.target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'BUTTON') return;
      if (event.key === ' ' || event.key === 'k') { event.preventDefault(); toggle(); }
      else if (event.key === 'ArrowRight') { event.preventDefault(); seekBy(5); }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); seekBy(-5); }
      else if (event.key === 'Home') { event.preventDefault(); seekTo(0); }
      else if (event.key === 'End') { event.preventDefault(); seekTo(comp.total); }
      else if (event.key === 'Escape') {
        event.preventDefault();
        if (dom.exportMenu && !dom.exportMenu.hidden) closeExportMenu();
        else stop();
      }
    });

    window.addEventListener('resize', fit);
  }

  function play(storyboard) {
    cacheDom();
    stop();

    dom.picker.hidden = true;
    dom.stage.hidden = false;
    dom.transport.hidden = false;
    dom.reel.classList.add('reel--playing');

    // The stage has to be laid out before the timeline is built: flow-trace places its
    // packet in percentages, which only resolve against a rail that has a width.
    dom.frame.textContent = '';
    comp = window.ReelTimeline.build(storyboard, dom.frame);
    sceneEls = comp.root.querySelectorAll('.scene');
    activeIndex = -1;

    buildTicks(comp.chapters, comp.total);
    fit();
    if (window.ResizeObserver && !observer) {
      observer = new ResizeObserver(fit);
    }
    if (observer) observer.observe(dom.frame);

    comp.tl.eventCallback('onUpdate', tick);
    comp.tl.eventCallback('onComplete', function () {
      setMode('ended');
      tick();
    });

    tick();
    comp.tl.play();
    setMode('playing');
    // After tl.play() and inside the same click driven call stack, which is what buys
    // the browser's autoplay permission for the narration.
    if (window.ReelAudio) window.ReelAudio.attach(storyboard, comp);
    syncMute();
    return comp;
  }

  function stop() {
    cacheDom();
    closeExportMenu();
    if (comp) {
      // First, so a torn down cut stops its own narration and its buffering requests
      // rather than talking over the picker.
      if (window.ReelAudio) window.ReelAudio.detach();
      comp.tl.pause();
      comp.tl.eventCallback('onUpdate', null);
      comp.tl.eventCallback('onComplete', null);
      if (window.__timelines && window.__timelines[comp.audience] === comp.tl) {
        delete window.__timelines[comp.audience];
      }
      comp.tl.kill();
      if (observer) observer.unobserve(dom.frame);
      comp = null;
    }
    sceneEls = [];
    activeIndex = -1;
    dom.frame.textContent = '';
    dom.chapter.textContent = '';
    dom.line.textContent = '';
    dom.stage.hidden = true;
    dom.transport.hidden = true;
    dom.picker.hidden = false;
    dom.reel.classList.remove('reel--playing');
    if (dom.mute) dom.mute.hidden = true;
    exporting = false;
    if (dom.export) dom.export.disabled = false;
    setMode('paused');
    note('');
  }

  return {
    play: play,
    stop: stop,
    openSource: openSource,
    isPlaying: function () { return mode === 'playing'; },
    composition: function () { return comp; }
  };
})();
