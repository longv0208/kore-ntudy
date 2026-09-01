/* ═══════════════════════════════════════════════════════════════════════════
   KSH — Class management page behavior
   Loaded by /lecturer/classes (manage). Requires app.js (KshModal + dropdowns).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Server flash payloads are drained centrally by notifications.js.

  // ── Copy subject code to clipboard ─────────────────────────────────
  document.querySelectorAll('.copy-code').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      e.preventDefault();
      var code = btn.dataset.code;
      if (!code) return;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(code).then(function () {
          if (window.KshToast) window.KshToast.success('Đã sao chép mã môn ' + code);
        }).catch(function () {
          if (window.KshToast) window.KshToast.error('Không sao chép được, vui lòng thử lại');
        });
      }
    });
  });

  // ── Delete: confirm modal + submit hidden form ─────────────────────
  // Hidden form per row preserves CSRF token (must NOT use fetch/XHR).
  document.querySelectorAll('[data-action="delete-class"]').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var classId = btn.dataset.classId;
      var className = btn.dataset.className || 'lớp này';
      if (!classId || !window.KshModal) return;

      window.KshModal.confirm({
        title: 'Xác nhận xoá lớp',
        body: 'Bạn có chắc chắn muốn xoá ' + className + '? Hành động này không thể hoàn tác.',
        confirmLabel: 'Xoá',
        onConfirm: function () {
          var form = document.getElementById('delete-form-' + classId);
          if (form) form.submit(); // form.submit keeps CSRF token intact
        }
      });
    });
  });

})();
