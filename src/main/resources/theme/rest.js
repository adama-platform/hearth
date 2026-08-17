// The rest timer, which is a clock and nothing else.
//
// The server has already rendered how long it has been since the last set, in words, and the target
// rest beside it. Without this file the page is honest and static: it says "1m 20s since your last
// set, rest 2m", which is exactly right for somebody who glances at it once. All this adds is that
// the number moves, and that it says when the rest is up.
//
// That split is deliberate. A gym is the worst network in anybody's life, and a timer that only
// exists once a script has loaded is a timer that is missing at the moment it is wanted. So the
// server does the arithmetic and this does the ticking.
(function () {
  var timer = document.querySelector('[data-rest]');
  if (!timer) {
    return;
  }
  var target = parseInt(timer.getAttribute('data-rest'), 10) || 0;
  var since = parseInt(timer.getAttribute('data-since'), 10) || 0;
  var counter = timer.querySelector('[data-rest-count]');
  if (!counter) {
    return;
  }
  // when the page was rendered, so the count stays true across a tab left in a pocket rather than
  // drifting by however long the browser felt like throttling the interval
  var rendered = Date.now() - since * 1000;

  function say(seconds) {
    var minutes = Math.floor(seconds / 60);
    var rest = seconds % 60;
    return minutes > 0 ? minutes + 'm ' + (rest < 10 ? '0' : '') + rest + 's' : rest + 's';
  }

  function tick() {
    var elapsed = Math.max(0, Math.round((Date.now() - rendered) / 1000));
    if (target > 0 && elapsed < target) {
      counter.textContent = say(target - elapsed) + ' left';
      timer.setAttribute('data-state', 'resting');
    } else if (target > 0) {
      counter.textContent = 'rested — ' + say(elapsed);
      timer.setAttribute('data-state', 'ready');
    } else {
      counter.textContent = say(elapsed) + ' since your last set';
    }
  }

  tick();
  // one second, because this is a clock somebody is looking at; there is nothing else on the page
  // doing any work, and a coarser tick reads as a stuck timer
  setInterval(tick, 1000);
})();
