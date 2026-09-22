/*
 * The date-range control, shared by the Reel tab and the Deck tab.
 *
 * Both tabs answer the same question, "what did we do between these two dates", so
 * "last week" has to mean one thing in this plugin. It used to live inlined in the
 * player's bridge; the moment the deck needed it too, a second copy would have been a
 * second definition of Monday, and the two tabs would have disagreed the first time a
 * period was edited in only one of them.
 *
 * Served from its own root at /shared/scope.js so both pages can load it by absolute
 * path. Plain script, no module: the rest of this runtime is plain scripts, and the
 * film's standalone export inlines its scripts by name.
 *
 * The page owns the markup. This owns the meaning. Both pages use the same element ids,
 * and anything missing is simply skipped, so a page may offer a subset of the controls.
 *
 * ### One control, not two
 *
 * There used to be a pair of radio buttons choosing between "the product" and "a progress
 * update", and then, underneath and only sometimes visible, a period. That is two
 * questions for what a person experiences as one: how far back do you want to go. So the
 * whole project is now simply the widest choice in the same row as last week, and the
 * mode is derived from the period rather than asked for separately.
 */
window.NexusScope = (function () {
  'use strict';

  function el(id) { return document.getElementById(id); }

  /** yyyy-MM-dd in the viewer's own zone, which is the zone DateRange reads git in. */
  function isoDay(date) {
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  }

  /** Monday, because a working week is what a person means by "this week". */
  function startOfWeek(date) {
    var start = new Date(date);
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
    return start;
  }

  function shiftDays(date, days) {
    var next = new Date(date);
    next.setDate(next.getDate() + days);
    return next;
  }

  /** The presets, matching the ones the Activity tab offers. Null means "custom". */
  function periodDates(id) {
    var today = new Date();
    switch (id) {
      case 'this-week': return [startOfWeek(today), today];
      case 'last-week': return [shiftDays(startOfWeek(today), -7), shiftDays(startOfWeek(today), -1)];
      case 'this-month': return [new Date(today.getFullYear(), today.getMonth(), 1), today];
      case 'last-month': return [
        new Date(today.getFullYear(), today.getMonth() - 1, 1),
        new Date(today.getFullYear(), today.getMonth(), 0)
      ];
      case 'last-7': return [shiftDays(today, -6), today];
      case 'last-30': return [shiftDays(today, -29), today];
      case 'last-90': return [shiftDays(today, -89), today];
      default: return null;
    }
  }

  /**
   * Which of the two things the tab is being asked for.
   *
   * Read off the checked radio inside #scope rather than by group name, so the two pages
   * are free to name their radios after the thing they produce.
   */
  function mode() {
    return chosen() === WHOLE ? 'launch' : 'recap';
  }

  /** The widest period there is: no dates at all, the project as it stands. */
  var WHOLE = 'whole';

  /**
   * The period, which is now the only thing the panel asks.
   *
   * Held in a hidden select rather than in a variable so that a page can still be driven
   * without JavaScript having run, and so the value survives the panel being rebuilt when
   * the project's areas arrive.
   */
  function chosen() {
    var period = el('scope-period');
    return period ? period.value : WHOLE;
  }

  function choose(value) {
    var period = el('scope-period');
    if (!period) return;
    period.value = value;
    // Written by us, so it does not bubble on its own.
    period.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function isRecap() { return mode() === 'recap'; }

  function range() {
    var pick = chosen();
    if (pick === WHOLE) return { since: '', until: '' };
    if (pick === 'custom') {
      var from = el('scope-from');
      var to = el('scope-to');
      return { since: (from && from.value) || '', until: (to && to.value) || '' };
    }
    var pair = periodDates(pick);
    return pair ? { since: isoDay(pair[0]), until: isoDay(pair[1]) } : { since: '', until: '' };
  }

  /* ------------------------------------------------------------------ calendar */

  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];
  var DAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

  /** Which month the calendar is showing. Only ever moved by the arrows. */
  var showing = null;
  /** The first click of a new range, held until the second lands. */
  var pending = null;

  function spoken(iso) {
    if (!iso) return '';
    var parts = iso.split('-');
    return Number(parts[2]) + ' ' + MONTHS[Number(parts[1]) - 1] + ' ' + parts[0];
  }

  function daysBetween(a, b) {
    return Math.round((new Date(b) - new Date(a)) / 86400000) + 1;
  }

  /**
   * Two months of clickable days.
   *
   * Two rather than one because a range that crosses a month boundary is the common case,
   * and paging back and forth to place the second end of it is where a one month picker
   * becomes annoying. The first click sets the start and clears the end; the second sets
   * the end, and clicking earlier than the start starts again rather than producing a
   * backwards range nobody meant.
   */
  function drawCalendar() {
    var host = el('scope-cal');
    if (!host) return;
    var current = range();
    var start = pending || current.since;
    var end = pending ? '' : current.until;

    if (!showing) {
      var anchor = start ? new Date(start + 'T00:00:00') : new Date();
      showing = new Date(anchor.getFullYear(), anchor.getMonth() - 1, 1);
    }

    host.textContent = '';
    for (var m = 0; m < 2; m++) {
      var month = new Date(showing.getFullYear(), showing.getMonth() + m, 1);
      host.appendChild(drawMonth(month, start, end, m));
    }
  }

  function drawMonth(month, start, end, index) {
    var wrap = document.createElement('div');
    wrap.className = 'cal';

    var head = document.createElement('div');
    head.className = 'cal__head';
    if (index === 0) head.appendChild(arrowButton('\u2039', -1));
    var name = document.createElement('span');
    name.className = 'cal__name';
    name.textContent = MONTHS[month.getMonth()] + ' ' + month.getFullYear();
    head.appendChild(name);
    if (index === 1) head.appendChild(arrowButton('\u203A', 1));
    wrap.appendChild(head);

    var grid = document.createElement('div');
    grid.className = 'cal__grid';
    DAYS.forEach(function (d) {
      var cell = document.createElement('span');
      cell.className = 'cal__dow';
      cell.textContent = d;
      grid.appendChild(cell);
    });

    // Monday first, matching startOfWeek, so the columns mean the same thing everywhere.
    var lead = (new Date(month.getFullYear(), month.getMonth(), 1).getDay() + 6) % 7;
    for (var i = 0; i < lead; i++) grid.appendChild(document.createElement('span'));

    var today = isoDay(new Date());
    var last = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
    for (var day = 1; day <= last; day++) {
      var iso = isoDay(new Date(month.getFullYear(), month.getMonth(), day));
      var cell = document.createElement('button');
      cell.type = 'button';
      cell.className = 'cal__day';
      cell.textContent = String(day);
      cell.setAttribute('data-iso', iso);
      // A range that has not happened yet has no commits in it.
      if (iso > today) cell.disabled = true;
      if (iso === start) cell.setAttribute('data-edge', 'start');
      if (end && iso === end) cell.setAttribute('data-edge', 'end');
      if (start && end && iso > start && iso < end) cell.setAttribute('data-in', 'true');
      if (iso === today) cell.setAttribute('data-today', 'true');
      grid.appendChild(cell);
    }
    wrap.appendChild(grid);
    return wrap;
  }

  function arrowButton(glyph, by) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'cal__arrow';
    button.textContent = glyph;
    button.setAttribute('data-move', String(by));
    button.setAttribute('aria-label', by < 0 ? 'Earlier months' : 'Later months');
    return button;
  }

  /** A day was clicked. Returns true when the range changed and callers should refresh. */
  function pickDay(iso) {
    var from = el('scope-from');
    var to = el('scope-to');
    if (!from || !to) return false;
    if (!pending || iso < pending) {
      pending = iso;
      from.value = iso;
      to.value = '';
    } else {
      from.value = pending;
      to.value = iso;
      pending = null;
    }
    return true;
  }

  /* ------------------------------------------------------------------- refresh */

  /** Shows the range controls only when they apply, and spells out the dates in words. */
  function refresh() {
    var pick = chosen();
    var custom = pick === 'custom';

    var chips = el('scope-chips');
    if (chips) {
      var buttons = chips.querySelectorAll('[data-period]');
      for (var i = 0; i < buttons.length; i++) {
        buttons[i].setAttribute('aria-pressed', buttons[i].getAttribute('data-period') === pick ? 'true' : 'false');
      }
    }

    // The filters only mean something for a period, so they are not offered for the
    // whole project, where "only my commits" would quietly hide most of the codebase.
    var filters = el('scope-filters');
    if (filters) filters.hidden = pick === WHOLE;

    var cal = el('scope-calendar');
    if (cal) cal.hidden = !custom;
    if (custom) drawCalendar();

    var echo = el('scope-dates');
    if (echo) {
      var current = range();
      if (pick === WHOLE) {
        echo.textContent = 'Everything in the project as it stands today.';
      } else if (pending) {
        echo.textContent = 'From ' + spoken(pending) + '. Now pick the last day.';
      } else if (current.since && current.until) {
        echo.textContent = spoken(current.since) + ' to ' + spoken(current.until) +
          '  \u00b7  ' + daysBetween(current.since, current.until) + ' days';
      } else {
        echo.textContent = 'Pick the first day of the period.';
      }
    }
  }

  /**
   * What the IDE is sent. The field names are the ones ReelScope reads, so the same
   * object serves both tabs' bridges without either of them translating it.
   */
  function payload() {
    if (!isRecap()) return { scope: 'launch' };
    var current = range();
    var area = el('scope-area');
    var mine = el('scope-mine');
    var uncommitted = el('scope-uncommitted');
    return {
      scope: 'recap',
      since: current.since,
      until: current.until,
      area: area ? area.value : 'all',
      mine: mine ? mine.checked : true,
      uncommitted: uncommitted ? uncommitted.checked : true
    };
  }

  /**
   * An identity for one built thing, so a cached result is never replayed for a range it
   * was not built from. A recap of last week is not the recap of last month, and keying
   * on the audience alone would hand back the wrong one.
   */
  function key(prefix, made) {
    var p = made || payload();
    return p.scope === 'launch'
      ? prefix + ':launch'
      : prefix + ':recap:' + p.since + ':' + p.until + ':' + p.area + ':' + p.mine + ':' + p.uncommitted;
  }

  /** One line for a status message, in the words the user picked rather than a mode name. */
  function describe() {
    if (!isRecap()) return 'the whole project';
    var current = range();
    return current.since && current.until ? current.since + ' to ' + current.until : 'the chosen period';
  }

  /** The areas come from the project's own modules, so they are asked for, not hard-coded. */
  function fillAreas(areas) {
    var select = el('scope-area');
    if (!select || !areas || !areas.length) return;
    var previous = select.value;
    select.innerHTML = '';
    areas.forEach(function (area) {
      var option = document.createElement('option');
      option.value = area.id;
      option.textContent = area.label;
      select.appendChild(option);
    });
    if (previous) select.value = previous;
    if (!select.value) select.value = 'all';
  }

  /**
   * Wires the panel to itself. Delegated from the document because the fields are
   * rebuilt when the project's areas arrive, and a listener bound to a replaced <option>
   * would be listening to an element nobody can reach.
   */
  function watch(onChange) {
    function changed() {
      refresh();
      if (onChange) onChange();
    }

    document.addEventListener('change', function (event) {
      if (!event.target.closest || !event.target.closest('#scope')) return;
      changed();
    });

    /*
     * Clicks, for the three controls that are buttons rather than fields: the period
     * chips, the calendar's days and its month arrows. Delegated from the document for
     * the same reason the change handler is, and more so here, because the calendar is
     * rebuilt from scratch every time the range moves.
     */
    document.addEventListener('click', function (event) {
      var target = event.target;
      if (!target.closest || !target.closest('#scope')) return;

      var chip = target.closest('[data-period]');
      if (chip) {
        event.preventDefault();
        // Reopening the calendar starts a fresh range rather than resuming a half
        // finished one the reader has long since forgotten about.
        if (chip.getAttribute('data-period') === 'custom') { pending = null; showing = null; }
        choose(chip.getAttribute('data-period'));
        return;
      }

      var move = target.closest('[data-move]');
      if (move) {
        event.preventDefault();
        showing = new Date(showing.getFullYear(), showing.getMonth() + Number(move.getAttribute('data-move')), 1);
        drawCalendar();
        return;
      }

      var day = target.closest('[data-iso]');
      if (day && !day.disabled) {
        event.preventDefault();
        if (pickDay(day.getAttribute('data-iso'))) changed();
      }
    });

    refresh();
  }

  return {
    WHOLE: WHOLE,
    isoDay: isoDay,
    spoken: spoken,
    choose: choose,
    periodDates: periodDates,
    mode: mode,
    isRecap: isRecap,
    range: range,
    refresh: refresh,
    payload: payload,
    key: key,
    describe: describe,
    fillAreas: fillAreas,
    watch: watch
  };
})();
