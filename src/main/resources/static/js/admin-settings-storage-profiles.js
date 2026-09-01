(function () {
  'use strict';
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.js-storage-profile-reveal').forEach(function (button) {
      button.addEventListener('click', function () {
        var input = document.getElementById('secretAccessKey');
        var live = document.getElementById('storageProfileActionResult');
        if (!input) return;
        if (button.getAttribute('aria-pressed') === 'true') {
          input.value = '********'; input.type = 'password';
          button.setAttribute('aria-pressed', 'false');
          if (live) live.textContent = 'Đã ẩn storage secret';
          return;
        }
        button.disabled = true;
        fetch('/admin/settings/storage-profiles/'
          + encodeURIComponent(button.getAttribute('data-profile-code')) + '/secret', {
          headers: { 'Accept': 'application/json' }, credentials: 'same-origin'
        }).then(function (response) {
          if (!response.ok) throw new Error('HTTP ' + response.status);
          return response.json();
        }).then(function (result) {
          if (!result.ok) throw new Error(result.errorCode || 'Secret unavailable');
          input.value = result.secret; input.type = 'text';
          button.setAttribute('aria-pressed', 'true');
          if (live) live.textContent = 'Đã hiện storage secret';
        }).catch(function (error) {
          if (window.KshToast) window.KshToast.error(error.message);
          if (live) live.textContent = error.message;
        }).finally(function () { button.disabled = false; });
      });
    });

    document.querySelectorAll('.js-storage-profile-test').forEach(function (button) {
      button.addEventListener('click', function () {
        var profileCode = button.getAttribute('data-profile-code');
        var result = document.getElementById(button.getAttribute('aria-controls'));
        var csrfToken = document.querySelector('meta[name="_csrf"]');
        var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
        if (!profileCode || !result || !csrfToken || !csrfHeader
            || !csrfToken.content || !csrfHeader.content) {
          showConnectionResult(result, 'failed',
            'Không thể bắt đầu kiểm tra kết nối. Vui lòng tải lại trang.');
          return;
        }

        var originalLabel = button.textContent;
        var headers = { 'Accept': 'application/json' };
        headers[csrfHeader.content] = csrfToken.content;
        var requestController = typeof AbortController === 'undefined'
          ? null : new AbortController();
        var requestTimeout = requestController
          ? window.setTimeout(function () { requestController.abort(); }, 12000)
          : null;

        button.disabled = true;
        button.textContent = 'Đang kiểm tra…';
        button.setAttribute('aria-busy', 'true');
        result.hidden = false;
        result.className = 'storage-connection-result is-testing';
        result.textContent = 'Đang kiểm tra backend lưu trữ bằng cấu hình đã lưu…';

        fetch('/admin/settings/storage-profiles/'
          + encodeURIComponent(profileCode) + '/test', {
          method: 'POST',
          headers: headers,
          credentials: 'same-origin',
          signal: requestController ? requestController.signal : undefined
        }).then(function (response) {
          if (!response.ok) throw new Error('STORAGE_TEST_HTTP_ERROR');
          return response.json();
        }).then(function (payload) {
          var status = payload && payload.status;
          if (status === 'SUCCESS') {
            showConnectionResult(result, 'success', payload.message);
          } else if (status === 'NOT_APPLICABLE') {
            showConnectionResult(result, 'neutral', payload.message);
          } else {
            showConnectionResult(result, 'failed', payload && payload.message);
          }
        }).catch(function (error) {
          var message = error && error.name === 'AbortError'
            ? 'Kiểm tra kết nối quá thời gian. Hãy kiểm tra endpoint và mạng.'
            : 'Không thể gọi dịch vụ kiểm tra kết nối. Vui lòng thử lại.';
          showConnectionResult(result, 'failed', message);
        }).finally(function () {
          if (requestTimeout !== null) window.clearTimeout(requestTimeout);
          button.disabled = false;
          button.textContent = originalLabel;
          button.removeAttribute('aria-busy');
        });
      });
    });
  });

  function showConnectionResult(result, state, message) {
    if (!result) return;
    result.hidden = false;
    result.className = 'storage-connection-result is-' + state;
    result.textContent = message || 'Không thể xác định trạng thái kết nối.';
  }
})();
