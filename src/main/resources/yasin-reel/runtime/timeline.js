/*
 * Turns a Storyboard into exactly one paused, seekable GSAP timeline.
 *
 * One timeline rather than one per scene is the whole contract: a single playhead is
 * what makes the scrubber honest, and it is what the HyperFrames CLI drives when the
 * same page is rendered to MP4 headlessly. That is also why the finished timeline is
 * registered on window.__timelines under the audience, which is the composition id.
 *
 * Scenes are stacked on top of each other and cross faded, so the stage never reflows
 * mid film and every scene owns the full frame.
 */
window.ReelTimeline = (function () {
  'use strict';

  // The stage is a fixed coordinate space that player.js scales to fit. Absolute
  // pixel type is the only way a layout stays identical in a 380px tool window and
  // in a 1920px headless render.
  var SIZE = { w: 1200, h: 675 };
  var CROSS = 0.3;
  var DEFAULT_SCENE_MS = 6000;

  function labelFor(scene, index) {
    var slots = scene.slots || {};
    var named = {
      'title': slots.productName,
      'big-statement': slots.statement,
      'stat-grid': slots.heading || 'By the numbers',
      'capability-cards': slots.heading || 'What it does',
      'arch-layers': slots.heading || 'Architecture',
      // name then heading, matching the precedence the builders themselves use: the
      // director labels a journey with `heading`, so reading only `name` put the
      // generic word "The journey" in the transport under a frame that said otherwise.
      'flow-trace': slots.name || slots.heading || 'Traced path',
      'journey': slots.name || slots.heading || 'The journey',
      'outro': slots.cta || slots.headline || 'Wrap'
    };
    var label = named[scene.template] || scene.template || ('Scene ' + (index + 1));
    label = String(label).replace(/\s+/g, ' ').trim();
    return label.length > 46 ? label.slice(0, 44) + '…' : label;
  }

  /*
   * Harvested colours only ever become accents. A project palette can be all pale or
   * all dark, so letting it drive surfaces would regularly produce unreadable film,
   * while the reel.css tokens underneath are known to work.
   */
  function usableColor(value) {
    if (!value) return false;
    // A custom property accepts any garbage, and the damage only shows up in whatever
    // reads it, so anything the harvester got wrong is dropped here instead.
    try {
      return !!(window.CSS && window.CSS.supports && window.CSS.supports('color', String(value)));
    } catch (error) {
      return true;
    }
  }

  /*
   * Resolves any colour notation the browser understands, including oklch, down to
   * sRGB bytes. Painting one pixel is the only parser that is guaranteed to agree with
   * what the screen will actually show.
   */
  function pixelOf(value) {
    try {
      var canvas = document.createElement('canvas');
      canvas.width = 1;
      canvas.height = 1;
      var paint = canvas.getContext('2d');
      if (!paint) return null;
      paint.fillStyle = '#000';
      paint.fillRect(0, 0, 1, 1);
      paint.fillStyle = String(value);
      paint.fillRect(0, 0, 1, 1);
      var d = paint.getImageData(0, 0, 1, 1).data;
      return { r: d[0] / 255, g: d[1] / 255, b: d[2] / 255 };
    } catch (error) {
      return null;
    }
  }

  /*
   * A real project palette is often three shades of near-white, taken from a stylesheet
   * that assumes a light page. Used as accents on this dark stage those read as no
   * colour at all, and the whole film comes out grey. Anything that pale, that dark or
   * that unsaturated is dropped so the built-in accents survive instead.
   */
  function accentWorthy(value) {
    var rgb = pixelOf(value);
    if (!rgb) return true;
    var max = Math.max(rgb.r, rgb.g, rgb.b);
    var min = Math.min(rgb.r, rgb.g, rgb.b);
    var saturation = max <= 0 ? 0 : (max - min) / max;
    var luma = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    if (luma > 0.86 || luma < 0.12) return false;
    return saturation >= 0.14;
  }

  function applyTheme(root, theme) {
    var raw = (theme && Object.prototype.toString.call(theme.colors) === '[object Array]') ? theme.colors : [];
    var colors = [];
    for (var c = 0; c < raw.length; c++) {
      if (usableColor(raw[c]) && accentWorthy(raw[c])) colors.push(String(raw[c]));
    }
    var slots = ['--reel-accent', '--reel-accent-2', '--reel-accent-3', '--reel-accent-4'];
    for (var i = 0; i < slots.length; i++) {
      if (colors[i]) root.style.setProperty(slots[i], colors[i]);
    }
    // With a single harvested colour the secondary accent would fall back to the Nexus
    // teal and fight it, so it borrows the primary instead.
    if (colors.length === 1) root.style.setProperty('--reel-accent-2', colors[0]);
  }

  function durationOf(scene, storyboard, count) {
    var ms = Number(scene && scene.durationMs);
    if (isFinite(ms) && ms > 300) return ms / 1000;
    var total = Number(storyboard && storyboard.totalMs);
    if (isFinite(total) && total > 0 && count > 0) return (total / count) / 1000;
    return DEFAULT_SCENE_MS / 1000;
  }

  function key(file, line) {
    return String(file) + '#' + (line === undefined || line === null ? '' : line);
  }

  /*
   * A scene that does not naturally show its own paths still carries sourceRefs, so
   * they are pinned in the corner as provenance. Click to source has to work in every
   * scene of the technical cut, not only in flow-trace.
   *
   * The stakeholder cut is the exception: a path means nothing to that audience, so the
   * strip is not built at all. It is absolutely positioned, so an absent one also costs
   * the layout nothing.
   */
  function addProvenance(host, scene, tl, start, dur, ctx) {
    if (!ctx.sources) return;
    var refs = scene.sourceRefs;
    if (Object.prototype.toString.call(refs) !== '[object Array]' || !refs.length) return;

    var seen = {};
    var shown = host.querySelectorAll('[data-file]');
    for (var s = 0; s < shown.length; s++) {
      seen[key(shown[s].getAttribute('data-file'), shown[s].getAttribute('data-line'))] = true;
    }

    var strip = document.createElement('div');
    strip.className = 'scene__refs';
    var added = 0;
    for (var i = 0; i < refs.length && added < 3; i++) {
      var ref = refs[i] || {};
      if (!ref.file || seen[key(ref.file, ref.line)]) continue;
      var chip = window.ReelScenes.sourceChip(ref.file, ref.line);
      if (!chip) continue;
      seen[key(ref.file, ref.line)] = true;
      strip.appendChild(chip);
      added++;
    }
    if (!added) return;

    // In a scene that has a header bar, the strip belongs at the end of that bar: pinned
    // over it instead, it lands on top of the rule.
    var head = host.querySelector('.scene__head');
    (head || host).appendChild(strip);
    window.ReelScenes.rise(tl, strip, start + Math.min(0.5, dur * 0.2), { y: -8, duration: 0.35 });
  }

  /*
   * The caption sits over the bottom of the frame, so the scene above has to be told to
   * keep out of it. A scene with no narration keeps that band for its own content
   * instead of leaving a strip of empty stage.
   */
  function addCaption(host, scene, tl, start) {
    if (!scene.narration) return;
    var caption = document.createElement('p');
    caption.className = 'scene__caption';
    caption.textContent = window.ReelScenes.clean(scene.narration);
    host.classList.add('scene--cc');
    host.appendChild(caption);
    window.ReelScenes.rise(tl, caption, start + 0.28, { y: 12, duration: 0.38 });
  }

  /**
   * @param storyboard the Stage 3 output, exactly as Kotlin serialises it
   * @param mount optional element to attach the stage to before tweens are created,
   *        which the percentage geometry in flow-trace needs in order to resolve
   */
  function build(storyboard, mount) {
    if (typeof window.gsap === 'undefined') throw new Error('GSAP is not loaded');
    if (!storyboard || Object.prototype.toString.call(storyboard.scenes) !== '[object Array]') {
      throw new Error('Storyboard has no scenes');
    }

    var audience = storyboard.audience || 'reel';
    var root = document.createElement('div');
    root.className = 'reel-stage';
    root.setAttribute('data-composition-id', audience);
    root.style.width = SIZE.w + 'px';
    root.style.height = SIZE.h + 'px';
    applyTheme(root, storyboard.theme);
    if (mount) mount.appendChild(root);

    // The one audience-wide switch in the runtime: what a stakeholder may be shown.
    var ctx = { sources: audience !== 'stakeholder' };

    var tl = gsap.timeline({ paused: true });
    var chapters = [];
    var scenes = storyboard.scenes;
    var at = 0;

    for (var i = 0; i < scenes.length; i++) {
      var scene = scenes[i] || {};
      var dur = durationOf(scene, storyboard, scenes.length);
      var builder = window.ReelScenes[scene.template];
      if (!builder) {
        // An unknown template must never blank the film, so it degrades to the one
        // scene that can carry any text at all.
        builder = window.ReelScenes['big-statement'];
        scene = {
          template: scene.template,
          slots: { statement: scene.narration || 'Scene unavailable', context: scene.template || '' },
          narration: null,
          sourceRefs: scene.sourceRefs
        };
      }

      var built = builder(scene.slots || {}, storyboard.theme || {}, ctx);
      root.appendChild(built.el);

      addCaption(built.el, scene, tl, at);
      built.animate(tl, at, dur);
      addProvenance(built.el, scene, tl, at, dur, ctx);

      var cross = Math.min(CROSS, dur / 3);
      gsap.set(built.el, { autoAlpha: 0 });
      tl.fromTo(built.el, { autoAlpha: 0 }, {
        autoAlpha: 1, duration: cross, ease: 'power1.inOut', immediateRender: false
      }, Math.max(0, at - cross));
      tl.fromTo(built.el, { autoAlpha: 1 }, {
        autoAlpha: 0, duration: cross, ease: 'power1.inOut', immediateRender: false
      }, at + dur - cross);

      chapters.push({
        index: i,
        template: scenes[i].template,
        label: labelFor(scenes[i], i),
        start: at,
        duration: dur,
        narration: scenes[i].narration || null
      });

      at += dur;
    }

    // Pins the timeline to the storyboard's own length, so the last fade to black is
    // part of the film rather than something the scrubber runs past.
    tl.to({ tail: 0 }, { tail: 1, duration: 0.001 }, Math.max(at - 0.001, 0));
    tl.seek(0);

    window.__timelines = window.__timelines || {};
    window.__timelines[audience] = tl;

    return { tl: tl, root: root, chapters: chapters, total: at, audience: audience };
  }

  return { build: build, SIZE: SIZE };
})();
