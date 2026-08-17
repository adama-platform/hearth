// The live channel, shared by every tab on this domain.
//
// Three jobs, in order of how much they matter:
//
//   1. hold ONE connection per domain, however many tabs are open;
//   2. hand out the signals that arrive on it;
//   3. light the bell, and update whatever is on screen in place.
//
// A signal carries no content. It says "comments:412 moved" and whatever is listening decides
// whether it cares and what to fetch. That is what keeps this file small and what keeps the server's fan-out
// cheap enough to be boring.
//
// ONE CONNECTION. Four tabs open used to mean four event streams, four heartbeats and four rows of
// presence traffic for one person sitting in one chair. The tabs elect a leader over a
// BroadcastChannel: the leader connects and rebroadcasts every signal, the followers connect to
// nothing. If the leader closes, the next heartbeat that does not arrive promotes somebody else
// within two seconds.
//
// It is deliberately NOT the service worker doing this, which is the obvious place to put it. A
// service worker is terminated by the browser whenever it feels like it -- usually within thirty
// seconds of the last fetch -- and a long-lived stream inside one dies with it, silently, on some
// browsers and not others. The worker is still what receives push while every tab is closed; this
// is what keeps the open tabs cheap.

(function () {
  'use strict';

  var tag = document.currentScript;
  if (!tag) { return; }
  var LIVE = tag.getAttribute('data-live') || '/~live';
  var ME = parseInt(tag.getAttribute('data-me') || '0', 10);

  var cursor = 0;
  var listeners = [];
  var channel = null;
  var leader = false;
  var leaderSeenAt = 0;
  var source = null;
  var polling = false;
  var stopped = false;
  var id = Math.random().toString(36).slice(2) + Date.now();

  // ---- the bus between tabs -------------------------------------------------------------------

  try {
    channel = new BroadcastChannel('hearth-live');
  } catch (e) {
    channel = null;
  }

  function broadcast(message) {
    if (channel) { try { channel.postMessage(message); } catch (e) { /* closed */ } }
  }

  if (channel) {
    channel.onmessage = function (event) {
      var message = event.data || {};
      if (message.type === 'signal') {
        deliver(message.signal, false);
      } else if (message.type === 'lead') {
        leaderSeenAt = Date.now();
        if (leader && message.id !== id && message.id > id) {
          // two tabs decided at once; the higher id keeps it, and this one steps down rather than
          // both holding a connection forever
          stepDown();
        }
      } else if (message.type === 'bell') {
        paintBell(message.count);
      }
    };
  }

  function stepDown() {
    leader = false;
    if (source) { source.close(); source = null; }
    polling = false;
  }

  function considerLeading() {
    if (stopped) { return; }
    if (leader) {
      broadcast({type: 'lead', id: id});
      return;
    }
    // nothing has claimed it for two heartbeats, so claim it
    if (Date.now() - leaderSeenAt > 2500) {
      leader = true;
      leaderSeenAt = Date.now();
      broadcast({type: 'lead', id: id});
      connect();
    }
  }

  // ---- the connection ------------------------------------------------------------------------

  function connect() {
    if (!leader || stopped) { return; }
    if (window.EventSource) {
      openStream();
    } else {
      longPoll();
    }
  }

  function openStream() {
    try {
      source = new EventSource(LIVE + '/sse?since=' + cursor);
    } catch (e) {
      longPoll();
      return;
    }
    var opened = false;
    source.addEventListener('hello', function (event) {
      opened = true;
      var hello = parse(event.data);
      // fell behind what the server still remembers: everything on the page is suspect, so tell
      // the listeners to start over rather than pretending we are in step
      if (hello && cursor && hello.floor > cursor) { resync(); }
      if (hello && !cursor) { cursor = hello.head; }
    });
    source.addEventListener('signal', function (event) {
      opened = true;
      var signal = parse(event.data);
      if (signal) { deliver(signal, true); }
    });
    source.onerror = function () {
      // EventSource reconnects by itself, and sends Last-Event-ID when it does. What it cannot do
      // is notice that a proxy is eating the stream -- so a connection that never opened at all
      // falls back to polling instead of retrying into the same wall.
      if (!opened) {
        if (source) { source.close(); source = null; }
        longPoll();
      }
    };
  }

  function longPoll() {
    if (polling || stopped || !leader) { return; }
    polling = true;
    var tick = function () {
      if (!polling || stopped || !leader) { return; }
      fetch(LIVE + '/poll?since=' + cursor, {credentials: 'same-origin'})
        .then(function (res) { return res.status === 200 ? res.json() : null; })
        .then(function (body) {
          if (!body) { setTimeout(tick, 5000); return; }
          if (cursor && body.floor > cursor) { resync(); }
          if (!cursor) { cursor = body.head; }
          for (var k = 0; k < body.signals.length; k++) { deliver(body.signals[k], true); }
          setTimeout(tick, 250);
        })
        .catch(function () { setTimeout(tick, 5000); });
    };
    tick();
  }

  function parse(text) {
    try { return JSON.parse(text); } catch (e) { return null; }
  }

  function deliver(signal, mine) {
    if (!signal || signal.seq <= cursor) { return; }
    cursor = signal.seq;
    if (mine) { broadcast({type: 'signal', signal: signal}); }
    for (var k = 0; k < listeners.length; k++) {
      try { listeners[k](signal); } catch (e) { /* one listener must not stop the next */ }
    }
  }

  function resync() {
    for (var k = 0; k < listeners.length; k++) {
      try { listeners[k]({kind: 'resync'}); } catch (e) { /* as above */ }
    }
  }

  // ---- the bell ------------------------------------------------------------------------------

  var unread = 0;

  function paintBell(count) {
    unread = count;
    var bells = document.querySelectorAll('[data-bell]');
    for (var k = 0; k < bells.length; k++) {
      var bell = bells[k];
      bell.setAttribute('data-count', String(count));
      if (count > 0) {
        bell.classList.add('lit');
        bell.setAttribute('aria-label', count + ' unread');
      } else {
        bell.classList.remove('lit');
        bell.setAttribute('aria-label', 'nothing new');
      }
      var badge = bell.querySelector('[data-bell-count]');
      if (badge) { badge.textContent = count > 99 ? '99+' : String(count); }
    }
  }

  function ring() {
    // a page that is already showing the thing counts nothing: it is about to redraw with the new
    // reply on it, and a bell for something in front of you is a bell that means nothing
    if (document.querySelector('[data-live-region]') && !document.hidden) { return; }
    paintBell(unread + 1);
    broadcast({type: 'bell', count: unread});
  }

  // ---- whatever is on screen, updated in place ------------------------------------------------
  //
  // Non-destructive on purpose. Somebody halfway through typing a reply must not lose it because
  // a stranger posted, so nothing here replaces a subtree that contains the caret, an open
  // <details>, or a form somebody has touched. Everything else is matched by key and swapped.

  function holdsWork(node) {
    if (!node || node.nodeType !== 1) { return false; }
    var active = document.activeElement;
    if (active && active !== document.body && node.contains(active)) { return true; }
    if (node.querySelector && node.querySelector('details[open]')) { return true; }
    var fields = node.querySelectorAll ? node.querySelectorAll('textarea, input') : [];
    for (var k = 0; k < fields.length; k++) {
      var field = fields[k];
      if (field.type === 'hidden') { continue; }
      if (field.value && field.value !== field.defaultValue) { return true; }
    }
    return false;
  }

  function morph(into, from) {
    var have = {};
    var keep = [];
    var k;
    for (k = 0; k < into.children.length; k++) {
      var child = into.children[k];
      var key = child.getAttribute('data-key');
      if (key) { have[key] = child; }
    }
    for (k = 0; k < from.children.length; k++) {
      var fresh = from.children[k];
      var freshKey = fresh.getAttribute('data-key');
      var existing = freshKey ? have[freshKey] : null;
      if (!existing) {
        into.appendChild(fresh.cloneNode(true));
      } else {
        if (!holdsWork(existing) && existing.innerHTML !== fresh.innerHTML) {
          existing.innerHTML = fresh.innerHTML;
        }
        keep.push(freshKey);
        delete have[freshKey];
      }
    }
    for (var gone in have) {
      if (Object.prototype.hasOwnProperty.call(have, gone) && !holdsWork(have[gone])) {
        have[gone].parentNode.removeChild(have[gone]);
      }
    }
  }

  var refreshing = false;

  /**
   * Re-fetch the page this tab is on and swap in the parts that changed.
   *
   * The whole page, because a signal says only that a row moved -- and the page already knows how
   * to render itself for the person looking at it. What comes back is matched against what is here
   * by key and only the differences are touched.
   */
  function refresh() {
    if (refreshing) { return; }
    var regions = document.querySelectorAll('[data-live-region]');
    if (!regions.length) { return; }
    refreshing = true;
    fetch(window.location.pathname + window.location.search, {credentials: 'same-origin'})
      .then(function (res) { return res.status === 200 ? res.text() : null; })
      .then(function (html) {
        refreshing = false;
        if (!html) { return; }
        var parsed = new DOMParser().parseFromString(html, 'text/html');
        for (var k = 0; k < regions.length; k++) {
          var name = regions[k].getAttribute('data-live-region');
          var fresh = parsed.querySelector('[data-live-region="' + name + '"]');
          if (fresh) { morph(regions[k], fresh); }
        }
        var count = parsed.querySelector('[data-live-count]');
        var here = document.querySelector('[data-live-count]');
        if (count && here && here.textContent !== count.textContent) {
          here.textContent = count.textContent;
        }
      })
      .catch(function () { refreshing = false; });
  }

  // ---- what this file itself listens for -------------------------------------------------------

  listeners.push(function (signal) {
    if (signal.kind === 'updated' || signal.kind === 'resync') {
      refresh();
      ring();
    }
  });

  // ---- what other scripts use ------------------------------------------------------------------

  window.hearthLive = {
    on: function (fn) { listeners.push(fn); },
    cursor: function () { return cursor; },
    clearBell: function () { paintBell(0); broadcast({type: 'bell', count: 0}); },
    refresh: refresh,
    me: ME,
    liveUrl: LIVE,
    morph: morph,
    holdsWork: holdsWork
  };

  // ---- go --------------------------------------------------------------------------------------

  setInterval(considerLeading, 1000);
  considerLeading();

  document.addEventListener('visibilitychange', function () {
    // a tab coming back to the front is the cheapest moment to notice it missed something
    if (!document.hidden) { considerLeading(); }
  });

  window.addEventListener('pagehide', function () {
    stopped = true;
    if (source) { source.close(); source = null; }
  });
}());
