// Light or dark, decided by the person rather than by their laptop.
//
// This file is deliberately the first script on every page and it is NOT deferred, because the one
// thing it has to do is set the attribute before anything is painted. Deferred, or at the end of
// the body, and everybody who chose dark gets a white flash on every navigation -- which is worse
// than not offering the choice at all.
//
// The whole state is one string in localStorage. No cookie, because that would be a third cookie on
// a site whose privacy policy says there are two and both are strictly necessary; no server round
// trip, because a colour scheme is a property of the screen somebody is looking at rather than of
// their account -- the same person on a phone at night and a laptop at work can reasonably want
// different answers, and a stored preference on the account would give them one.
//
// The default is light. An operating system set to dark is a reasonable guess about a text editor
// and a poor one about a community's own colours, which somebody chose.

(function () {
  'use strict';

  var KEY = 'hearth-theme';
  var root = document.documentElement;

  function stored() {
    try {
      return localStorage.getItem(KEY);
    } catch (e) {
      // private mode, or storage turned off entirely. The page still works; it is just always light.
      return null;
    }
  }

  function apply(mode) {
    if (mode === 'dark') {
      root.setAttribute('data-theme', 'dark');
    } else {
      root.removeAttribute('data-theme');
    }
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    for (var k = 0; k < buttons.length; k++) {
      buttons[k].setAttribute('aria-pressed', mode === 'dark' ? 'true' : 'false');
      buttons[k].setAttribute('title', mode === 'dark' ? 'Switch to light' : 'Switch to dark');
    }
  }

  var current = stored() === 'dark' ? 'dark' : 'light';
  apply(current);

  function toggle() {
    current = current === 'dark' ? 'light' : 'dark';
    try {
      localStorage.setItem(KEY, current);
    } catch (e) {
      // it still changes for this page; it just will not be remembered
    }
    apply(current);
  }

  // one delegated listener rather than one per button, because the bar is rendered on every page
  // and a listener per element is a listener per page load that has to find its element first
  document.addEventListener('click', function (event) {
    var target = event.target;
    while (target && target !== document) {
      if (target.hasAttribute && target.hasAttribute('data-theme-toggle')) {
        event.preventDefault();
        toggle();
        return;
      }
      target = target.parentNode;
    }
  });

  // the buttons are not in the document yet when this runs, so paint them when they are
  document.addEventListener('DOMContentLoaded', function () {
    apply(current);
  });

  // and other tabs follow along: changing it in one and not the others is the kind of small
  // inconsistency that makes a site feel broken
  window.addEventListener('storage', function (event) {
    if (event.key === KEY) {
      current = event.newValue === 'dark' ? 'dark' : 'light';
      apply(current);
    }
  });
}());
