/* ═══════════════════════════════════════════════════════════════════════════
   KSH — Student classes behavior
   - Leave-class menu action gated by confirm modal
   - Copy subject code to clipboard
   - Copy the displayed subject code
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Server flash payloads are drained centrally by notifications.js.

  // ── "Rời khỏi lớp" menu action → confirm → submit hidden form ──────
  document.addEventListener('click', function (e) {
    var trigger = e.target.closest('[data-action="leave-class"]');
    if (!trigger) return;
    e.preventDefault();
    var classId = trigger.dataset.classId;
    var className = trigger.dataset.className || 'này';
    var form = document.getElementById('leave-form-' + classId);
    if (!form) return;
    if (!window.KshModal || !window.KshModal.confirm) {
      form.submit();
      return;
    }
    window.KshModal.confirm({
      title: 'Rời lớp học',
      body: 'Bạn có chắc muốn rời lớp ' + className + '?',
      confirmLabel: 'Rời lớp',
      onConfirm: function () { form.submit(); }
    });
  });

  // ── Copy subject code to clipboard ────────────────────────────────
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.copy-code');
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    var code = btn.dataset.code || '';
    if (!code) return;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(code).then(function () {
        if (window.KshToast) window.KshToast.success('Đã sao chép mã môn: ' + code);
      });
    }
  });

})();
