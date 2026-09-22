/*
 * Narration and music, hung off the one paused GSAP timeline.
 *
 * The timeline is the only clock in this player, and audio is no exception: nothing
 * here decides when a clip should be heard, it only asks the playhead where it is and
 * makes the sound agree. That is what keeps a scrub honest. Drag the scrubber into the
 * middle of scene four and you hear scene four from the middle of its line, not scene
 * one racing to catch up.
 *
 * Every element is a real <audio> tag carrying id, data-start, data-duration,
 * data-track-index and data-volume, so the same page a viewer scrubs in the tool window
 * is the page a headless renderer can mix into an MP4 without this file running at all.
 *
 * Kotlin hands the clips over on the storyboard as:
 *   storyboard.audio = [{ sceneIndex: 0, url: "tts/<sha256>.mp3", durationMs: 4120 }, ...]
 * Scenes with no entry are silent, and a storyboard with no audio at all plays exactly
 * as it did before this file existed.
 */
window.ReelAudio = (function () {
  'use strict';

  var MUSIC_SRC = 'assets/music/bed.mp3';
  var MUSIC_VOLUME = 0.30;
  var MUSIC_DUCKED = 0.12;

  // Well above the visual tracks, and music is on its own so it never shares a lane
  // with an overlapping narration clip.
  var NARRATION_TRACK = 10;
  var MUSIC_TRACK = 11;

  var HOST_ID = 'reel-audio';
  var UNLOCK_ID = 'reel-audio-unlock';

  // Below this the ear cannot tell, above it a scrub is audibly wrong.
  var DRIFT_S = 0.25;
  // Per frame approach rate for the duck, roughly a quarter second to settle.
  var DUCK_RATE = 0.12;
  var DEFAULT_SCENE_S = 6;

  var state = null;
  // Kept outside `state` on purpose: a viewer who muted the technical cut means it,
  // and should not have to mute the stakeholder cut again.
  var muted = false;
  var blocked = false;

  function host() {
    var el = document.getElementById(HOST_ID);
    if (!el) {
      el = document.createElement('div');
      el.id = HOST_ID;
      // Present in the DOM (a renderer finds media by a flat query) but never seen.
      el.setAttribute('aria-hidden', 'true');
      el.style.position = 'absolute';
      el.style.width = '0';
      el.style.height = '0';
      el.style.overflow = 'hidden';
      document.body.appendChild(el);
    }
    return el;
  }

  function slug(value) {
    return String(value || 'reel').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'reel';
  }

  function sceneSeconds(scene, storyboard, count) {
    var ms = Number(scene && scene.durationMs);
    if (isFinite(ms) && ms > 300) return ms / 1000;
    var total = Number(storyboard && storyboard.totalMs);
    if (isFinite(total) && total > 0 && count > 0) return (total / count) / 1000;
    return DEFAULT_SCENE_S;
  }

  /*
   * Scene start times must be the same numbers the timeline used, or every clip lands
   * a little late. The composition knows them for certain, so prefer its chapters and
   * only recompute from the storyboard when playing without one.
   */
  function startsFrom(storyboard, comp) {
    var scenes = storyboard.scenes || [];
    var bounds = [];
    if (comp && Object.prototype.toString.call(comp.chapters) === '[object Array]' &&
        comp.chapters.length === scenes.length) {
      for (var c = 0; c < comp.chapters.length; c++) {
        bounds.push({ start: comp.chapters[c].start, end: comp.chapters[c].start + comp.chapters[c].duration });
      }
      return bounds;
    }
    var at = 0;
    for (var i = 0; i < scenes.length; i++) {
      var dur = sceneSeconds(scenes[i], storyboard, scenes.length);
      bounds.push({ start: at, end: at + dur });
      at += dur;
    }
    return bounds;
  }

  /** Accepts the storyboard-level `audio` array, or per scene `audio` / `audioUrl`. */
  function entriesFrom(storyboard) {
    var out = {};
    var list = storyboard.audio;
    if (Object.prototype.toString.call(list) === '[object Array]') {
      for (var i = 0; i < list.length; i++) {
        var entry = list[i] || {};
        var index = Number(entry.sceneIndex);
        var url = entry.url || entry.relativeUrl || entry.src;
        if (!isFinite(index) || !url) continue;
        out[index] = { url: String(url), durationMs: Number(entry.durationMs) || null };
      }
    }
    var scenes = storyboard.scenes || [];
    for (var s = 0; s < scenes.length; s++) {
      if (out[s]) continue;
      var scene = scenes[s] || {};
      var inline = scene.audio || (scene.audioUrl ? { url: scene.audioUrl } : null);
      if (inline && (inline.url || inline.relativeUrl)) {
        out[s] = {
          url: String(inline.url || inline.relativeUrl),
          durationMs: Number(inline.durationMs) || null
        };
      }
    }
    return out;
  }

  function makeElement(id, src, start, duration, track, volume) {
    var el = document.createElement('audio');
    // An <audio> with no id is invisible to the render mixer, so the MP4 comes out
    // silent even though the page sounds correct. Always set one.
    el.id = id;
    el.src = src;
    el.preload = 'auto';
    el.setAttribute('data-start', String(round(start)));
    if (duration) el.setAttribute('data-duration', String(round(duration)));
    el.setAttribute('data-track-index', String(track));
    el.setAttribute('data-volume', String(volume));
    el.volume = volume;
    el.muted = muted;
    return el;
  }

  function round(seconds) {
    return Math.round(seconds * 1000) / 1000;
  }

  function clipLength(clip) {
    var media = clip.el.duration;
    if (isFinite(media) && media > 0) return media;
    if (clip.durationMs) return clip.durationMs / 1000;
    // The scene's own span, never the extended end: a clip whose length is not known yet
    // must not be assumed to speak across the scenes its line was allowed to run into.
    return clip.span;
  }

  function clipAt(s, time) {
    for (var i = 0; i < s.clips.length; i++) {
      var clip = s.clips[i];
      if (time >= clip.start && time < clip.end) return clip;
    }
    return null;
  }

  /** True while the playhead is still inside the recorded line, not just inside the scene. */
  function speaking(clip, time) {
    if (!clip) return false;
    var offset = time - clip.start;
    return offset >= 0 && offset < clipLength(clip) - 0.05;
  }

  function safePlay(el) {
    var promise;
    try {
      promise = el.play();
    } catch (error) {
      return;
    }
    if (!promise || typeof promise.catch !== 'function') return;
    promise.catch(function (error) {
      // Only a policy refusal means the browser is waiting for a gesture. A pause that
      // interrupts a play (AbortError) is our own doing, and a missing file
      // (NotSupportedError) is a silent scene, not something a viewer can fix by
      // clicking, so neither earns the prompt.
      if (error && error.name === 'NotAllowedError') {
        blocked = true;
        showUnlock();
      }
    });
  }

  function stopClip(clip) {
    if (!clip) return;
    try {
      clip.el.pause();
      clip.el.currentTime = 0;
    } catch (error) {
      // A clip that never loaded cannot be rewound, and does not need to be.
    }
  }

  function syncClip(clip, time, playing) {
    var offset = time - clip.start;
    if (!speaking(clip, time)) {
      if (!clip.el.paused) clip.el.pause();
      return;
    }
    // readyState 1 is the first point at which currentTime is honoured rather than
    // quietly dropped, which would otherwise leave a clip permanently behind.
    if (clip.el.readyState >= 1 && Math.abs(clip.el.currentTime - offset) > DRIFT_S) {
      try { clip.el.currentTime = offset; } catch (error) { /* seek before metadata */ }
    }
    if (playing && clip.el.paused && !blocked) safePlay(clip.el);
    if (!playing && !clip.el.paused) clip.el.pause();
  }

  function pauseAll(s) {
    for (var i = 0; i < s.clips.length; i++) {
      if (!s.clips[i].el.paused) s.clips[i].el.pause();
    }
    if (s.music && !s.music.paused) s.music.pause();
  }

  /*
   * The bed sits under the whole reel and gets out of the way of a voice. Ramped
   * rather than switched, because a hard volume step is audible as a click and reads
   * as a bug on a demo screen.
   */
  function driveMusic(s, time, playing, ducking) {
    var music = s.music;
    if (!music) return;
    var target = ducking ? MUSIC_DUCKED : MUSIC_VOLUME;
    s.musicVolume += (target - s.musicVolume) * DUCK_RATE;
    music.volume = Math.max(0, Math.min(1, s.musicVolume));

    if (!playing) {
      if (!music.paused) music.pause();
      return;
    }
    var length = music.duration;
    if (isFinite(length) && length > 0) {
      var wanted = time % length;
      if (music.readyState >= 1 && Math.abs(music.currentTime - wanted) > DRIFT_S * 4) {
        try { music.currentTime = wanted; } catch (error) { /* seek before metadata */ }
      }
    }
    if (music.paused && !blocked) safePlay(music);
  }

  function frame() {
    var s = state;
    if (!s || !s.tl) return;
    var time = s.tl.time();
    var playing = !s.tl.paused() && s.tl.progress() < 1;
    var clip = clipAt(s, time);

    if (clip !== s.current) {
      stopClip(s.current);
      s.current = clip;
    }
    if (clip) syncClip(clip, time, playing);
    if (!playing) pauseAll(s);

    driveMusic(s, time, playing, speaking(clip, time));
  }

  function addTicker() {
    if (window.gsap && gsap.ticker) {
      gsap.ticker.add(frame);
      return function () { gsap.ticker.remove(frame); };
    }
    var live = true;
    var loop = function () {
      if (!live) return;
      frame();
      window.requestAnimationFrame(loop);
    };
    window.requestAnimationFrame(loop);
    return function () { live = false; };
  }

  /*
   * Autoplay policy blocks sound until the page has been touched. The reel is always
   * started by a click and attach() runs inside that click, so the first play() call
   * has to happen here: it is what buys permission for every later clip.
   *
   * When the playhead is not inside the first line there is nothing to legitimately
   * play yet, so the permission is taken with a muted clip that is stopped again as
   * soon as it has served its purpose.
   */
  function prime(s) {
    var el = s.clips.length ? s.clips[0].el : null;
    if (!el || !el.paused) return;
    var wasMuted = el.muted;
    el.muted = true;

    var promise;
    try {
      promise = el.play();
    } catch (error) {
      el.muted = wasMuted;
      return;
    }

    var settle = function () {
      // By the time the promise resolves the timeline may have started and this very
      // clip may be the line that should now be heard, so check before stopping it.
      var wanted = state === s && s.current && s.current.el === el && !s.tl.paused();
      if (!wanted) {
        try {
          el.pause();
          el.currentTime = 0;
        } catch (error) {
          // Nothing to rewind.
        }
      }
      el.muted = wasMuted;
    };

    if (!promise || typeof promise.then !== 'function') {
      settle();
      return;
    }
    promise.then(settle, function (error) {
      settle();
      if (error && error.name === 'NotAllowedError') {
        blocked = true;
        showUnlock();
      }
    });
  }

  function showUnlock() {
    if (document.getElementById(UNLOCK_ID)) return;
    var button = document.createElement('button');
    button.id = UNLOCK_ID;
    button.type = 'button';
    button.textContent = 'Click to enable sound';
    // Styled inline so this module stays self contained and cannot be broken by a
    // stylesheet that does not know about it.
    button.style.cssText = [
      'position:fixed', 'right:16px', 'bottom:16px', 'z-index:9999',
      'padding:6px 12px', 'border-radius:999px', 'border:1px solid rgba(255,255,255,.28)',
      'background:rgba(16,18,24,.82)', 'color:#fff', 'font:500 12px/1.4 system-ui,sans-serif',
      'cursor:pointer', 'backdrop-filter:blur(6px)'
    ].join(';');
    button.addEventListener('click', function () {
      blocked = false;
      hideUnlock();
      // The click itself is the gesture, so start from inside it rather than waiting
      // for the next animation frame.
      if (state) frame();
    });
    document.body.appendChild(button);
  }

  function hideUnlock() {
    var button = document.getElementById(UNLOCK_ID);
    if (button && button.parentNode) button.parentNode.removeChild(button);
  }

  /*
   * The bed is optional and not shipped. Probing with HEAD rather than letting an
   * <audio> 404 keeps the console clean and means an absent file is a non event.
   */
  function probeMusic(s) {
    if (typeof window.fetch !== 'function') return;
    var pending;
    try {
      pending = window.fetch(MUSIC_SRC, { method: 'HEAD' });
    } catch (error) {
      return;
    }
    pending.then(function (response) {
      if (!response || !response.ok || state !== s) return;
      var music = makeElement('reel-audio-bed', MUSIC_SRC, 0, 0, MUSIC_TRACK, MUSIC_VOLUME);
      music.loop = true;
      s.host.appendChild(music);
      s.music = music;
      s.musicVolume = MUSIC_VOLUME;
      // Attach happened during the click, so this one has missed the gesture. It is
      // allowed to fail quietly; the unlock affordance covers it.
      if (!s.tl.paused()) safePlay(music);
    }).catch(function () {
      // No bed, no problem.
    });
  }

  /**
   * @param storyboard the storyboard as Kotlin serialised it, optionally carrying `audio`
   * @param timeline the composition returned by ReelTimeline.build, or a bare GSAP timeline
   */
  function attach(storyboard, timeline) {
    detach();
    if (!storyboard || !timeline) return false;

    var tl = timeline.tl || timeline;
    if (!tl || typeof tl.time !== 'function') return false;

    var entries = entriesFrom(storyboard);
    var bounds = startsFrom(storyboard, timeline.tl ? timeline : null);
    var container = host();
    container.textContent = '';

    var audience = slug(storyboard.audience);
    var clips = [];
    for (var i = 0; i < bounds.length; i++) {
      var entry = entries[i];
      if (!entry) continue;
      var length = entry.durationMs ? entry.durationMs / 1000 : (bounds[i].end - bounds[i].start);
      var el = makeElement(
        'reel-audio-' + audience + '-' + i,
        entry.url,
        bounds[i].start,
        length,
        NARRATION_TRACK,
        1
      );
      container.appendChild(el);
      clips.push({
        index: i,
        el: el,
        start: bounds[i].start,
        end: bounds[i].end,
        span: bounds[i].end - bounds[i].start,
        durationMs: entry.durationMs
      });
    }

    /*
     * A line owns the stage until the next line starts, not until its own scene ends.
     *
     * The voiceover is one continuous script, so a recording that runs a moment past its
     * picture is a sentence still being spoken, and cutting it at the scene change cuts it
     * mid word. Where the next scene speaks, nothing changes: the two ends are the same
     * instant. speaking() still stops the clip when the recording itself runs out, so a
     * short line over a long silent scene is unaffected.
     */
    var filmEnd = bounds.length ? bounds[bounds.length - 1].end : 0;
    for (var c = 0; c < clips.length; c++) {
      clips[c].end = (c + 1 < clips.length) ? clips[c + 1].start : filmEnd;
    }

    state = {
      tl: tl,
      host: container,
      clips: clips,
      current: null,
      music: null,
      musicVolume: MUSIC_VOLUME,
      stopTicker: null
    };

    probeMusic(state);
    if (!clips.length) {
      // No narration, but a bed may still arrive, so the ticker is still worth running.
      state.stopTicker = addTicker();
      return false;
    }

    state.stopTicker = addTicker();
    // In this order: frame() starts the right clip while the click is still the
    // current gesture, and prime only steps in when that left everything silent.
    frame();
    prime(state);
    return true;
  }

  function detach() {
    hideUnlock();
    if (!state) return;
    if (state.stopTicker) state.stopTicker();
    pauseAll(state);
    for (var i = 0; i < state.clips.length; i++) {
      // Dropping the source stops a buffering request that nobody is going to hear.
      state.clips[i].el.removeAttribute('src');
    }
    if (state.music) state.music.removeAttribute('src');
    if (state.host) state.host.textContent = '';
    state = null;
  }

  function setMuted(next) {
    muted = !!next;
    if (!state) return muted;
    for (var i = 0; i < state.clips.length; i++) state.clips[i].el.muted = muted;
    if (state.music) state.music.muted = muted;
    return muted;
  }

  return {
    attach: attach,
    detach: detach,
    setMuted: setMuted,
    isMuted: function () { return muted; },
    isAvailable: function () { return !!(state && state.clips.length); },
    hasMusic: function () { return !!(state && state.music); }
  };
})();
