/*
 * Draws the trust verdict the plugin computed.
 *
 * This file decides nothing about what is true. Every count, every rectangle and every colour
 * arrives already settled from TrustPayload on the Kotlin side, which lays the treemap out with
 * the same Squarify the Swing tab used and resolves the colour scale with the same TrustColors.
 * That is deliberate: the picture's whole pitch is that it only claims what a machine can back
 * up, and a second implementation of the geometry would be a second answer to "where is this
 * file" waiting to disagree with the first.
 *
 * What this file does own: hit testing, the tooltip, hatching a dead cell, and asking again
 * when the box changes size or the report changes on disk.
 */
(function () {
  'use strict';

  /* Matches TrustTreemap, which this replaces. Below this a box has no room for a name or a
     click, so a folder that small is named in the caption instead of being inflated. */
  var MIN_LABEL = 26;

  /* A cell thinner than this in either direction cannot carry a border that reads, so it is
     filled and left alone rather than drawn with chrome wider than the cell. */
  var MIN_CHROME = 3;

  var dom = {};
  var model = null;
  var selected = null;
  var pending = 0;
  var asked = '';
  var busy = false;

  function byId(id) { return document.getElementById(id); }

  function cache() {
    ['hero-number', 'hero-said', 'hero-sub', 'run', 'paint', 'dead-only', 'filter',
     'filter-text', 'show-all', 'empty', 'empty-head', 'empty-body', 'empty-cmd', 'data',
     'strip', 'map', 'tip', 'legend-scale', 'undrawn', 'list-head', 'list', 'source']
      .forEach(function (id) {
        dom[id.replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); })] = byId(id);
      });
  }

  /* ---------------------------------------------------------------- talking to the IDE */

  /**
   * Asks for the model, laid out for the box the picture currently has.
   *
   * The size is measured rather than assumed, and [asked] remembers what was sent, because the
   * first call happens while the whole data section is still hidden. That measures zero, the
   * layout comes back with every folder below the fourteen pixel floor, and the picture is
   * empty with a caption saying nothing could be drawn. The observer below catches the reveal
   * and asks again; this is what makes the second answer replace the first rather than race it.
   */
  function ask() {
    var box = dom.map.parentElement.getBoundingClientRect();
    var w = Math.round(box.width);
    var h = Math.round(box.height);
    asked = w + 'x' + h;
    var query = '?w=' + w + '&h=' + h + (dom.deadOnly.checked ? '&dead=1' : '');
    fetch('/trust/model.json' + query, { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (data) { model = data; render(); })
      .catch(function () { /* The IDE is not there. The panel says so through its empty state. */ });
  }

  /** Coalesced, because dragging the tool window edge fires this on every frame. */
  function askSoon() {
    window.clearTimeout(pending);
    pending = window.setTimeout(ask, 90);
  }

  /** Only when the box is genuinely a different size, so a redraw cannot loop through itself. */
  function askIfResized() {
    var box = dom.map.parentElement.getBoundingClientRect();
    if (Math.round(box.width) + 'x' + Math.round(box.height) !== asked) askSoon();
  }

  /* ------------------------------------------------------------------------- the words */

  function commas(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }

  function plural(n, word) { return n === 1 ? word : word + 's'; }

  /**
   * The headline.
   *
   * Two wordings, because in dead-only mode a percentage would be 100% every time and say
   * nothing. What the reader wants there is the size of the pile.
   */
  function hero() {
    var t = model.totals;
    if (dom.deadOnly.checked) {
      dom.heroNumber.textContent = commas(t.neverRun);
      dom.heroNumber.style.color = '#6A6A76';
      dom.heroSaid.textContent = t.neverRun === 1 ? 'line of dead code' : 'lines of dead code';
      dom.heroSub.textContent = t.deadFiles + ' ' + plural(t.deadFiles, 'file') +
        ' · nothing imports them and nothing has ever run them';
      return;
    }
    dom.heroNumber.textContent = t.percent + '%';
    dom.heroNumber.style.color = t.colour;
    dom.heroSaid.textContent =
      t.percent > 60 ? 'of this codebase has never executed' :
      t.percent > 25 ? 'of this codebase still has never executed' :
      t.percent > 0 ? 'left, and it is mostly edge cases' :
      'everything known here has run at least once';
    // One sentence tying the three counts together, because "40 files" up here and "34 files"
    // in the list with nothing between them reads as a bug.
    var sub = commas(t.neverRun) + ' of ' + commas(t.lines) + ' lines never run' +
      ' · ' + t.filesAffected + ' of ' + t.filesKnown + ' files affected';
    if (t.deadFiles > 0) sub += ' · ' + t.deadFiles + ' dead';
    dom.heroSub.textContent = sub;
  }

  /* -------------------------------------------------------------------------- the bars */

  function strip() {
    dom.strip.textContent = '';
    (model.layers || []).forEach(function (layer) {
      var row = document.createElement('button');
      row.type = 'button';
      row.className = 'strip__row';
      row.setAttribute('aria-pressed', String(selected === layer.folder));
      row.title = layer.folder + ' · ' + commas(layer.lines) + ' ' + plural(layer.lines, 'line');

      var name = document.createElement('span');
      name.className = 'strip__name';
      name.textContent = layer.name;

      var track = document.createElement('span');
      track.className = 'strip__track';
      var fill = document.createElement('span');
      fill.className = 'strip__fill';
      fill.style.width = layer.percent + '%';
      fill.style.background = layer.colour;
      track.appendChild(fill);

      var pct = document.createElement('span');
      pct.className = 'strip__pct';
      pct.textContent = layer.percent + '%';

      row.appendChild(name);
      row.appendChild(track);
      row.appendChild(pct);
      row.addEventListener('click', function () {
        selected = selected === layer.folder ? null : layer.folder;
        render();
      });
      dom.strip.appendChild(row);
    });
  }

  /* ----------------------------------------------------------------------- the picture */

  /**
   * Desaturated and darkened, so an unselected folder recedes without vanishing.
   *
   * Towards grey rather than towards the background: dimming a red by mixing in the page
   * leaves a pale red, and a screen of pale reds and pale ambers is mud. Grey steps aside and
   * lets the chosen folder be the only colour on screen. Copied from the Swing original so
   * the two pictures fade the same way.
   */
  function fade(hex) {
    var n = parseInt(hex.slice(1), 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    var luminance = 0.299 * r + 0.587 * g + 0.114 * b;
    var grey = luminance * 0.55 + 40 * 0.45;
    function mix(c) { return Math.min(255, Math.max(0, Math.round(c * 0.15 + grey * 0.85))); }
    return 'rgb(' + mix(r) + ',' + mix(g) + ',' + mix(b) + ')';
  }

  function paintMap() {
    var canvas = dom.map;
    var box = canvas.parentElement.getBoundingClientRect();
    var ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(box.width * ratio);
    canvas.height = Math.round(box.height * ratio);

    var g = canvas.getContext('2d');
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.clearRect(0, 0, box.width, box.height);

    if (!(model.cells || []).length) {
      g.fillStyle = '#79859A';
      g.font = '13px ' + uiFont();
      g.textAlign = 'center';
      g.fillText('No execution data yet', box.width / 2, box.height / 2);
      g.textAlign = 'left';
      return;
    }

    (model.groups || []).forEach(function (group) {
      g.fillStyle = '#E4E6EB';
      g.fillRect(group.x, group.y, group.w, group.h);
    });

    (model.cells || []).forEach(function (cell) {
      var dim = selected !== null && cell.folder !== selected;
      body(g, cell, dim);
    });

    (model.groups || []).forEach(function (group) { header(g, group); });
  }

  function body(g, cell, dim) {
    var x = cell.x, y = cell.y, w = cell.w, h = cell.h;

    if (cell.dead) {
      g.fillStyle = dim ? fade('#9A9AA4') : '#9A9AA4';
      g.fillRect(x, y, w, h);
      hatch(g, x, y, w, h);
    } else {
      g.fillStyle = dim ? fade(cell.colour) : cell.colour;
      g.fillRect(x, y, w, h);
    }

    if (w < MIN_CHROME || h < MIN_CHROME) return;

    g.save();
    g.lineWidth = 1;
    if (cell.dead) {
      g.setLineDash([3, 3]);
      g.strokeStyle = dim ? 'rgba(106,106,118,.45)' : '#6A6A76';
    } else {
      g.strokeStyle = 'rgba(0,0,0,.45)';
    }
    g.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
    g.restore();

    label(g, cell, x, y, w, h);
  }

  /** 45 degree white hatching, clipped to the cell. Unclipped, a tall dead box used to smear
      stripes across every healthy file beside it, which is the picture claiming something the
      data never said. */
  function hatch(g, x, y, w, h) {
    g.save();
    g.beginPath();
    g.rect(x, y, w, h);
    g.clip();
    g.strokeStyle = 'rgba(255,255,255,.55)';
    g.lineWidth = 1;
    for (var i = -h; i < w; i += 7) {
      g.beginPath();
      g.moveTo(x + i, y + h);
      g.lineTo(x + i + h, y);
      g.stroke();
    }
    g.restore();
  }

  function label(g, cell, x, y, w, h) {
    if (w < MIN_LABEL || h < 16) return;
    var text = clip(g, cell.name, w - 8, '10.5px ' + uiFont());
    if (!text) return;
    // Dark behind light, so a name stays readable on both the green end of the scale and the
    // red end without a second colour decision per cell.
    g.font = '10.5px ' + uiFont();
    g.fillStyle = 'rgba(0,0,0,.65)';
    g.fillText(text, x + 5, y + 13);
    g.fillStyle = '#FFFFFF';
    g.fillText(text, x + 4, y + 12);

    if (h >= 30 && w >= 44) {
      var corner = cell.dead ? 'dead' : cell.percent + '%';
      g.fillStyle = 'rgba(0,0,0,.65)';
      g.fillText(corner, x + 5, y + h - 5);
      g.fillStyle = '#FFFFFF';
      g.fillText(corner, x + 4, y + h - 6);
    }
  }

  function header(g, group) {
    g.save();
    g.beginPath();
    g.rect(group.x, group.y, group.w, group.h);
    g.clip();

    g.strokeStyle = selected === group.folder ? '#2F2F33' : '#D8D8DC';
    g.lineWidth = selected === group.folder ? 2 : 1;
    g.strokeRect(group.x + 1, group.y + 1, group.w - 2, group.h - 2);

    g.font = '700 10px ' + uiFont();
    var text = clip(g, group.name, group.w - 8, '700 10px ' + uiFont());
    if (text) {
      g.fillStyle = '#3C3F43';
      g.fillText(text, group.x + 4, group.y + 11);
    }
    g.restore();
  }

  function uiFont() {
    return '"Segoe UI Variable", "Segoe UI", system-ui, sans-serif';
  }

  /** Two letters and an ellipsis reads as a glitch, not a name; the tooltip has the whole one. */
  function clip(g, text, available, font) {
    g.font = font;
    if (g.measureText(text).width <= available) return text;
    var cut = text;
    while (cut.length && g.measureText(cut + '…').width > available) cut = cut.slice(0, -1);
    return cut.length < 3 ? '' : cut + '…';
  }

  /* ---------------------------------------------------------------------- hit testing */

  function cellAt(x, y) {
    var cells = model && model.cells ? model.cells : [];
    for (var i = cells.length - 1; i >= 0; i--) {
      var c = cells[i];
      if (x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + c.h) return c;
    }
    return null;
  }

  function onMove(event) {
    var box = dom.map.getBoundingClientRect();
    var cell = cellAt(event.clientX - box.left, event.clientY - box.top);
    if (!cell) {
      dom.tip.hidden = true;
      return;
    }
    var verdict = cell.dead ? 'dead: nothing imports it and nothing ever ran it'
      : cell.changed ? 'changed since the run, so this answer is out of date'
      : 'never run';
    dom.tip.textContent = '';
    var name = document.createElement('b');
    name.textContent = cell.path;
    dom.tip.appendChild(name);
    dom.tip.appendChild(document.createTextNode(
      cell.neverRun + ' of ' + cell.lines + ' lines ' + verdict));
    dom.tip.hidden = false;
    // Kept inside the picture, so a cell near the right edge does not push the tip off it.
    var tip = dom.tip.getBoundingClientRect();
    var left = Math.min(event.clientX - box.left + 12, box.width - tip.width - 4);
    var top = Math.min(event.clientY - box.top + 14, box.height - tip.height - 4);
    dom.tip.style.left = Math.max(0, left) + 'px';
    dom.tip.style.top = Math.max(0, top) + 'px';
  }

  /* --------------------------------------------------------------------------- the list */

  function list() {
    var rows = (model.files || []).filter(function (f) {
      return selected === null || f.folder === selected;
    });

    var deadOnly = dom.deadOnly.checked;
    dom.listHead.textContent =
      deadOnly && selected !== null ? 'Dead files in ' + selected + ' (' + rows.length + ')' :
      deadOnly ? 'Dead files (' + rows.length + ')' :
      selected !== null ? 'Files with never-run code in ' + selected + ' (' + rows.length + ')' :
      'Files with never-run code (' + rows.length + ')';

    dom.filter.hidden = selected === null;
    dom.filterText.textContent = selected === null ? '' : 'Narrowed to ' + selected + '. ';

    dom.list.textContent = '';
    if (!rows.length) {
      var none = document.createElement('li');
      none.className = 'list__empty';
      none.textContent = 'Nothing unproven here';
      dom.list.appendChild(none);
      return;
    }

    rows.forEach(function (file) {
      var item = document.createElement('li');
      var row = document.createElement('button');
      row.type = 'button';
      row.className = 'list__row';
      row.title = file.path;

      var name = document.createElement('span');
      name.className = 'list__name';
      name.textContent = file.path;
      row.appendChild(name);

      if (file.dead) {
        var tag = document.createElement('span');
        tag.className = 'list__tag';
        tag.textContent = 'DEAD';
        row.appendChild(tag);
      }

      var count = document.createElement('span');
      count.className = 'list__count';
      count.textContent = file.neverRun + ' never run';
      row.appendChild(count);

      if (!file.dead) {
        var of = document.createElement('span');
        of.className = 'list__of';
        of.textContent = 'of ' + file.lines + '  (' + file.percent + '%)';
        row.appendChild(of);
      }

      if (file.changed) {
        var changed = document.createElement('span');
        changed.className = 'list__changed';
        changed.textContent = 'changed since the run';
        row.appendChild(changed);
      }

      item.appendChild(row);
      dom.list.appendChild(item);
    });
  }

  /* ------------------------------------------------------------------------- rendering */

  function render() {
    if (!model) return;

    dom.paint.checked = Boolean(model.paint);
    dom.source.textContent =
      !model.source ? 'No coverage report found in this project yet.'
      : model.placeholder ? model.source + '  (not a real run yet)'
      : model.source;

    var command = model.command;
    // Labelled for what it is rather than for what it wishes it were. The Swing tab said
    // "Set up coverage" with an ellipsis here and opened a dialog on click; a disabled button
    // with an ellipsis promises a next step it cannot take, and the instructions for taking it
    // are already on the card below.
    dom.run.textContent = busy ? 'Running…' : command ? 'Run with coverage' : 'No coverage command';
    dom.run.disabled = busy || !command;
    dom.run.title = command
      ? command.origin + ': ' + command.text
      : 'Nexus could not guess how to run this project with coverage. Name the command in ' +
        '.nexus/trust.json at the project root, as { "command": "..." }.';

    if (!model.ready) {
      dom.empty.hidden = false;
      dom.data.hidden = true;
      dom.heroNumber.textContent = '-';
      dom.heroNumber.style.color = '';
      dom.heroSaid.textContent = 'No execution data for this project';
      dom.heroSub.textContent = 'Nothing here has been measured yet';
      dom.emptyBody.textContent = command
        ? 'This panel paints the code that has never been executed. Press Run with coverage above. It runs this in the Run tool window, and this fills in when it finishes.'
        : 'This panel paints the code that has never been executed. It needs one run of the project with coverage on, and it does not know the command for this project. Put one in .nexus/trust.json at the project root, as { "command": "..." }. Any command that writes coverage.xml, lcov.info or a JaCoCo report will do, and running it in a terminal works too: the file is watched.';
      dom.emptyCmd.hidden = !command;
      if (command) dom.emptyCmd.textContent = command.text;
      return;
    }

    dom.empty.hidden = true;
    dom.data.hidden = false;

    hero();
    strip();
    paintMap();
    list();

    dom.legendScale.style.background = 'linear-gradient(90deg, ' +
      (model.scale || []).map(function (stop) { return stop.colour; }).join(', ') + ')';

    var hidden = model.hidden || [];
    dom.undrawn.hidden = hidden.length === 0;
    dom.undrawn.textContent = 'Not drawn, too small: ' + hidden.map(function (h) {
      return h.name + ' (' + commas(h.lines) + ' ' + plural(h.lines, 'line') + ')';
    }).join(', ');
  }

  /* ----------------------------------------------------------------------------- wiring */

  function start() {
    cache();

    dom.run.addEventListener('click', function () {
      if (dom.run.disabled) return;
      busy = true;
      render();
      fetch('/trust/run', { method: 'POST' })
        .catch(function () {})
        // The result does not come back through this reply. The report file is watched, so
        // whatever the run produces arrives the same way a terminal run's would.
        .finally(function () { window.setTimeout(function () { busy = false; ask(); }, 1200); });
    });

    dom.paint.addEventListener('change', function () {
      fetch('/trust/paint', { method: 'POST' })
        .then(function (r) { return r.json(); })
        .then(function (body) { dom.paint.checked = Boolean(body.paint); })
        .catch(function () {});
    });

    dom.deadOnly.addEventListener('change', function () { selected = null; ask(); });
    dom.showAll.addEventListener('click', function () { selected = null; render(); });

    dom.map.addEventListener('mousemove', onMove);
    dom.map.addEventListener('mouseleave', function () { dom.tip.hidden = true; });
    dom.map.addEventListener('click', function (event) {
      var box = dom.map.getBoundingClientRect();
      var cell = cellAt(event.clientX - box.left, event.clientY - box.top);
      selected = cell && selected !== cell.folder ? cell.folder : null;
      render();
    });

    // Watches the picture itself rather than the window: the size that matters changes when
    // the data section is revealed and when the file list grows, neither of which is a resize.
    if (window.ResizeObserver) new ResizeObserver(askIfResized).observe(dom.map.parentElement);
    else window.addEventListener('resize', askSoon);
    // The report is watched on the IDE side, so a run from a terminal changes the answer with
    // nothing pressed here. Asking on a slow beat is what turns that into a redraw.
    window.setInterval(ask, 4000);
    ask();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
