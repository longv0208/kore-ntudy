(function () {
  'use strict';

  function setupUpload() {
    var form = document.querySelector('[data-library-upload-form]');
    if (!form) return;

    var input = form.querySelector('[data-library-file-input]');
    var label = form.querySelector('[data-library-file-label]');
    var submit = form.querySelector('[data-library-upload-submit]');

    if (input && label) {
      input.addEventListener('change', function () {
        var file = input.files && input.files[0];
        label.textContent = file ? file.name : 'Chọn tệp';
      });
    }

    form.addEventListener('submit', function () {
      if (!form.checkValidity() || !submit) return;
      submit.disabled = true;
      submit.textContent = 'Đang tải lên…';
    });
  }

  function setupDeleteConfirmation() {
    document.querySelectorAll('[data-library-delete-form]').forEach(function (form) {
      form.addEventListener('submit', function (event) {
        var title = form.getAttribute('data-asset-title') || 'tài liệu này';
        if (!window.confirm('Xoá “' + title + '” khỏi kho cá nhân?\nTài liệu đang được bài giảng sử dụng sẽ không thể xoá.')) {
          event.preventDefault();
        }
      });
    });
  }

  function setupExclusiveMenus() {
    document.querySelectorAll('details.personal-library-more').forEach(function (details) {
      details.addEventListener('toggle', function () {
        if (!details.open) return;
        document.querySelectorAll('details.personal-library-more[open]').forEach(function (other) {
          if (other !== details) other.removeAttribute('open');
        });
      });
    });
  }

  function setupShareDialog() {
    var dialog = document.getElementById('personalLibraryShareDialog');
    if (!dialog || typeof window.fetch !== 'function') return;

    var title = dialog.querySelector('[data-share-asset-title]');
    var classMode = dialog.querySelector('[data-share-class-mode]');
    var kindNote = dialog.querySelector('[data-share-kind-note]');
    var form = dialog.querySelector('[data-class-share-form]');
    var classSelect = dialog.querySelector('[data-share-class]');
    var status = dialog.querySelector('[data-share-status]');
    var submit = dialog.querySelector('[data-share-submit]');
    var currentAsset = null;
    var classes = [];

    function csrfMeta(name) {
      var meta = document.querySelector('meta[name="' + name + '"]');
      return meta ? meta.getAttribute('content') || '' : '';
    }

    function setStatus(message, modifier) {
      if (!status) return;
      status.textContent = message || '';
      status.classList.remove('is-error', 'is-success', 'is-loading');
      if (modifier) status.classList.add(modifier);
    }

    function resetSelect(select, placeholder) {
      if (!select) return;
      select.replaceChildren();
      var option = document.createElement('option');
      option.value = '';
      option.textContent = placeholder;
      select.appendChild(option);
      select.value = '';
      select.disabled = true;
    }

    function addOptions(select, items, label) {
      resetSelect(select, label);
      items.forEach(function (item) {
        var option = document.createElement('option');
        option.value = String(item.id);
        option.textContent = item.label;
        select.appendChild(option);
      });
      select.disabled = items.length === 0;
    }

    function syncSubmit() {
      if (!submit) return;
      submit.disabled = !classSelect.value;
    }

    function resetClassFlow() {
      classes = [];
      if (form) form.hidden = true;
      resetSelect(classSelect, 'Chọn lớp…');
      setStatus('');
      if (submit) {
        submit.disabled = true;
        submit.textContent = 'Chia sẻ vào Tài liệu';
      }
    }

    function closeDialog() {
      if (dialog.open) dialog.close();
    }

    function loadTargets() {
      if (!currentAsset || currentAsset.kind !== 'DOCUMENT') return;
      if (form) form.hidden = false;
      classMode.disabled = true;
      setStatus('Đang tải các lớp ACTIVE bạn sở hữu…', 'is-loading');

      window.fetch('/lecturer/library/assets/' + encodeURIComponent(currentAsset.id) + '/class-targets', {
        method: 'GET',
        credentials: 'same-origin',
        headers: {'Accept': 'application/json'}
      }).then(function (response) {
        return response.json().catch(function () { return {}; }).then(function (payload) {
          if (!response.ok) throw new Error(payload.message || 'Chưa thể tải danh sách lớp');
          return payload;
        });
      }).then(function (payload) {
        classes = Array.isArray(payload.classes) ? payload.classes : [];
        addOptions(classSelect, classes.map(function (item) {
          return {
            id: item.id,
            label: item.name + (item.status ? ' · ' + item.status : '')
          };
        }), 'Chọn lớp…');
        setStatus(classes.length
          ? 'Chọn lớp sẽ nhận tài liệu trong tab Tài liệu.'
          : 'Bạn chưa có lớp phù hợp để nhận tài liệu này.');
      }).catch(function (error) {
        setStatus(error.message || 'Chưa thể tải danh sách lớp', 'is-error');
      }).finally(function () {
        classMode.disabled = false;
      });
    }

    document.querySelectorAll('[data-personal-library-share]').forEach(function (trigger) {
      trigger.addEventListener('click', function () {
        currentAsset = {
          id: trigger.getAttribute('data-asset-id'),
          title: trigger.getAttribute('data-asset-title') || 'Tài liệu',
          kind: trigger.getAttribute('data-asset-kind') || 'DOCUMENT'
        };
        resetClassFlow();
        if (title) title.textContent = currentAsset.title;
        var supportsClassShare = currentAsset.kind === 'DOCUMENT';
        classMode.disabled = !supportsClassShare;
        classMode.setAttribute('aria-disabled', String(!supportsClassShare));
        if (kindNote) kindNote.hidden = supportsClassShare;
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
      });
    });

    dialog.querySelectorAll('[data-share-close]').forEach(function (button) {
      button.addEventListener('click', closeDialog);
    });
    dialog.addEventListener('click', function (event) {
      if (event.target === dialog) closeDialog();
    });
    dialog.addEventListener('close', resetClassFlow);

    classMode.addEventListener('click', loadTargets);
    classSelect.addEventListener('change', syncSubmit);

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      if (!currentAsset || currentAsset.kind !== 'DOCUMENT' || !form.checkValidity()) return;
      submit.disabled = true;
      submit.textContent = 'Đang chia sẻ…';
      setStatus('Đang chia sẻ tài liệu vào lớp…', 'is-loading');

      var body = new URLSearchParams();
      body.set('classId', classSelect.value);
      var csrfToken = csrfMeta('_csrf');
      var csrfHeader = csrfMeta('_csrf_header');
      if (csrfToken) body.set('_csrf', csrfToken);
      var headers = {
        'Accept': 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
      };
      if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;

      window.fetch('/lecturer/library/assets/' + encodeURIComponent(currentAsset.id) + '/share/class', {
        method: 'POST',
        credentials: 'same-origin',
        headers: headers,
        body: body.toString()
      }).then(function (response) {
        return response.json().catch(function () { return {}; }).then(function (payload) {
          if (!response.ok) throw new Error(payload.message || 'Chưa thể chia sẻ tài liệu');
          return payload;
        });
      }).then(function (payload) {
        setStatus(payload.message || 'Đã chia sẻ vào tab Tài liệu của lớp', 'is-success');
        submit.textContent = 'Đã chia sẻ';
        classSelect.disabled = true;
      }).catch(function (error) {
        setStatus(error.message || 'Chưa thể chia sẻ tài liệu', 'is-error');
        submit.disabled = false;
        submit.textContent = 'Thử chia sẻ lại';
      });
    });
  }

  function ready() {
    setupUpload();
    setupDeleteConfirmation();
    setupExclusiveMenus();
    setupShareDialog();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ready);
  } else {
    ready();
  }
})();
