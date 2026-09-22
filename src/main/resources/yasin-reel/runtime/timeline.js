/*
 * Turns a Storyboard into exactly one paused, seekable GSAP timeline.
 *
 * One timeline rather than one per scene is the whole contract: a single playhead is
 * what makes the scrubber honest, and it is what the HyperFrames CLI drives when the
 * same page is rendered to MP4 headlessly. That is also why the finished timeline is
 * registered on window.__timelines under the audience, which is the composition id.
 *
 * Scenes are stacked on top of each other and pushed past one another, so the stage
 * never reflows mid film and every scene owns the full frame.
 *
 * Behind all of them is one world layer, and in front of all of them one progress
 * line, neither of which is rebuilt at a cut. That is deliberate: a film in which
 * every pixel is replaced between scenes reads as a slide deck no matter how well
 * each scene is animated, because nothing on screen outlives the transition.
 */
window.ReelTimeline = (function () {
  'use strict';

  // The stage is a fixed coordinate space that player.js scales to fit. Absolute
  // pixel type is the only way a layout stays identical in a 380px tool window and
  // in a 1920px headless render.
  var SIZE = { w: 1200, h: 675 };
  /* Long enough for a push to read as a move rather than as a jump cut. */
  var CROSS = 0.42;
  var DEFAULT_SCENE_MS = 6000;

  /*
   * A scene enters from one edge and leaves towards the opposite one, and the edge
   * rotates. That is the single change that stops this reading as a slide deck: a
   * cross fade between two full frame layouts is exactly the transition a presentation
   * tool ships as its default, whereas a push has a direction, and a direction implies
   * a camera.
   */
  var PUSH = [{ x: 6, y: 0 }, { x: 0, y: 5 }, { x: -6, y: 0 }, { x: 0, y: -5 }];

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
      'product-ui': slots.heading || slots.brand || 'The product',
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
    // Retuned when the stage went light, and inverted at the top end. A pale mint that
    // was a usable accent on near-black is invisible on paper, while a deep navy that
    // the dark stage had to reject is one of the strongest marks available here.
    if (luma > 0.74 || luma < 0.06) return false;
    return saturation >= 0.16;
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
    // One harvested colour is given two of the four slots rather than all four: it
    // then leads the rotation without flattening the film back to a single hue, which
    // is the thing the built-in accents are there to prevent.
    if (colors.length === 1) root.style.setProperty('--reel-accent-3', colors[0]);
  }

  /*
   * A seeded generator, because the marks drifting in the background need positions
   * that look scattered and are identical on every machine. Math.random would give a
   * different film to the tool window and to the headless renderer, and the renderer
   * would produce a different one on every attempt.
   */
  function rng(seed) {
    var s = seed >>> 0;
    return function () {
      s = (s * 1664525 + 1013904223) >>> 0;
      return s / 4294967296;
    };
  }

  function seedOf(text) {
    var h = 2166136261;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = (h * 16777619) >>> 0;
    }
    return h;
  }

  /*
   * The layer behind every scene, built once and never rebuilt.
   *
   * Everything in here runs for the entire film on the master timeline, in finite
   * tweens with yoyo rather than repeat: -1, because an infinite repeat cannot be
   * seeked and the MP4 renderer does nothing but seek.
   */
  function mountWorld(root, audience) {
    var world = document.createElement('div');
    world.className = 'world';

    var blobs = [];
    ['a', 'b', 'c'].forEach(function (key) {
      var blob = document.createElement('span');
      blob.className = 'world__blob world__blob--' + key;
      world.appendChild(blob);
      blobs.push(blob);
    });

    var grid = document.createElement('span');
    grid.className = 'world__grid';
    world.appendChild(grid);

    // Ghost copies of the same icons the scenes wear, so the background belongs to
    // this film rather than being generic motion graphics.
    var motes = [];
    if (window.ReelIcons && !window.ReelScenes.reducedMotion) {
      var random = rng(seedOf(audience || 'reel'));
      var names = window.ReelIcons.names;
      for (var m = 0; m < 9; m++) {
        var mote = document.createElement('span');
        mote.className = 'world__mote';
        var icon = window.ReelIcons.get(names[(m * 5 + 3) % names.length]);
        mote.style.backgroundImage = 'url("' + icon.uri + '")';
        mote.style.left = (random() * 104 - 4) + '%';
        mote.style.top = (random() * 104 - 4) + '%';
        var size = 44 + Math.round(random() * 54);
        mote.style.width = size + 'px';
        mote.style.height = size + 'px';
        world.appendChild(mote);
        motes.push({ el: mote, lift: 90 + random() * 180, sway: random() * 46 - 23, spin: random() * 26 - 13 });
      }
    }

    var vignette = document.createElement('span');
    vignette.className = 'world__vignette';
    world.appendChild(vignette);

    root.appendChild(world);
    return { el: world, blobs: blobs, grid: grid, motes: motes };
  }

  /** The world's motion, laid down once the film's real length is known. */
  function animateWorld(world, total, tl) {
    if (!world || window.ReelScenes.reducedMotion) return;
    var blobs = world.blobs;
    var grid = world.grid;
    var motes = world.motes;
    var half = Math.max(total / 2, 0.5);
    var paths = [{ x: 92, y: 54, s: 1.14 }, { x: -104, y: -46, s: 1.1 }, { x: 68, y: 86, s: 1.18 }];
    for (var b = 0; b < blobs.length; b++) {
      gsap.set(blobs[b], { x: 0, y: 0, scale: 1, transformOrigin: '50% 50%' });
      tl.fromTo(blobs[b], { x: 0, y: 0, scale: 1 }, {
        x: paths[b].x, y: paths[b].y, scale: paths[b].s,
        duration: half, ease: 'sine.inOut', yoyo: true, repeat: 1, immediateRender: false
      }, 0);
    }

    // The grid pans against the blobs. Two layers moving at different rates over the
    // same seconds is the cheapest parallax there is, and it is what gives the frame
    // a sense of depth that a single flat background never has.
    tl.fromTo(grid, { backgroundPosition: '0px 0px' }, {
      backgroundPosition: '-68px -136px', duration: Math.max(total, 0.5), ease: 'none', immediateRender: false
    }, 0);

    for (var k = 0; k < motes.length; k++) {
      gsap.set(motes[k].el, { x: 0, y: 0, rotation: 0, transformOrigin: '50% 50%' });
      tl.fromTo(motes[k].el, { y: 0, rotation: 0 }, {
        y: -motes[k].lift, rotation: motes[k].spin,
        duration: Math.max(total, 0.5), ease: 'none', immediateRender: false
      }, 0);
      tl.fromTo(motes[k].el, { x: 0 }, {
        x: motes[k].sway, duration: half, ease: 'sine.inOut', yoyo: true, repeat: 1, immediateRender: false
      }, 0);
    }
  }

  /*
   * The progress line burned into the bottom edge, with one notch per scene.
   *
   * It is the only thing on screen guaranteed to be moving at every second of the
   * film, and it survives every cut, so it is also the strongest available signal
   * that these scenes are one piece rather than a queue.
   */
  function buildHud(root, chapters, total, tl) {
    var hud = document.createElement('div');
    hud.className = 'hud';
    var track = document.createElement('span');
    track.className = 'hud__track';
    hud.appendChild(track);

    var fill = document.createElement('span');
    fill.className = 'hud__fill';
    hud.appendChild(fill);

    for (var i = 1; i < chapters.length; i++) {
      var tick = document.createElement('span');
      tick.className = 'hud__tick';
      tick.style.left = ((chapters[i].start / Math.max(total, 0.001)) * 100) + '%';
      hud.appendChild(tick);
    }

    root.appendChild(hud);
    gsap.set(fill, { scaleX: 0, transformOrigin: '0% 50%' });
    tl.fromTo(fill, { scaleX: 0 }, {
      scaleX: 1, duration: Math.max(total, 0.001), ease: 'none', immediateRender: false
    }, 0);
    return hud;
  }

  /*
   * A scene's entrance and exit, as a push rather than a dissolve.
   *
   * The exit is deliberately shorter and sharper than the entrance: with both on the
   * same curve the two scenes spend the whole overlap at half opacity on top of each
   * other, which is muddier than either a cut or a fade. Leaving quickly and arriving
   * calmly is how the overlap reads as one displacing the other.
   */
  function transition(tl, el, index, at, dur) {
    var cross = Math.min(CROSS, dur / 3);
    if (window.ReelScenes.reducedMotion) {
      gsap.set(el, { autoAlpha: 0 });
      tl.fromTo(el, { autoAlpha: 0 }, { autoAlpha: 1, duration: cross, ease: 'power1.inOut', immediateRender: false },
        Math.max(0, at - cross));
      tl.fromTo(el, { autoAlpha: 1 }, { autoAlpha: 0, duration: cross, ease: 'power1.inOut', immediateRender: false },
        at + dur - cross);
      return;
    }

    var push = PUSH[index % PUSH.length];
    var enter = { autoAlpha: 0, xPercent: push.x, yPercent: push.y, scale: 1.045 };
    gsap.set(el, enter);
    tl.fromTo(el, enter, {
      autoAlpha: 1, xPercent: 0, yPercent: 0, scale: 1,
      duration: cross, ease: 'power2.out', immediateRender: false
    }, Math.max(0, at - cross));
    tl.fromTo(el, { autoAlpha: 1, xPercent: 0, yPercent: 0, scale: 1 }, {
      autoAlpha: 0, xPercent: -push.x * 0.75, yPercent: -push.y * 0.75, scale: 0.965,
      duration: cross * 0.8, ease: 'power2.in', immediateRender: false
    }, at + dur - cross);
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

    // Mounted before any scene so it sits behind all of them. Its motion is laid down
    // after the loop, once the film's real length has been measured.
    var world = mountWorld(root, audience);

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
      /*
       * A builder that decides it has nothing honest to draw says so by returning
       * nothing, and the film carries the sentence instead of an empty frame. The
       * recreated interface is the one that can reach this: its content is measured
       * rather than written, so it is the one scene that can legitimately find that
       * what it was given does not add up to a screen.
       */
      if (!built || !built.el) {
        built = window.ReelScenes['big-statement'](
          { statement: scene.narration || '', context: '' }, storyboard.theme || {}, ctx
        );
      }
      root.appendChild(built.el);

      /*
       * Content starts arriving slightly before the scene nominally begins, which is
       * to say while it is still sliding in.
       *
       * Without the lead there is a real hole at every cut: the push finishes exactly
       * at `at`, and the first thing inside does not move until `at + BODY_AT`, so a
       * quarter of a second of empty stage sits at the head of every scene. It is the
       * single most slide-deck-like moment in the film, and on a 6 second scene it is
       * 4% of the running time showing nothing.
       *
       * The end is held where it was: the scene still finishes at `at + dur`, so the
       * storyboard's pacing and the chapter marks are untouched.
       */
      var lead = Math.min(Math.min(CROSS, dur / 3) * 0.6, 0.25);
      var from = Math.max(0, at - lead);
      /*
       * Both of these add DOM, so both run before animate(), never after.
       *
       * The first thing a builder's animate() does is measure its own layout and fit
       * the type into it. Adding the provenance strip afterwards put a row of paths
       * into the header bar, the header grew, the body lost the height the header
       * gained, and the fit that had just been calculated was of a layout that no
       * longer existed. On a six band architecture scene that was five pixels, and
       * five pixels is the bottom row of component pills shaved in half.
       */
      addCaption(built.el, scene, tl, from);
      addProvenance(built.el, scene, tl, from, at + dur - from, ctx);
      built.animate(tl, from, at + dur - from);

      transition(tl, built.el, i, at, dur);

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

    animateWorld(world, at, tl);
    buildHud(root, chapters, at, tl);

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
