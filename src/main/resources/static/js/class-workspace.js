(function () {
  'use strict';
  // Native details keeps both filters and semester groups usable without JavaScript.
  var filters = document.querySelectorAll('.class-workspace .cw-filter');
  document.addEventListener('click', function (event) {
    filters.forEach(function (filter) {
      if (filter.open && !filter.contains(event.target)) filter.open = false;
    });
  });
  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') return;
    filters.forEach(function (filter) {
      if (!filter.open) return;
      filter.open = false;
      filter.querySelector('summary').focus();
    });
  });
})();
