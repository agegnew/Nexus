/*
 * Draws the slides the .pptx contains, from the same list of positioned shapes the
 * writer converts into OOXML.
 *
 * This file decides nothing about layout. Every x, y, width, height, size and colour
 * arrives already settled from DeckGeometry on the Kotlin side, and all this does is
 * turn each shape into a div. That is the whole point: the film once shipped with an
 * outro whose Kotlin wrote {headline, sub} while its player read {cta, repoUrl}, so the
 * last scene of every film quietly showed a hardcoded string. Two renderers that each
 * work out their own positions would be the same failure waiting to happen, so only one
 * of them works anything out.
 */
window.DeckPreview = (function () {
  'use strict';

  /* Mirrors DeckTheme.W and DeckTheme.H. A 1280x720 space is exactly a 16:9 slide. */
  var W = 1280;
  var H = 720;

  function el(tag, cls) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    return node;
  }

  function px(value) {
    return (value || 0) + 'px';
  }

  function place(node, shape) {
    node.style.left = px(shape.x);
    node.style.top = px(shape.y);
    node.style.width = px(shape.w);
    node.style.height = px(shape.h);
  }

  function box(shape) {
    var node = el('div', 'sh sh--box');
    place(node, shape);
    node.style.background = '#' + shape.fill;
    if (shape.roundPct) {
      // OOXML rounds by a percentage of the short side, so the same number has to be
      // resolved against the same side here or a pill would come out a rectangle.
      node.style.borderRadius = px(Math.min(shape.w, shape.h) * shape.roundPct / 100);
    }
    if (shape.stroke) node.style.boxShadow = 'inset 0 0 0 ' + px(shape.strokeWidth || 1) + ' #' + shape.stroke;
    return node;
  }

  function label(shape) {
    var node = el('div', 'sh sh--text');
    place(node, shape);
    node.style.color = '#' + shape.color;
    node.style.fontSize = px(shape.sizePx);
    node.style.fontWeight = shape.bold ? '700' : '400';
    node.style.lineHeight = String((shape.linePct || 118) / 100);
    node.style.fontFamily = shape.mono ? 'Consolas, monospace' : 'Arial, Helvetica, sans-serif';
    if (shape.spacing) node.style.letterSpacing = px(shape.spacing / 75);
    node.style.textAlign = (shape.align || 'LEFT').toLowerCase().replace('center', 'center');
    node.style.justifyContent =
      shape.anchor === 'MIDDLE' ? 'center' : shape.anchor === 'BOTTOM' ? 'flex-end' : 'flex-start';
    (shape.lines || []).forEach(function (line, i) {
      var p = el('p');
      if (i > 0 && shape.gapPx) p.style.marginTop = px(shape.gapPx);
      p.textContent = line;
      node.appendChild(p);
    });
    return node;
  }

  function pic(shape) {
    var node = el('div', 'sh sh--pic');
    place(node, shape);
    node.style.backgroundImage = 'url("icons/' + shape.icon + '.png")';
    return node;
  }

  function slide(art, index) {
    var thumb = el('div', 'thumb');
    var frame = el('div', 'thumb__frame');
    var stage = el('div', 'thumb__stage');

    (art.shapes || []).forEach(function (shape) {
      if (shape.kind === 'box') stage.appendChild(box(shape));
      else if (shape.kind === 'text') stage.appendChild(label(shape));
      else if (shape.kind === 'pic') stage.appendChild(pic(shape));
    });

    frame.appendChild(stage);
    thumb.appendChild(frame);

    var cap = el('div', 'thumb__cap');
    var no = el('span', 'thumb__no');
    no.textContent = (index + 1) < 10 ? '0' + (index + 1) : String(index + 1);
    var name = el('span', 'thumb__name');
    name.textContent = art.title || '';
    // The speaker notes are the deck's reason for existing, so they are at least
    // reachable from the preview even though there is no room to print them.
    if (art.notes) thumb.title = art.notes;
    cap.appendChild(no);
    cap.appendChild(name);
    thumb.appendChild(cap);
    return { el: thumb, frame: frame, stage: stage };
  }

  /** Scales every stage to whatever width its frame ended up with. */
  function fit(thumbs) {
    thumbs.forEach(function (thumb) {
      var width = thumb.frame.clientWidth;
      if (!width) return;
      thumb.stage.style.transform = 'scale(' + (width / W) + ')';
    });
  }

  function render(host, slides) {
    host.textContent = '';
    var thumbs = (slides || []).map(function (art, i) {
      var made = slide(art, i);
      host.appendChild(made.el);
      return made;
    });
    fit(thumbs);
    return thumbs;
  }

  return { render: render, fit: fit, SIZE: { w: W, h: H } };
})();
