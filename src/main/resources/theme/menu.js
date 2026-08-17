// The menu closes when you look away.
//
// The panel is a <details>, which means it works with no JavaScript at all: pressing the button
// opens it, pressing it again closes it, and a keyboard reaches it because a summary is a button.
// What it does not do on its own is close when somebody clicks the page behind it or presses
// escape -- and a panel that stays open over what you are trying to read is a panel people learn to
// distrust. That is the whole of this file.
//
// Deliberately not the thing that opens it. If this script fails to load, the menu still works.
(function () {
  // The site's menu, and the admin sidebar, which are the same behaviour at two sizes.
  //
  // The sidebar ships *open*, and this closes it when the screen is narrow. That way a phone with
  // no JavaScript gets the whole list expanded above the page -- which is exactly what it had
  // before and is merely inconvenient -- rather than a menu button that does nothing, which is a
  // section somebody cannot reach at all. A wide screen never sees it closed.
  var NARROW = '(max-width: 48rem)';
  var sidebar = document.querySelector('[data-sidemenu]');
  if (sidebar && window.matchMedia) {
    var narrow = window.matchMedia(NARROW);
    var fit = function (matches) {
      sidebar.open = !matches;
    };
    fit(narrow.matches);
    // rotating a phone, or dragging a window across the breakpoint, should not leave somebody with
    // a sidebar collapsed on a screen wide enough to show it and no button drawn to open it
    if (narrow.addEventListener) {
      narrow.addEventListener('change', function (event) {
        fit(event.matches);
      });
    }
  }

  var menus = [];
  var site = document.querySelector('[data-menu]');
  if (site) {
    menus.push(site);
  }
  if (sidebar) {
    menus.push(sidebar);
  }
  if (!menus.length) {
    return;
  }

  // A menu that is not collapsible right now -- the sidebar on a wide screen -- must not be closed
  // by a click on the page, or the sidebar would disappear the first time somebody clicked
  // anything and there would be no button to bring it back.
  function collapsible(menu) {
    if (menu !== sidebar) {
      return true;
    }
    return window.matchMedia ? window.matchMedia(NARROW).matches : false;
  }

  function each(action) {
    for (var i = 0; i < menus.length; i++) {
      if (collapsible(menus[i])) {
        action(menus[i]);
      }
    }
  }

  document.addEventListener('click', function (event) {
    each(function (menu) {
      if (menu.open && !menu.contains(event.target)) {
        menu.open = false;
      }
    });
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') {
      return;
    }
    each(function (menu) {
      if (!menu.open) {
        return;
      }
      menu.open = false;
      var summary = menu.querySelector('summary');
      if (summary) {
        summary.focus();
      }
    });
  });

  // following a link inside it should not leave it hanging open behind the next page in browsers
  // that restore a page from the back-forward cache
  for (var k = 0; k < menus.length; k++) {
    (function (menu) {
      menu.addEventListener('click', function (event) {
        if (collapsible(menu) && event.target.closest('a')) {
          menu.open = false;
        }
      });
    })(menus[k]);
  }
})();
