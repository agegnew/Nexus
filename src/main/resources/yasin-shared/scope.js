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
    var host = el('scope');
    var checked = host && host.querySelector('input[type="radio"]:checked');
    return checked ? checked.value : 'launch';
  }

  function isRecap() { return mode() === 'recap'; }

  function range() {
    var period = el('scope-period');
    var chosen = period ? period.value : 'last-week';
    if (chosen === 'custom') {
      var from = el('scope-from');
      var to = el('scope-to');
      return { since: (from && from.value) || '', until: (to && to.value) || '' };
    }
    var pair = periodDates(chosen);
    return pair ? { since: isoDay(pair[0]), until: isoDay(pair[1]) } : { since: '', until: '' };
  }

  /** Shows the range controls only when they apply, and echoes the dates a preset resolved to. */
  function refresh() {
    var recap = isRecap();
    var host = el('scope-range');
    if (host) host.hidden = !recap;

    var period = el('scope-period');
    var custom = !!period && period.value === 'custom';
    var from = el('scope-from-field');
    var to = el('scope-to-field');
    if (from) from.hidden = !custom;
    if (to) to.hidden = !custom;

    var echo = el('scope-dates');
    if (echo) {
      var current = range();
      // A preset is a promise about dates the reader cannot see, so it is spelled out.
      // A custom range is already on screen in two date fields, so repeating it is noise.
      echo.textContent = custom || !current.since ? '' : current.since + ' to ' + current.until;
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
    document.addEventListener('change', function (event) {
      if (!event.target.closest || !event.target.closest('#scope')) return;
      refresh();
      if (onChange) onChange();
    });
    refresh();
  }

  return {
    isoDay: isoDay,
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
