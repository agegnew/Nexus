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
 *
 * It draws the same slide at two sizes: small in the rail, large in the view. Both go
 * through `paint`, so a thumbnail can never disagree with the slide it is a thumbnail of.
 */
window.DeckPreview = (function () {
  'use strict';

  /* Mirrors DeckTheme.W and DeckTheme.H. A 1280x720 space is exactly a 16:9 slide. */
  var W = 1280;
  var H = 720;

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
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
    node.style.textAlign = (shape.align || 'LEFT').toLowerCase();
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

  /** One slide's shapes onto one 1280x720 stage. The only place a shape becomes a node. */
  function paint(stage, art) {
    stage.textContent = '';
    (art.shapes || []).forEach(function (shape) {
      if (shape.kind === 'box') stage.appendChild(box(shape));
      else if (shape.kind === 'text') stage.appendChild(label(shape));
      else if (shape.kind === 'pic') stage.appendChild(pic(shape));
    });
  }

  /** Scales a 1280 wide stage down to whatever width its frame ended up with. */
  function fitOne(frame, stage) {
    var width = frame.clientWidth;
    if (!width) return;
    stage.style.transform = 'scale(' + (width / W) + ')';
  }

  function number(index) {
    return (index + 1) < 10 ? '0' + (index + 1) : String(index + 1);
  }

  /* ------------------------------------------------------------------ the rail */

  /**
   * The sidebar: every slide small, in order, each one a button.
   *
   * Buttons rather than divs with click handlers, because this is the deck's navigation
   * and somebody driving the IDE from the keyboard has to be able to reach it.
   */
  function rail(host, slides, onPick) {
    host.textContent = '';
    var items = (slides || []).map(function (art, i) {
      var item = el('button', 'rail__item');
      item.type = 'button';
      item.setAttribute('data-index', String(i));
      item.setAttribute('aria-label', 'Slide ' + (i + 1) + (art.title ? ', ' + art.title : ''));

      var frame = el('div', 'rail__frame');
      var stage = el('div', 'rail__stage');
      paint(stage, art);
      frame.appendChild(stage);

      var cap = el('div', 'rail__cap');
      cap.appendChild(el('span', 'rail__no', number(i)));
      cap.appendChild(el('span', 'rail__name', art.title || ''));

      item.appendChild(frame);
      item.appendChild(cap);
      item.addEventListener('click', function () { onPick(i); });
      host.appendChild(item);
      return { el: item, frame: frame, stage: stage };
    });
    return items;
  }

  /* ----------------------------------------------------------------- the notes */

  /**
   * Every slide's notes, in order, each one labelled with the slide it belongs to.
   *
   * The notes are the one thing a deck has that a film does not: they are what the person
   * standing in front of the room actually says. Printing them all in order means the deck
   * can be rehearsed from this panel without clicking through twenty slides, and the
   * current one is marked rather than being the only one shown.
   */
  function notes(host, slides, onPick) {
    host.textContent = '';
    var items = (slides || []).map(function (art, i) {
      var item = el('article', 'note');
      item.setAttribute('data-index', String(i));

      var head = el('button', 'note__head');
      head.type = 'button';
      head.appendChild(el('span', 'note__no', number(i)));
      head.appendChild(el('span', 'note__title', art.title || 'Slide ' + (i + 1)));
      head.addEventListener('click', function () { onPick(i); });

      var body = el('p', 'note__body', art.notes || 'No notes for this slide.');
      if (!art.notes) body.classList.add('note__body--empty');

      item.appendChild(head);
      item.appendChild(body);
      host.appendChild(item);
      return item;
    });
    return items;
  }

  return {
    paint: paint,
    fitOne: fitOne,
    rail: rail,
    notes: notes,
    number: number,
    SIZE: { w: W, h: H }
  };
})();
