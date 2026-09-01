(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', function () {
    var drawer = document.getElementById('testDetailDrawer');
    var body = document.getElementById('testDetailDrawerBody');
    if (!drawer || !body) return;

    var lastTrigger = null;

    function openDetail(id, trigger) {
      var template = document.getElementById('testDetailTemplate-' + id);
      if (!template) return;
      lastTrigger = trigger || null;
      body.replaceChildren(template.content.cloneNode(true));
      drawer.hidden = false;
      document.body.classList.add('tst-drawer-open');
      window.requestAnimationFrame(function () {
        drawer.classList.add('is-open');
        var close = drawer.querySelector('[data-close-test-detail]');
        if (close) close.focus();
      });
    }

    function closeDetail() {
      drawer.classList.remove('is-open');
      document.body.classList.remove('tst-drawer-open');
      window.setTimeout(function () {
        drawer.hidden = true;
        body.replaceChildren();
        if (lastTrigger) lastTrigger.focus();
      }, 180);
    }

    document.addEventListener('click', function (event) {
      var openButton = event.target.closest('[data-open-test-detail]');
      var row = event.target.closest('[data-test-detail]');
      if (openButton && row) {
        openDetail(row.dataset.testDetail, openButton);
        return;
      }
      if (!row || event.target.closest('a, button, input, select, summary, details')) return;
      openDetail(row.dataset.testDetail, row);
    });

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !drawer.hidden) {
        closeDetail();
        return;
      }
      var row = event.target.closest && event.target.closest('[data-test-detail]');
      if (!row || (event.key !== 'Enter' && event.key !== ' ')) return;
      event.preventDefault();
      openDetail(row.dataset.testDetail, row);
    });

    drawer.addEventListener('click', function (event) {
      if (event.target.closest('[data-close-test-detail]')) closeDetail();
    });
  });
})();
