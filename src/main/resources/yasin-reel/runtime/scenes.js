/*
 * One builder per scene template.
 *
 * Each builder is `build(slots, theme, ctx) -> { el, animate(tl, startSec, durSec) }`.
 * Building and animating are split because the element has to be mounted before its
 * tweens first render: percentage geometry (the flow-trace rail) only resolves once
 * the stage has a width.
 *
 * Every tween is a `fromTo` with `immediateRender: false`, and every animated element
 * is parked in its from-state by `gsap.set` at build time. That combination is what
 * makes `tl.seek(t)` land on exactly frame t no matter which direction the playhead
 * arrived from, which is the precondition for rendering this page headlessly to MP4.
 *
 * Two rules shape every layout here. The frame is filled edge to edge, because a scene
 * that occupies the middle third of a 16:9 stage reads as a slide rather than as film.
 * And nothing holds still: each scene owns a slow continuous move that runs its whole
 * length, so a viewer who pauses on any second sees a frame that was going somewhere.
 */
window.ReelScenes = (function () {
  'use strict';

  var REDUCED = (function () {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
  })();

  /*
   * Under reduced motion we drop transforms and collapse staggers, but keep opacity,
   * duration and `left`. Timing has to survive because the scene still owns its slice
   * of the film, and the flow-trace packet is subject matter rather than decoration.
   */
  var MOTION_KEYS = {
    x: 1, y: 1, xPercent: 1, yPercent: 1, scale: 1, scaleX: 1, scaleY: 1,
    rotate: 1, rotation: 1, rotateX: 1, rotateY: 1, skewX: 1, skewY: 1
  };

  /*
   * The film has to fit a minute, so an entrance is over before the eye has finished
   * travelling to it. These are the only timing numbers in the file: every builder
   * spends them rather than inventing its own.
   */
  var IN = 0.4;
  var STEP = 0.06;
  var EASE = 'power3.out';
  var HEAD_AT = 0.08;
  var BODY_AT = 0.22;

  function calm(vars) {
    if (!REDUCED) return vars;
    var out = {};
    for (var key in vars) {
      if (!Object.prototype.hasOwnProperty.call(vars, key)) continue;
      if (MOTION_KEYS[key]) continue;
      out[key] = key === 'stagger' ? 0 : vars[key];
    }
    return out;
  }

  function empty(targets) {
    if (!targets) return true;
    if (targets.nodeType) return false;
    return targets.length === 0;
  }

  /*
   * READMEs and commit subjects carry markdown, and a storyboard slot is filled
   * straight from them. On screen `**42 Abu Dhabi**` is not emphasis, it is four
   * stray asterisks, so the markers are dropped at the point of display.
   */
  function clean(raw) {
    return String(raw)
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function make(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null && text !== '') node.textContent = String(text);
    return node;
  }

  function add(parent, tag, cls, text) {
    var node = make(tag, cls, text);
    parent.appendChild(node);
    return node;
  }

  function list(value, cap) {
    var out = Object.prototype.toString.call(value) === '[object Array]' ? value : [];
    return cap && out.length > cap ? out.slice(0, cap) : out;
  }

  function text(value, fallback) {
    if (value === undefined || value === null || value === '') return fallback || '';
    return clean(value);
  }

  /** Picks the first size whose length threshold the string still fits under. */
  function scaleType(node, value, steps) {
    var length = String(value || '').length;
    for (var i = 0; i < steps.length; i++) {
      if (length <= steps[i][0]) { node.style.fontSize = steps[i][1] + 'px'; return; }
    }
    node.style.fontSize = steps[steps.length - 1][1] + 'px';
  }

  /*
   * Everything from here to `settle` is the defence against a slot handed more than it
   * was designed for. The length steps above are an estimate from character count, and
   * an estimate is what produced the frame the user photographed: a sentence dropped
   * into a slot sized for a number, cut off at the top and the bottom of its card.
   *
   * These look at the box the browser actually laid out and step the type down until
   * the content is inside it. They run from animate(), the first moment a scene is
   * mounted and has a box, and they touch nothing but font-size, so the playhead and
   * every tween are unaffected. The result is a pure function of the layout, which is
   * why a headless render and a narrow tool window agree on it.
   */
  function num(value) {
    var parsed = parseFloat(value);
    return isFinite(parsed) ? parsed : 0;
  }

  /*
   * The content box of `host` in screen pixels, plus the factor between screen and
   * layout pixels: the stage is a fixed 1200x675 space scaled to the panel by one
   * transform, so a rect and a computed padding are in different units until now.
   */
  function contentBox(host) {
    var rect = host.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    var k = host.offsetWidth ? rect.width / host.offsetWidth : 1;
    if (!k || !isFinite(k)) k = 1;
    var style = window.getComputedStyle(host);
    return {
      k: k,
      top: rect.top + (num(style.borderTopWidth) + num(style.paddingTop)) * k,
      bottom: rect.bottom - (num(style.borderBottomWidth) + num(style.paddingBottom)) * k,
      left: rect.left + (num(style.borderLeftWidth) + num(style.paddingLeft)) * k,
      right: rect.right - (num(style.borderRightWidth) + num(style.paddingRight)) * k
    };
  }

  /*
   * True when anything inside `host` reaches past its content box.
   *
   * Child rects rather than scrollHeight, because a centred flex column overflows
   * equally above and below and the half above the top edge never reaches scrollHeight.
   * That is exactly the case that shipped: the tall value was cut off at both ends.
   */
  /*
   * How much of its own text an element is cutting off, past what is only rounding.
   *
   * The tolerances are a fraction of a line and a fraction of an em rather than a flat
   * pixel, and that is the whole point. Display type reports a scrollHeight a few
   * pixels over its clientHeight purely from line box rounding, and the bigger the type
   * the bigger that gap: at 132px it was 5px. Read against a 1px tolerance that looked
   * like a hidden line, so the fit pass shrank a number to its floor and a tile with
   * room for 132px digits rendered them at 60. A line that is genuinely hidden costs a
   * whole line box, so anything under half of one is rounding and nothing is missing.
   */
  function clips(node) {
    var style = window.getComputedStyle(node);
    var size = num(style.fontSize);
    var line = num(style.lineHeight) || size * 1.2;
    /*
     * The tolerance is the glyph overhang and nothing else.
     *
     * A typeface's ascent plus descent is around 1.2em, so a line box set tighter than
     * that cannot contain its own glyphs and the element reports the difference as
     * overflow although nothing is missing. Where the leading is generous there is no
     * overhang and the tolerance is a pixel, because there anything over really is a
     * line being cut.
     *
     * Both halves of that matter, and getting either wrong is visible. A flat 1px let
     * 132px digits report 5px of rounding as a hidden line, so the fit pass shrank them
     * to 60px in a tile with room for all of it. Half a line, which was the first fix,
     * went too far the other way and let a third of a line of body copy be shaved off
     * the bottom of a card.
     */
    var overhang = Math.max(0, size * 1.2 - line);
    // Two on top of the overhang, not one: scrollHeight and clientHeight are each
    // rounded to whole pixels independently, so they can disagree by a pixel in either
    // direction with nothing actually wrong.
    var over = Math.max(0, node.scrollHeight - node.clientHeight - overhang - 2);
    // One line ending in an ellipsis is the graceful case the stylesheet asked for,
    // not a spill, and shrinking the whole block to avoid it helps nobody.
    var tidy = style.textOverflow === 'ellipsis' && style.whiteSpace.indexOf('nowrap') >= 0;
    if (!tidy) {
      // Sideways the only routine slack is the letter-spacing trailing the last glyph.
      over += Math.max(0, node.scrollWidth - node.clientWidth - Math.max(1, num(style.letterSpacing)));
    }
    return over;
  }

  /**
   * @param nodes the blocks this fit owns. They are checked directly as well as through
   *        `host`, because `host.children` is one level deep and the block that clips is
   *        routinely a level below that: a card title lives inside the card's top row,
   *        so the card sees a row overflowing by 1px while the title inside it is
   *        cutting four lines down to three.
   */
  function spills(host, deep, nodes) {
    if (!host) return false;
    var box = contentBox(host);
    if (!box) return false;
    var slack = 0.75 * box.k;
    var kids = host.children;
    for (var i = 0; i < kids.length; i++) {
      var kid = kids[i];
      var style = window.getComputedStyle(kid);
      // Decoration is painted across the whole box on purpose, and a pinned chip sits
      // in padding the layout already reserved for it.
      if (style.position === 'absolute' || style.position === 'fixed') continue;
      if (style.display === 'none') continue;
      var rect = kid.getBoundingClientRect();
      if (!rect.width && !rect.height) continue;
      if (rect.top < box.top - slack || rect.bottom > box.bottom + slack) return true;
      if (rect.left < box.left - slack || rect.right > box.right + slack) return true;
      // A child clipping its own overflow hides the spill rather than showing it. The
      // shrink and trim passes treat that as an overflow and undo it; the clamp pass
      // must not, or it would go on cutting lines forever.
      if (!deep) continue;
      if (clips(kid) > 0) return true;
    }
    if (!deep) return false;
    // The host hides its own overflow too, so it can be the thing doing the cutting: an
    // architecture band whose pills wrap to a row it has no height for clips them
    // itself, and no child of it reports anything wrong.
    if (clips(host) > 0) return true;
    for (var n = 0; nodes && n < nodes.length; n++) {
      if (nodes[n] && clips(nodes[n]) > 0) return true;
    }
    return false;
  }

  /*
   * How far past its box `host` reaches, as one number.
   *
   * `spills` answers yes or no, which is all the shrink pass needs. The trim pass needs
   * to know whether what it just removed made anything better, so it needs a magnitude,
   * and the two have to agree about what counts as overflow or the trim pass would
   * chase a spill the shrink pass does not believe in. Same rules, different answer.
   */
  function excess(host, nodes) {
    var box = contentBox(host);
    if (!box) return 0;
    var total = 0;
    var kids = host.children;
    for (var i = 0; i < kids.length; i++) {
      var kid = kids[i];
      var style = window.getComputedStyle(kid);
      if (style.position === 'absolute' || style.position === 'fixed') continue;
      if (style.display === 'none') continue;
      var rect = kid.getBoundingClientRect();
      if (!rect.width && !rect.height) continue;
      total += Math.max(0, box.top - rect.top) + Math.max(0, rect.bottom - box.bottom);
      total += Math.max(0, box.left - rect.left) + Math.max(0, rect.right - box.right);
      total += clips(kid);
    }
    total += clips(host);
    for (var n = 0; nodes && n < nodes.length; n++) {
      if (nodes[n]) total += clips(nodes[n]);
    }
    return total;
  }

  /*
   * Steps the given type down together until nothing escapes `host`. One factor across
   * the whole set is what keeps the hierarchy inside a card: a shrunk title still reads
   * as the title next to its body.
   */
  function fitInto(host, nodes, floor) {
    if (!host || !nodes) return;
    var live = [];
    for (var i = 0; i < nodes.length; i++) if (nodes[i]) live.push(nodes[i]);
    if (!live.length || !spills(host, true, live)) return;
    var min = floor === undefined ? 15 : floor;
    var base = [];
    for (var b = 0; b < live.length; b++) base.push(num(window.getComputedStyle(live[b]).fontSize) || 16);
    var scale = 1;
    for (var step = 0; step < 24 && spills(host, true, live); step++) {
      scale -= 0.03;
      var floored = 0;
      for (var n = 0; n < live.length; n++) {
        // Half pixels rather than fractions, so the same storyboard produces the same
        // sizes on every machine that renders it.
        var next = Math.max(min, Math.round(base[n] * scale * 2) / 2);
        live[n].style.fontSize = next + 'px';
        if (next <= min) floored++;
      }
      // Below the floor legibility is gone and the stylesheet clamp takes over.
      if (floored === live.length) break;
    }
  }

  /*
   * When the type has shrunk as far as legibility allows and the content still does not
   * fit, the last resort: the block that is tallest gives up a line, and then the next
   * tallest, until the box holds what is left. The stylesheet marks which blocks may be
   * clamped at all; how many lines actually fit is a property of the box, so it is
   * decided here rather than written into a rule.
   */
  function clampInto(host, nodes) {
    for (var guard = 0; guard < 30 && spills(host, false, nodes); guard++) {
      var tallest = null;
      var lines = 0;
      var height = -1;
      for (var i = 0; i < nodes.length; i++) {
        if (!nodes[i]) continue;
        var style = window.getComputedStyle(nodes[i]);
        if (style.display.indexOf('box') < 0) continue;
        var count = parseInt(style.webkitLineClamp, 10);
        if (!isFinite(count) || count <= 1) continue;
        var box = nodes[i].getBoundingClientRect().height;
        if (box > height) { height = box; tallest = nodes[i]; lines = count; }
      }
      if (!tallest) return;
      tallest.style.setProperty('-webkit-line-clamp', String(lines - 1));
    }
  }

  /*
   * The end of the line, and the reason it is done in script rather than left to
   * -webkit-line-clamp: almost every block here is a flex item, a flex item is
   * blockified, and blockifying `display: -webkit-box` turns it into a plain block with
   * the clamp inert. The rules in the stylesheet still hold for the blocks that are not
   * flex items, and this trims the ones that are, whole words off the end with an
   * ellipsis in their place. Nothing is ever cut through the middle of a line.
   */
  function trimInto(host, nodes) {
    /*
     * `stale` is how this pass knows when to stop, and it shipped without one.
     *
     * A 6px badge overhanging its own box was enough to make every step of a journey
     * read "opens...", "reads...", "watches...": the spill was real, no amount of
     * trimming could resolve it, and the loop only stopped when each line was down to
     * its last word. Counting words removed is the wrong bound, because a card that
     * genuinely holds five lines too many needs a lot of them removed. What matters is
     * whether removing them is working, so that is what gets measured: a node whose
     * words have stopped buying any reduction in overflow is abandoned, and the
     * stylesheet's own clamp handles it from there.
     *
     * The tolerance is generous because one word rarely collapses a line on its own.
     */
    var stale = {};
    var idle = {};
    // The deep check, because the case this exists for is a flex item that was shrunk
    // by its own container and is now clipping its text through the middle of a line.
    for (var guard = 0; guard < 60 && spills(host, true, nodes); guard++) {
      var tallest = null;
      var height = -1;
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (stale[i]) continue;
        // A plain run of text only. A heading split into per-word spans is animated by
        // reference, so its words are not this pass's to remove.
        if (!node || node.children.length || !node.textContent) continue;
        var style = window.getComputedStyle(node);
        // Already ending in an ellipsis on its one line, by the stylesheet's own rule.
        if (style.textOverflow === 'ellipsis' && style.whiteSpace.indexOf('nowrap') >= 0) continue;
        var parts = node.textContent.replace(/\u2026$/, '').trim().split(' ');
        if (parts.length < 2) continue;
        var box = node.getBoundingClientRect().height;
        if (box > height) { height = box; tallest = { node: node, parts: parts, at: i }; }
      }
      if (!tallest) return;
      var before = excess(host, nodes);
      tallest.parts.pop();
      tallest.node.textContent = tallest.parts.join(' ') + '\u2026';
      if (excess(host, nodes) < before - 0.5) {
        idle[tallest.at] = 0;
      } else {
        idle[tallest.at] = (idle[tallest.at] || 0) + 1;
        // Six words gone with nothing to show for it means the overflow is not this
        // node's to fix, and taking a seventh only costs the reader a word.
        if (idle[tallest.at] >= 6) stale[tallest.at] = true;
      }
    }
  }

  /** Runs a builder's collected fits, once, at the top of its animate(). */
  function settle(fits) {
    for (var i = 0; i < fits.length; i++) {
      fitInto(fits[i].host, fits[i].nodes, fits[i].floor);
      clampInto(fits[i].host, fits[i].nodes);
      trimInto(fits[i].host, fits[i].nodes);
    }
  }

  /*
   * A stat value is meant to be a measurement, and the template draws it as one. A
   * director that sends a whole sentence instead gets a paragraph typeset as display
   * digits, which is the frame the user photographed. Prose is recognised here so it
   * can be typeset as prose, at a size a sentence can be read at.
   */
  function proseValue(value) {
    var body = text(value);
    if (!body) return false;
    return body.length > 16 || body.split(' ').length > 3;
  }

  /** Splits into per-word spans so type can arrive a word at a time. */
  function words(host, value, masked) {
    var parts = text(value).split(/\s+/);
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      if (!parts[i]) continue;
      var slot = add(host, 'span', masked ? 'w w--mask' : 'w');
      var inner = add(slot, 'span', 'w__in', parts[i]);
      out.push(masked ? inner : slot);
    }
    return out;
  }

  /*
   * The click-to-source affordance. The delegated handler in player.js reads these
   * attributes, so any element carrying data-file anywhere in a scene is clickable.
   */
  function sourceChip(file, line) {
    if (!file) return null;
    var chip = make('button', 'srcref');
    chip.type = 'button';
    chip.setAttribute('data-file', String(file));
    var label = String(file).split('/').pop();
    if (line !== undefined && line !== null && line !== '') {
      chip.setAttribute('data-line', String(line));
      label += ':' + line;
    }
    chip.textContent = label;
    // The basename is what fits on screen, so the full path lives in the tooltip.
    chip.title = 'Open ' + file + (line ? ':' + line : '');
    return chip;
  }

  /*
   * A path on screen is meaningless to the audience of the stakeholder cut, and the
   * one thing they asked never to see. `ctx.sources` is false for that cut, and the
   * chip is then not built at all rather than hidden, so no layout gap is left behind.
   */
  function refIn(ctx, host, file, line) {
    if (!ctx || ctx.sources === false) return null;
    var chip = sourceChip(file, line);
    if (chip) host.appendChild(chip);
    return chip;
  }

  function rise(tl, targets, at, opts) {
    if (empty(targets)) return;
    opts = opts || {};
    var from = { autoAlpha: 0, y: opts.y === undefined ? 20 : opts.y };
    var to = {
      autoAlpha: opts.to === undefined ? 1 : opts.to,
      y: 0,
      duration: opts.duration === undefined ? IN : opts.duration,
      ease: opts.ease || EASE,
      stagger: opts.stagger === undefined ? STEP : opts.stagger,
      immediateRender: false
    };
    if (opts.x !== undefined) { from.x = opts.x; to.x = 0; }
    if (opts.scale !== undefined) { from.scale = opts.scale; to.scale = 1; }
    if (opts.from !== undefined) from.autoAlpha = opts.from;
    gsap.set(targets, calm(from));
    tl.fromTo(targets, calm(from), calm(to), at);
  }

  /** A rule, spine or wire drawing itself in. */
  function draw(tl, targets, at, opts) {
    if (empty(targets)) return;
    opts = opts || {};
    var axis = opts.axis === 'y' ? 'scaleY' : 'scaleX';
    var from = {};
    var to = {};
    from[axis] = 0;
    to[axis] = 1;
    to.duration = opts.duration === undefined ? 0.45 : opts.duration;
    to.ease = opts.ease || EASE;
    to.stagger = opts.stagger === undefined ? 0 : opts.stagger;
    to.immediateRender = false;
    gsap.set(targets, { transformOrigin: opts.origin || (axis === 'scaleY' ? '50% 0%' : '0% 50%') });
    gsap.set(targets, calm(from));
    tl.fromTo(targets, calm(from), calm(to), at);
  }

  /*
   * The slow push on a framing block, and the reason a paused frame still reads as
   * film rather than as a screenshot.
   *
   * It replaced a 1.6% scale that was, honestly, too small to see. Two blocks in the
   * same scene are given different amounts and opposite directions, so the frame has
   * layers moving at different rates rather than one plane creeping.
   */
  function drift(tl, target, at, dur, amount, opts) {
    if (!target || REDUCED) return;
    opts = opts || {};
    var end = amount === undefined ? 1.045 : amount;
    var from = { scale: 1, x: 0, y: 0 };
    gsap.set(target, { scale: 1, x: 0, y: 0, transformOrigin: opts.origin || '50% 50%' });
    tl.fromTo(target, from, {
      scale: end,
      x: opts.x === undefined ? 0 : opts.x,
      y: opts.y === undefined ? 0 : opts.y,
      duration: Math.max(dur, 0.1), ease: 'none', immediateRender: false
    }, at);
  }

  /**
   * Mounts one icon, chosen from the words already on screen beside it.
   *
   * `taken` is per scene, so a grid of six cards comes out wearing six different
   * faces rather than the same one repeated, which is what a keyword match on similar
   * titles would otherwise produce.
   */
  function iconIn(host, primary, secondary, index, taken, cls) {
    if (!window.ReelIcons || !host) return null;
    var name = window.ReelIcons.pick(primary, secondary, index, taken);
    return window.ReelIcons.el(host, name, cls);
  }

  /** The entrance for an icon: overshoots, so it lands rather than appearing. */
  function pop(tl, targets, at, opts) {
    if (empty(targets)) return;
    opts = opts || {};
    var from = { autoAlpha: 0, scale: 0.34, rotation: REDUCED ? 0 : -16 };
    var to = {
      autoAlpha: 1, scale: 1, rotation: 0,
      duration: opts.duration === undefined ? 0.5 : opts.duration,
      ease: REDUCED ? 'power1.out' : 'back.out(2.4)',
      stagger: opts.stagger === undefined ? STEP : opts.stagger,
      immediateRender: false
    };
    gsap.set(targets, calm({ autoAlpha: 0, scale: 0.34, rotation: -16, transformOrigin: '50% 50%' }));
    tl.fromTo(targets, calm(from), calm(to), at);
  }

  /*
   * A gentle float that runs for the rest of the scene. One per icon, phase shifted by
   * position, so a row of six is never in lockstep: six things bobbing together read
   * as one object, and six things bobbing out of phase read as six.
   */
  function bob(tl, nodes, start, dur, from) {
    if (REDUCED || !nodes || !nodes.length) return;
    var span = Math.max(dur - (from - start) - 0.2, 0.5);
    for (var i = 0; i < nodes.length; i++) {
      if (!nodes[i]) continue;
      var lead = (i % 3) * 0.16;
      var half = Math.max((span - lead) / 2, 0.35);
      tl.fromTo(nodes[i], { y: 0 }, {
        y: -7, duration: half, ease: 'sine.inOut', yoyo: true, repeat: 1, immediateRender: false
      }, from + lead);
    }
  }

  /*
   * The gradient inside a rule travelling along it. Cheap, but it means a scene whose
   * content has finished arriving is still visibly running.
   */
  function shimmer(tl, target, at, dur, axis) {
    if (!target || REDUCED) return;
    var from = axis === 'y' ? '50% 0%' : '0% 50%';
    var to = axis === 'y' ? '50% -200%' : '-200% 50%';
    tl.fromTo(target, { backgroundPosition: from }, {
      backgroundPosition: to, duration: Math.max(dur, 0.1), ease: 'none', immediateRender: false
    }, at);
  }

  /*
   * A light passing over a list, one item at a time, spread across whatever time the
   * scene has left after its entrance. This is the second motion in most scenes: it
   * also reads as the film pointing at each item in turn.
   */
  function sweep(tl, glows, from, until, cards, icons) {
    if (REDUCED || !glows || !glows.length) return;
    var span = Math.max(until - from, 0.6);
    var slot = span / glows.length;
    var hold = Math.min(Math.max(slot * 0.45, 0.25), 0.7);
    for (var i = 0; i < glows.length; i++) {
      var at = from + i * slot;
      gsap.set(glows[i], { autoAlpha: 0 });
      tl.fromTo(glows[i], { autoAlpha: 0 }, {
        autoAlpha: 1, duration: hold, ease: 'sine.inOut',
        yoyo: true, repeat: 1, immediateRender: false
      }, at);
      // The lit card also grows very slightly and its icon leans. A tint alone is a
      // change of colour; a change of size is the film physically pointing at it.
      if (cards && cards[i]) {
        tl.fromTo(cards[i], { scale: 1 }, {
          scale: 1.022, duration: hold, ease: 'sine.inOut',
          yoyo: true, repeat: 1, transformOrigin: '50% 50%', immediateRender: false
        }, at);
      }
      if (icons && icons[i]) {
        tl.fromTo(icons[i], { scale: 1, rotation: 0 }, {
          scale: 1.16, rotation: 7, duration: hold, ease: 'sine.inOut',
          yoyo: true, repeat: 1, transformOrigin: '50% 50%', immediateRender: false
        }, at);
      }
    }
  }

  function group(value) {
    var parts = value.split('.');
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return parts.join('.');
  }

  /*
   * Splits "~12,400 ms" into prefix, number and suffix so a value can count up while
   * keeping whatever shape the harvester found it in.
   */
  function parseNumber(raw) {
    if (raw === undefined || raw === null) return null;
    var match = /^([^\d\-+]*)([-+]?\d[\d,]*(?:\.\d+)?)(.*)$/.exec(String(raw));
    if (!match) return null;
    var digits = match[2];
    var grouped = digits.indexOf(',') >= 0;
    var dot = digits.indexOf('.');
    var decimals = dot < 0 ? 0 : digits.length - dot - 1;
    var value = parseFloat(digits.replace(/,/g, ''));
    if (isNaN(value)) return null;
    return {
      value: value,
      render: function (n) {
        var out = n.toFixed(decimals);
        if (grouped) out = group(out);
        return match[1] + out + match[3];
      }
    };
  }

  /*
   * Counts through a proxy object, and writes the digits from a modifier rather than
   * from onUpdate.
   *
   * That distinction decides whether the scene survives being scrubbed: gsap.seek()
   * suppresses callbacks by default, so an onUpdate counter silently freezes the
   * moment anything renders the timeline instead of playing it. A modifier runs inside
   * the property tween's own render, so the digits are a pure function of the playhead.
   */
  function countUp(tl, node, raw, at, duration) {
    var parsed = parseNumber(raw);
    if (!parsed) {
      node.textContent = text(raw);
      return;
    }
    node.textContent = parsed.render(0);
    tl.fromTo({ v: 0 }, { v: 0 }, {
      v: parsed.value,
      duration: duration,
      ease: 'power2.out',
      immediateRender: false,
      lazy: false,
      modifiers: {
        v: function (value) {
          node.textContent = parsed.render(value);
          return value;
        }
      }
    }, at);
  }

  /** Column centre of cell i of n, as a percentage of the track. */
  function centre(i, n) {
    return ((i + 0.5) / n) * 100;
  }

  function scene(kind) {
    return make('div', 'scene scene--' + kind);
  }

  /*
   * Every scene but the two title cards opens with the same bar: a label on the left
   * and a rule running out to the right margin. It costs one line and it is what stops
   * the top edge of the frame being empty, whatever the template underneath does.
   */
  function header(el, label) {
    var head = add(el, 'div', 'scene__head');
    var eyebrow = label ? add(head, 'span', 'scene__eyebrow', label) : null;
    // A heading written as a sentence is wider than the bar. It steps down before the
    // ellipsis in the stylesheet has to take over, and the rule keeps its own share.
    if (eyebrow) scaleType(eyebrow, label, [[34, 20], [52, 17], [999, 15]]);
    var rule = add(head, 'span', 'scene__rule');
    return {
      head: head,
      eyebrow: eyebrow,
      rule: rule,
      animate: function (tl, start, dur) {
        if (eyebrow) rise(tl, eyebrow, start + HEAD_AT, { y: 10, duration: 0.35 });
        draw(tl, rule, start + HEAD_AT + 0.05, { duration: 0.5 });
        shimmer(tl, rule, start, dur);
      }
    };
  }

  /*
   * The stretchy region under the header. The modifier is namespaced because several
   * templates already own a `<prefix>__body` of their own on an inner element.
   */
  function bodyOf(el, kind) {
    return add(el, 'div', 'scene__body scene__body--' + kind);
  }

  /** Columns that keep a list wide rather than tall, which is what fills a 16:9 frame. */
  function columnsFor(n) {
    if (n <= 1) return 1;
    if (n <= 3) return n;
    if (n === 4) return 2;
    return 3;
  }

  function glow(host) {
    return add(host, 'span', 'glow');
  }

  // ---------------------------------------------------------------- title

  function buildTitle(slots, theme, ctx) {
    var el = scene('title');
    var block = add(el, 'div', 'ti__block');

    var project = text(theme && theme.projectName);
    var name = text(slots.productName, project || 'This project');
    var blurb = text(slots.tagline);

    var top = add(block, 'div', 'ti__top');
    var mark = add(top, 'span', 'ti__mark');
    var icon = iconIn(mark, name, blurb, 0, null);
    var kicker = add(top, 'span', 'ti__kicker', project && project !== name ? project : 'Nexus Reel');

    var mid = add(block, 'div', 'ti__mid');
    var heading = add(mid, 'h1', 'ti__name');
    scaleType(heading, name, [[12, 118], [18, 96], [26, 76], [40, 60], [64, 48], [999, 38]]);
    var marks = words(heading, name, true);
    var rule = add(mid, 'span', 'ti__rule');
    var tagline = text(slots.tagline) ? add(mid, 'p', 'ti__tagline', text(slots.tagline)) : null;
    if (tagline) scaleType(tagline, slots.tagline, [[70, 38], [130, 32], [220, 27], [999, 23]]);

    var foot = add(block, 'div', 'ti__foot');
    var repo = text(slots.repoUrl) ? add(foot, 'span', 'ti__repo', text(slots.repoUrl)) : null;
    var chip = refIn(ctx, foot, slots.file, slots.line);

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle([
          { host: mid, nodes: [heading, tagline], floor: 20 },
          { host: block, nodes: [heading, tagline], floor: 20 }
        ]);
        drift(tl, block, start, dur, 1.05, { y: -9 });
        pop(tl, icon, start + HEAD_AT, { duration: 0.6 });
        bob(tl, [icon], start, dur, start + 0.75);
        rise(tl, kicker, start + HEAD_AT + 0.1, { y: 10, duration: 0.35 });
        rise(tl, marks, start + 0.2, { y: 56, duration: 0.5, stagger: 0.055 });
        draw(tl, rule, start + 0.45, { duration: 0.5 });
        shimmer(tl, rule, start + 0.45, dur - 0.45);
        if (tagline) rise(tl, tagline, start + 0.6, { y: 16 });
        if (repo) rise(tl, repo, start + 0.75, { y: 10, duration: 0.35 });
        if (chip) rise(tl, chip, start + 0.82, { y: 10, duration: 0.35 });
      }
    };
  }

  // -------------------------------------------------------- big-statement

  function buildBigStatement(slots, theme, ctx) {
    var el = scene('statement');
    var block = add(el, 'div', 'bs__block');

    var mid = add(block, 'div', 'bs__mid');
    var raw = text(slots.statement, text(slots.text));
    // The icon carries the left of the frame. One sentence, however large, leaves a
    // 16:9 stage looking like a pull quote in a document; a mark beside it composes.
    var icon = iconIn(mid, raw, text(slots.context), 1, null);
    // A rule the full height of the frame rather than a short one across the top: one
    // sentence cannot fill 16:9 on its own, so the composition has to.
    var bar = add(mid, 'span', 'bs__bar');
    var quote = add(mid, 'blockquote', 'bs__text');
    scaleType(quote, raw, [[24, 100], [44, 82], [72, 68], [120, 54], [200, 44], [330, 36], [999, 30]]);
    var marks = words(quote, raw, true);

    var foot = add(block, 'div', 'bs__foot');
    var context = text(slots.context) ? add(foot, 'p', 'bs__context', text(slots.context)) : null;
    if (context) scaleType(context, slots.context, [[120, 22], [230, 20], [999, 18]]);
    var chip = refIn(ctx, foot, slots.file, slots.line);

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle([
          { host: mid, nodes: [quote], floor: 22 },
          { host: block, nodes: [quote, context], floor: 17 }
        ]);
        drift(tl, block, start, dur, 1.042, { y: -7 });
        pop(tl, icon, start + HEAD_AT, { duration: 0.6 });
        bob(tl, [icon], start, dur, start + 0.8);
        draw(tl, bar, start + HEAD_AT, { axis: 'y', duration: 0.55 });
        shimmer(tl, bar, start, dur, 'y');
        rise(tl, marks, start + 0.18, { y: 44, duration: 0.5, stagger: 0.05 });
        var after = Math.min(0.18 + 0.05 * marks.length + 0.3, Math.max(dur - 1.1, 0.6));
        if (context) rise(tl, context, start + after, { y: 14 });
        if (chip) rise(tl, chip, start + after + 0.1, { y: 10, duration: 0.35 });
      }
    };
  }

  // ------------------------------------------------------------ stat-grid

  function buildStatGrid(slots, theme, ctx) {
    var el = scene('stats');
    var stats = list(slots.stats, 6);
    if (!stats.length) stats = [{ label: 'No measurements', value: '0' }];
    var head = header(el, text(slots.heading, text(slots.title)));
    var body = bodyOf(el, 'sg');
    var cols = columnsFor(stats.length);
    var grid = add(body, 'div', 'sg__grid');
    grid.setAttribute('data-count', String(stats.length));
    grid.setAttribute('data-cols', String(cols));
    grid.style.gridTemplateColumns = 'repeat(' + cols + ', 1fr)';

    // One sentence in the set is enough to change the shape of every tile: a column of
    // digits beside a column of paragraphs reads as two scenes spliced together.
    var anyProse = false;
    for (var p = 0; p < stats.length; p++) {
      if (proseValue(stats[p].value)) { anyProse = true; break; }
    }
    grid.setAttribute('data-shape', anyProse ? 'text' : 'number');

    var cells = [];
    var bars = [];
    var values = [];
    var glows = [];
    var fits = [];
    var counts = [];
    var icons = [];
    var taken = {};
    for (var i = 0; i < stats.length; i++) {
      var cell = add(grid, 'div', 'sg__cell');
      glows.push(glow(cell));
      bars.push(add(cell, 'span', 'sg__bar'));
      icons.push(iconIn(cell, text(stats[i].label), text(stats[i].value), i, taken));
      var prose = proseValue(stats[i].value);
      var value = add(cell, 'strong', 'sg__value' + (prose ? ' sg__value--prose' : ''));
      // Written now rather than by the count, because the fit pass has to measure the
      // string that will actually be on screen.
      value.textContent = text(stats[i].value);
      // The count is the subject of the scene, so the digits get whatever room the
      // column width leaves rather than a size fixed for the shortest case.
      if (prose) {
        scaleType(value, stats[i].value, cols >= 3
          ? [[30, 26], [64, 23], [120, 20], [999, 18]]
          : [[30, 34], [64, 29], [120, 25], [999, 21]]);
      } else {
        scaleType(value, stats[i].value, cols >= 3
          ? [[4, 78], [7, 62], [999, 48]]
          : [[4, 132], [7, 100], [999, 72]]);
      }
      values.push(value);
      counts.push(!prose);
      var meta = add(cell, 'div', 'sg__meta');
      var label = add(meta, 'span', 'sg__label', text(stats[i].label));
      refIn(ctx, meta, stats[i].file, stats[i].line);
      fits.push({ host: cell, nodes: [value, label], floor: 14 });
      cells.push(cell);
    }

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle(fits);
        head.animate(tl, start, dur);
        drift(tl, body, start, dur, 1.016);
        var open = start + BODY_AT;
        rise(tl, cells, open, { y: 26, duration: IN, stagger: STEP });
        draw(tl, bars, open + 0.12, { duration: 0.4, stagger: STEP });
        pop(tl, icons, open + 0.14, { stagger: STEP });
        var settled = open + STEP * cells.length + IN;
        bob(tl, icons, start, dur, settled);
        // Long enough to read as counting, short enough to settle before the cut.
        var span = Math.max(0.7, Math.min(1.3, dur - 1.4));
        for (var i = 0; i < values.length; i++) {
          // Counting a sentence up from zero is nonsense, and it would also re-wrap the
          // paragraph on every frame, so prose is written once and left alone.
          if (counts[i]) countUp(tl, values[i], stats[i].value, open + 0.1 + i * STEP, span);
        }
        sweep(tl, glows, settled, start + dur - 0.2, cells, icons);
      }
    };
  }

  // ----------------------------------------------------- capability-cards

  function buildCapabilityCards(slots, theme, ctx) {
    var el = scene('cards');
    var cards = list(slots.cards, 6);
    if (!cards.length) cards = [{ title: 'No capabilities found', body: '' }];
    var head = header(el, text(slots.heading, text(slots.title)));
    var body = bodyOf(el, 'cc');
    var cols = columnsFor(cards.length);
    var row = add(body, 'div', 'cc__row');
    row.setAttribute('data-count', String(cards.length));
    row.setAttribute('data-cols', String(cols));
    row.style.gridTemplateColumns = 'repeat(' + cols + ', 1fr)';

    var nodes = [];
    var bars = [];
    var glows = [];
    var fits = [];
    var icons = [];
    var taken = {};
    for (var i = 0; i < cards.length; i++) {
      var card = add(row, 'article', 'cc__card');
      glows.push(glow(card));
      bars.push(add(card, 'span', 'cc__bar'));
      var top = add(card, 'div', 'cc__top');
      // The icon replaces the 01, 02, 03 that used to number these. A card in a row of
      // four does not need to be told its position, and the icon says what it is about.
      icons.push(iconIn(top, text(cards[i].title), text(cards[i].body), i, taken));
      var title = add(top, 'h3', 'cc__title', text(cards[i].title));
      scaleType(title, cards[i].title, cols >= 3 ? [[24, 33], [48, 28], [999, 24]] : [[30, 39], [60, 33], [999, 27]]);
      var copy = null;
      if (text(cards[i].body)) {
        copy = add(card, 'p', 'cc__body', text(cards[i].body));
        scaleType(copy, cards[i].body, cols >= 3 ? [[90, 24], [180, 21], [999, 18]] : [[120, 27], [260, 23], [999, 19]]);
      }
      refIn(ctx, card, cards[i].file, cards[i].line);
      fits.push({ host: card, nodes: [title, copy], floor: 14 });
      nodes.push(card);
    }

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle(fits);
        head.animate(tl, start, dur);
        drift(tl, body, start, dur, 1.016);
        var open = start + BODY_AT;
        rise(tl, nodes, open, { y: 30, scale: 0.975, duration: IN, stagger: STEP });
        draw(tl, bars, open + 0.14, { duration: 0.4, stagger: STEP });
        pop(tl, icons, open + 0.16, { stagger: STEP });
        var landed = open + STEP * nodes.length + IN;
        bob(tl, icons, start, dur, landed);
        sweep(tl, glows, landed, start + dur - 0.2, nodes, icons);
      }
    };
  }

  // ----------------------------------------------------------- arch-layers

  function buildArchLayers(slots, theme, ctx) {
    var el = scene('layers');
    // Six rather than five: a sixth layer that arrives should be drawn small, not
    // silently dropped out of the picture the film claims is complete.
    var layers = list(slots.layers, 6);
    if (!layers.length) layers = [{ name: 'No layers detected', components: [] }];
    var head = header(el, text(slots.heading, 'Architecture'));
    var body = bodyOf(el, 'al');
    var stack = add(body, 'div', 'al__stack');
    stack.setAttribute('data-count', String(layers.length));

    var rows = [];
    var glows = [];
    var parts = [];
    var fits = [];
    var icons = [];
    var taken = {};
    for (var i = 0; i < layers.length; i++) {
      var row = add(stack, 'div', 'al__layer');
      glows.push(glow(row));
      var components = list(layers[i].components, 8);
      // What a band contains says more about it than its name alone: a layer called
      // "Core" is unreadable, the same layer holding Gson and a JSON contract is not.
      var inside = '';
      for (var c = 0; c < components.length; c++) {
        inside += ' ' + text(components[c].name) + ' ' + text(components[c].tech);
      }
      var headCell = add(row, 'div', 'al__head');
      icons.push(iconIn(headCell, text(layers[i].name), inside, i, taken));
      var name = add(headCell, 'span', 'al__name', text(layers[i].name));
      var bag = add(row, 'div', 'al__parts');
      var owned = [];
      for (var j = 0; j < components.length; j++) {
        var part = add(bag, 'span', 'al__part');
        add(part, 'span', 'al__part-name', text(components[j].name));
        if (text(components[j].tech)) add(part, 'em', 'al__part-tech', text(components[j].tech));
        refIn(ctx, part, components[j].file, components[j].line);
        owned.push(part);
      }
      // A band is one row of the picture, so its name and its pills shrink together
      // rather than the pills wrapping into the band below.
      fits.push({ host: row, nodes: [name].concat(owned), floor: 12 });
      rows.push(row);
      parts.push(owned);
    }

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle(fits);
        head.animate(tl, start, dur);
        drift(tl, body, start, dur, 1.03, { y: -6 });
        var open = start + BODY_AT;
        // Layers land top-down, so the picture assembles the way you would draw it.
        var step = Math.max(0.16, Math.min(0.3, (dur - 1.8) / Math.max(rows.length, 1)));
        var last = open;
        for (var i = 0; i < rows.length; i++) {
          var at = open + i * step;
          rise(tl, rows[i], at, { y: 16, x: -18, duration: IN });
          if (icons[i]) pop(tl, icons[i], at + 0.06, { duration: 0.44 });
          if (parts[i].length) rise(tl, parts[i], at + 0.1, { y: 10, duration: 0.34, stagger: 0.04 });
          last = at + 0.1 + 0.04 * parts[i].length + 0.34;
        }
        bob(tl, icons, start, dur, last);
        sweep(tl, glows, last, start + dur - 0.2, rows, icons);
      }
    };
  }

  // ----------------------------------------------------------- flow-trace

  /** Accepts the modelled `steps`, or the shorthand from/via/to seen in early drafts. */
  function flowSteps(slots) {
    var steps = list(slots.steps, 6);
    if (steps.length) return steps;
    var shorthand = [];
    ['from', 'via', 'to'].forEach(function (key) {
      if (text(slots[key])) shorthand.push({ label: slots[key] });
    });
    return shorthand;
  }

  function buildFlowTrace(slots, theme, ctx) {
    var el = scene('flow');
    var steps = flowSteps(slots);
    if (!steps.length) steps = [{ label: 'No traced path' }];
    var n = steps.length;
    var head = header(el, text(slots.name, text(slots.heading, 'Traced path')));
    var body = bodyOf(el, 'ft');
    var track = add(body, 'div', 'ft__track');
    track.setAttribute('data-count', String(n));
    var rail = add(track, 'div', 'ft__rail');

    var wires = [];
    for (var w = 0; w < n - 1; w++) {
      var wire = add(rail, 'span', 'ft__wire');
      wire.style.left = centre(w, n) + '%';
      wire.style.width = (100 / n) + '%';
      wires.push(wire);
    }

    var nodes = [];
    var dots = [];
    var halos = [];
    var labels = [];
    var fits = [];
    var taken = {};
    for (var i = 0; i < n; i++) {
      var node = add(rail, 'div', 'ft__node');
      node.style.left = centre(i, n) + '%';
      node.style.width = (100 / n) + '%';
      // The rail runs through the middle and the cards alternate above and below it.
      // Five cards stacked under one wire leaves half the frame empty; zigzagged they
      // use the whole height and the path still reads left to right.
      var up = add(node, 'div', 'ft__slot ft__slot--up');
      var mark = add(node, 'span', 'ft__mark');
      halos.push(add(mark, 'span', 'ft__halo'));
      // The icon is the node itself. Every pulse the packet triggers on arrival was
      // already written against `dots`, so putting the icon in that slot means the
      // whole travelling animation now lands on something that says what the step is.
      dots.push(iconIn(mark, text(steps[i].label), text(steps[i].detail), i, taken, 'ic--round')
        || add(mark, 'span', 'ft__dot'));
      var down = add(node, 'div', 'ft__slot ft__slot--down');
      var card = add(i % 2 === 0 ? down : up, 'div', 'ft__body');
      var label = add(card, 'span', 'ft__label', text(steps[i].label, 'Step ' + (i + 1)));
      scaleType(label, steps[i].label, n >= 5 ? [[26, 22], [60, 19], [999, 16]] : [[26, 28], [60, 23], [999, 20]]);
      var detail = text(steps[i].detail) ? add(card, 'span', 'ft__detail', text(steps[i].detail)) : null;
      refIn(ctx, card, steps[i].file, steps[i].line);
      fits.push({ host: card, nodes: [label, detail], floor: 12 });
      nodes.push(node);
      labels.push(card);
    }

    var packet = add(rail, 'span', 'ft__packet');
    packet.style.left = centre(0, n) + '%';

    return {
      el: el,
      animate: function (tl, start, dur) {
        // After mount, so the percentage column widths these cards live in are real.
        settle(fits);
        head.animate(tl, start, dur);
        drift(tl, body, start, dur, 1.028, { y: -5 });
        gsap.set(nodes, { xPercent: -50 });
        gsap.set(packet, { xPercent: -50, yPercent: -50 });
        gsap.set(halos, { autoAlpha: 0, scale: 0.4, transformOrigin: '50% 50%' });

        var open = start + BODY_AT;
        rise(tl, nodes, open, { y: 18, duration: 0.38, stagger: 0.05 });
        // Steps start dim and light up as the packet reaches them.
        gsap.set(labels, { autoAlpha: 0.4 });
        // After the gsap.set above, not before: pop parks the icons in its own from
        // state, and a set running afterwards would quietly undo that parking.
        pop(tl, dots, open + 0.08, { stagger: 0.05, duration: 0.46 });

        var runStart = open + 0.05 * n + 0.32;
        rise(tl, packet, runStart - 0.24, { y: 0, scale: 0.2, duration: 0.28 });
        rise(tl, labels[0], runStart - 0.08, { y: 0, from: 0.4, to: 1, duration: 0.26 });

        var hops = Math.max(n - 1, 1);
        // The packet spends the whole rest of the scene in flight, which is this
        // template's continuous move.
        var budget = Math.max(1.2, dur - (runStart - start) - 0.4);
        var hop = budget / hops;
        var travel = hop * 0.74;

        for (var i = 0; i < n - 1; i++) {
          var at = runStart + i * hop;
          tl.fromTo(wires[i], { scaleX: 0 }, {
            scaleX: 1, duration: travel, ease: 'power2.inOut',
            transformOrigin: '0% 50%', immediateRender: false
          }, at);
          tl.fromTo(packet, { left: centre(i, n) + '%' }, {
            left: centre(i + 1, n) + '%', duration: travel, ease: 'power2.inOut', immediateRender: false
          }, at);
          var landed = at + travel;
          tl.fromTo(dots[i + 1], { scale: 1, rotation: 0 }, {
            scale: 1.3, rotation: 9, duration: 0.16, ease: 'power2.out',
            yoyo: true, repeat: 1, immediateRender: false
          }, landed);
          tl.fromTo(halos[i + 1], { autoAlpha: 0.55, scale: 0.4 }, {
            autoAlpha: 0, scale: 2.1, duration: 0.5, ease: 'power2.out', immediateRender: false
          }, landed);
          rise(tl, labels[i + 1], landed, { y: 0, from: 0.4, to: 1, duration: 0.26 });
        }

        gsap.set(wires, { scaleX: 0, transformOrigin: '0% 50%' });
      }
    };
  }

  // -------------------------------------------------------------- journey

  function buildJourney(slots, theme, ctx) {
    var el = scene('journey');
    // Eight rather than six: a journey with more beats than expected should read as a
    // longer journey, not as one that quietly stops two thirds of the way through.
    var steps = list(slots.steps, 8);
    if (!steps.length) steps = [{ actor: 'Someone', action: 'uses the product' }];
    var head = header(el, text(slots.name, text(slots.heading, 'What happens')));
    var body = bodyOf(el, 'jr');
    var track = add(body, 'div', 'jr__track');
    track.setAttribute('data-count', String(steps.length));
    var spine = add(track, 'span', 'jr__spine');
    var pip = add(track, 'span', 'jr__pip');
    var stack = add(track, 'div', 'jr__stack');

    var rows = [];
    var fits = [];
    var icons = [];
    var taken = {};
    for (var i = 0; i < steps.length; i++) {
      var row = add(stack, 'div', 'jr__step');
      // The icon becomes the badge and the number shrinks into its corner. The order
      // still matters on a journey, so the number stays; it just stops being the only
      // thing distinguishing one beat from the next.
      var mark = add(row, 'span', 'jr__mark');
      icons.push(iconIn(mark, text(steps[i].action, text(steps[i].label)), text(steps[i].actor), i, taken));
      add(mark, 'span', 'jr__badge', String(i + 1));
      var card = add(row, 'div', 'jr__body');
      var actor = text(steps[i].actor) ? add(card, 'span', 'jr__actor', text(steps[i].actor)) : null;
      var action = add(card, 'span', 'jr__action', text(steps[i].action, text(steps[i].label)));
      scaleType(action, steps[i].action || steps[i].label,
        steps.length >= 7
          ? [[46, 25], [90, 21], [999, 18]]
          : steps.length >= 5 ? [[46, 31], [90, 26], [999, 22]] : [[46, 40], [90, 32], [999, 26]]);
      refIn(ctx, card, steps[i].file, steps[i].line);
      // Each step owns an equal share of the frame, so a long one shrinks inside its
      // share instead of pushing the step under it off the bottom.
      fits.push({ host: row, nodes: [actor, action], floor: 13 });
      rows.push(row);
    }

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle(fits);
        head.animate(tl, start, dur);
        drift(tl, body, start, dur, 1.026, { y: -5 });
        var open = start + BODY_AT;
        var run = Math.max(1.2, dur - (BODY_AT + 0.4));
        // Every step is on screen by about the middle of the scene. Paced across the
        // whole run instead, a three step journey spends most of its time showing one
        // line of text and a lot of nothing.
        var reveal = Math.min(run, Math.max(1.0, dur * 0.45));
        draw(tl, spine, open, { axis: 'y', duration: reveal + 0.35, ease: 'none' });
        if (!REDUCED) {
          // The pip keeps running to the end of the scene, which is what stops the
          // frame going still once the last step has landed.
          gsap.set(pip, { xPercent: -50, yPercent: -50 });
          tl.fromTo(pip, { top: '0%', autoAlpha: 0 }, {
            top: '100%', autoAlpha: 1, duration: run, ease: 'none', immediateRender: false
          }, open);
        }
        for (var i = 0; i < rows.length; i++) {
          var at = open + reveal * (i / rows.length);
          rise(tl, rows[i], at, { y: 12, x: 22, duration: IN });
          if (icons[i]) pop(tl, icons[i], at + 0.08, { duration: 0.46 });
        }
        bob(tl, icons, start, dur, open + reveal + 0.3);
      }
    };
  }

  // ---------------------------------------------------------------- outro

  function buildOutro(slots, theme, ctx) {
    var el = scene('outro');
    var block = add(el, 'div', 'ou__block');
    var mid = add(block, 'div', 'ou__mid');
    // `headline` and `sub` are the names the Kotlin directors used before the two
    // halves were reconciled, and a model given the old catalogue can still send them.
    // Reading both beats rendering a placeholder over whatever was actually written.
    var call = text(slots.cta, text(slots.headline, 'Thanks for watching'));
    var mark = add(mid, 'span', 'ou__mark');
    var ring = add(mark, 'span', 'ou__ring');
    var icon = iconIn(mark, call, text(slots.repoUrl, text(slots.sub)), 0, null);
    var heading = add(mid, 'h2', 'ou__cta');
    scaleType(heading, call, [[28, 78], [52, 62], [90, 50], [150, 40], [999, 32]]);
    var marks = words(heading, call, true);
    var rule = add(mid, 'span', 'ou__rule');

    var foot = add(block, 'div', 'ou__foot');
    var under = text(slots.repoUrl, text(slots.sub));
    var repo = under ? add(foot, 'span', 'ou__repo', under) : null;
    var stampText = text(slots.generatedAt);
    var stamp = add(foot, 'span', 'ou__stamp',
      'Nexus Reel' + (theme && theme.projectName ? ' for ' + theme.projectName : '') + (stampText ? ' · ' + stampText : ''));

    return {
      el: el,
      animate: function (tl, start, dur) {
        settle([
          { host: mid, nodes: [heading], floor: 22 },
          { host: block, nodes: [heading, repo, stamp], floor: 16 }
        ]);
        drift(tl, block, start, dur, 1.04, { y: -8 });
        pop(tl, icon, start + HEAD_AT, { duration: 0.6 });
        bob(tl, [icon], start, dur, start + 0.8);
        gsap.set(ring, { transformOrigin: '50% 50%' });
        // The ring keeps pulsing for the whole outro, so the last card is never a still.
        tl.fromTo(ring, { scale: 0.55, autoAlpha: 0.75 }, {
          scale: 1.9, autoAlpha: 0, duration: Math.max(dur * 0.45, 0.8), ease: 'power2.out',
          repeat: 1, immediateRender: false
        }, start + 0.2);
        rise(tl, marks, start + 0.3, { y: 34, duration: 0.45, stagger: 0.055 });
        draw(tl, rule, start + 0.55, { duration: 0.5 });
        shimmer(tl, rule, start + 0.55, dur - 0.55);
        if (repo) rise(tl, repo, start + 0.65, { y: 12, duration: 0.35 });
        rise(tl, stamp, start + 0.72, { y: 12, duration: 0.35 });
      }
    };
  }

  return {
    'title': buildTitle,
    'big-statement': buildBigStatement,
    'stat-grid': buildStatGrid,
    'capability-cards': buildCapabilityCards,
    'arch-layers': buildArchLayers,
    'flow-trace': buildFlowTrace,
    'journey': buildJourney,
    'outro': buildOutro,
    reducedMotion: REDUCED,
    sourceChip: sourceChip,
    clean: clean,
    rise: rise
  };
})();
