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
        if (file && typeof form.requestSubmit === 'function') {
          window.setTimeout(function () { form.requestSubmit(); }, 0);
        }
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
    var menus = Array.from(document.querySelectorAll('details.personal-library-more'));

    function closeMenus(except) {
      menus.forEach(function (details) {
        if (details !== except) details.removeAttribute('open');
      });
    }

    function placeMenu(details) {
      var summary = details.querySelector(':scope > summary');
      var menu = details.querySelector(':scope > .personal-library-more-menu');
      if (!summary || !menu) return;

      window.requestAnimationFrame(function () {
        if (!details.open) return;
        var gap = 8;
        var edge = 12;
        var triggerRect = summary.getBoundingClientRect();
        var menuRect = menu.getBoundingClientRect();
        var left = Math.min(window.innerWidth - menuRect.width - edge,
          Math.max(edge, triggerRect.right - menuRect.width));
        var top = triggerRect.bottom + gap;

        if (top + menuRect.height > window.innerHeight - edge) {
          top = triggerRect.top - menuRect.height - gap;
        }
        top = Math.max(edge, Math.min(top, window.innerHeight - menuRect.height - edge));
        menu.style.left = Math.round(left) + 'px';
        menu.style.top = Math.round(top) + 'px';
      });
    }

    menus.forEach(function (details) {
      details.addEventListener('toggle', function () {
        if (!details.open) return;
        closeMenus(details);
        placeMenu(details);
      });
    });

    document.addEventListener('pointerdown', function (event) {
      if (!event.target.closest('details.personal-library-more')) closeMenus();
    });
    window.addEventListener('resize', function () { closeMenus(); });
    window.addEventListener('scroll', function (event) {
      if (event.target instanceof Element && event.target.closest('.personal-library-more-menu')) return;
      closeMenus();
    }, true);
  }

  function setupDetailPanel() {
    var shell = document.querySelector('[data-library-detail-panel]');
    if (!shell) return;

    var panel = shell.querySelector('.personal-library-detail-panel');
    var activeTrigger = null;
    var detailRequest = 0;

    function field(selector) {
      return shell.querySelector(selector);
    }

    function setText(selector, value) {
      var element = field(selector);
      if (element) element.textContent = value || '—';
    }

    function formatSize(value) {
      var bytes = Number(value);
      if (!Number.isFinite(bytes) || bytes < 0) return '—';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1048576) return (bytes / 1024).toLocaleString('vi-VN', {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1
      }) + ' KB';
      return (bytes / 1048576).toLocaleString('vi-VN', {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1
      }) + ' MB';
    }

    function formatDateTime(value) {
      if (!value) return 'Chưa rõ';
      if (typeof value !== 'string' || value.indexOf('T') < 0) return value;
      var date = new Date(value);
      if (Number.isNaN(date.getTime())) return value;
      return date.toLocaleString('vi-VN', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
      });
    }

    function safeInternalUrl(value, fallback) {
      return typeof value === 'string' && value.indexOf('/') === 0 ? value : fallback;
    }

    function setDetailIcon(formatClass, iconLabel) {
      var icon = field('[data-detail-icon]');
      if (icon) icon.className = 'asset-format-icon is-' + (formatClass || 'file');
      setText('[data-detail-format]', iconLabel || 'FILE');
    }

    function renderUsage(usages, fallbackInUse) {
      var usageStatus = field('[data-detail-usage-status]');
      var usageList = field('[data-detail-usage-list]');
      if (usageList) usageList.replaceChildren();
      var items = Array.isArray(usages) ? usages : [];

      if (items.length === 0) {
        if (usageStatus) usageStatus.textContent = fallbackInUse
          ? 'Chưa tìm thấy vị trí còn hiệu lực cho tài liệu này.'
          : 'Tài liệu chưa được dùng trong bài giảng hoặc lớp học.';
        return;
      }
      if (usageStatus) {
        usageStatus.textContent = items.length === 1
          ? 'Tài liệu đang được dùng tại vị trí sau.'
          : 'Tài liệu đang được dùng tại ' + items.length + ' vị trí sau.';
      }
      if (!usageList) return;

      items.forEach(function (usage) {
        var item = document.createElement('a');
        item.className = 'personal-library-usage-item';
        item.href = safeInternalUrl(usage && usage.url, '/lecturer/library/list');

        var copy = document.createElement('span');
        var title = document.createElement('strong');
        title.textContent = usage && usage.title ? usage.title : 'Bài giảng';
        var subtitle = document.createElement('small');
        subtitle.textContent = usage && usage.subtitle ? usage.subtitle : 'Kho học liệu';
        copy.appendChild(title);
        copy.appendChild(subtitle);

        var meta = document.createElement('span');
        meta.className = 'personal-library-usage-meta';
        var placement = document.createElement('em');
        placement.textContent = usage && usage.placement ? usage.placement : 'Tài liệu bài giảng';
        var updated = document.createElement('time');
        updated.textContent = usage && usage.updatedAt
          ? 'Cập nhật ' + formatDateTime(usage.updatedAt) : 'Đang sử dụng';
        meta.appendChild(placement);
        meta.appendChild(updated);

        item.appendChild(copy);
        item.appendChild(meta);
        usageList.appendChild(item);
      });
    }

    function applyDetail(detail, fallback) {
      var usages = Array.isArray(detail.usages) ? detail.usages : [];
      var inUse = usages.length > 0;
      setDetailIcon(detail.formatClass || fallback.assetFormatClass,
        detail.formatClass === 'excel' ? 'X' : detail.formatClass === 'word' ? 'W'
          : detail.formatClass === 'powerpoint' ? 'P' : detail.formatClass === 'pdf' ? 'PDF'
            : detail.formatClass === 'video' ? '▶' : fallback.assetIcon);
      setText('[data-detail-title]', detail.title || fallback.assetTitle || 'Tài liệu');
      setText('[data-detail-filename]', detail.originalFilename || fallback.assetFilename || '');
      setText('[data-detail-kind]', detail.kind === 'VIDEO' ? 'Video' : 'Tài liệu');
      setText('[data-detail-format-text]', detail.formatLabel || fallback.assetFormat || 'FILE');
      setText('[data-detail-size]', formatSize(detail.sizeBytes));
      setText('[data-detail-status]', inUse ? 'Đã dùng' : 'Chưa dùng');
      setText('[data-detail-created]', formatDateTime(detail.createdAt) || fallback.assetCreated);
      setText('[data-detail-updated]', formatDateTime(detail.updatedAt) || fallback.assetUpdated);
      setText('[data-detail-activity-time]', formatDateTime(detail.updatedAt) || fallback.assetUpdated);
      setText('[data-detail-description]',
        '“' + (detail.title || fallback.assetTitle || 'Tài liệu')
          + '” được lưu trong kho cá nhân để dùng lại mà không sao chép tệp.');

      var viewLink = field('[data-detail-view]');
      var downloadLink = field('[data-detail-download]');
      if (viewLink) viewLink.href = safeInternalUrl(detail.previewUrl, fallback.assetViewUrl || '#');
      if (downloadLink) downloadLink.href = safeInternalUrl(detail.downloadUrl,
        fallback.assetDownloadUrl || '#');
      renderUsage(usages, fallback.assetInUse === 'true');
    }

    function loadDetail(trigger, request) {
      var endpoint = trigger.dataset.assetDetailUrl;
      if (!endpoint || typeof window.fetch !== 'function') return;
      var usageStatus = field('[data-detail-usage-status]');
      if (usageStatus) usageStatus.textContent = 'Đang tải nơi đang sử dụng…';

      window.fetch(endpoint, {
        method: 'GET', credentials: 'same-origin', headers: {'Accept': 'application/json'}
      }).then(function (response) {
        if (!response.ok) throw new Error('Không thể tải chi tiết tài liệu');
        return response.json();
      }).then(function (detail) {
        if (request !== detailRequest || activeTrigger !== trigger || shell.hidden) return;
        applyDetail(detail || {}, trigger.dataset);
      }).catch(function () {
        if (request !== detailRequest || activeTrigger !== trigger || shell.hidden) return;
        if (usageStatus) usageStatus.textContent = trigger.dataset.assetInUse === 'true'
          ? 'Chưa thể tải các vị trí đang sử dụng. Hãy thử lại sau.'
          : 'Tài liệu chưa được dùng trong bài giảng hoặc lớp học.';
      });
    }

    function closePanel() {
      detailRequest += 1;
      shell.hidden = true;
      shell.setAttribute('aria-hidden', 'true');
      document.body.classList.remove('personal-library-detail-open');
      if (activeTrigger && typeof activeTrigger.focus === 'function') activeTrigger.focus();
    }

    function openPanel(trigger) {
      activeTrigger = trigger;
      var data = trigger.dataset;
      var inUse = data.assetInUse === 'true';
      var formatClass = data.assetFormatClass || 'file';
      setDetailIcon(formatClass, data.assetIcon || 'FILE');
      setText('[data-detail-title]', data.assetTitle || 'Tài liệu');
      setText('[data-detail-filename]', data.assetFilename || '');
      setText('[data-detail-kind]', data.assetKind === 'VIDEO' ? 'Video' : 'Tài liệu');
      setText('[data-detail-format-text]', data.assetFormat || 'FILE');
      setText('[data-detail-size]', formatSize(data.assetSize));
      setText('[data-detail-status]', inUse ? 'Đã dùng' : 'Chưa dùng');
      setText('[data-detail-created]', data.assetCreated || 'Chưa rõ');
      setText('[data-detail-updated]', data.assetUpdated || 'Chưa rõ');
      setText('[data-detail-activity-time]', data.assetUpdated || 'Chưa rõ');
      setText('[data-detail-description]',
        '“' + (data.assetTitle || 'Tài liệu') + '” được lưu trong kho cá nhân để dùng lại mà không sao chép tệp.');
      var usage = field('[data-detail-usage]');
      var usageList = field('[data-detail-usage-list]');
      if (usage) usage.setAttribute('data-asset-id', data.assetId || '');
      if (usageList) usageList.replaceChildren();
      setText('[data-detail-usage-status]', inUse
        ? 'Đang tải nơi đang sử dụng…'
        : 'Tài liệu chưa được dùng trong bài giảng hoặc lớp học.');

      var viewLink = field('[data-detail-view]');
      var downloadLink = field('[data-detail-download]');
      if (viewLink) {
        viewLink.href = data.assetViewUrl || '#';
        viewLink.setAttribute('data-asset-id', data.assetId || '');
      }
      if (downloadLink) downloadLink.href = data.assetDownloadUrl || '#';

      shell.hidden = false;
      shell.setAttribute('aria-hidden', 'false');
      document.body.classList.add('personal-library-detail-open');
      var closeButton = shell.querySelector('.personal-library-detail-panel [data-detail-close]');
      if (closeButton) closeButton.focus();
      detailRequest += 1;
      loadDetail(trigger, detailRequest);
    }

    document.querySelectorAll('[data-library-detail-trigger]').forEach(function (trigger) {
      trigger.addEventListener('click', function (event) {
        if (event.target.closest('a, button, summary, input, select, form, details')) return;
        openPanel(trigger);
      });
      trigger.addEventListener('keydown', function (event) {
        if (event.target !== trigger || (event.key !== 'Enter' && event.key !== ' ')) return;
        event.preventDefault();
        openPanel(trigger);
      });
    });

    document.querySelectorAll('[data-row-share-trigger]').forEach(function (button) {
      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        var row = button.closest('[data-library-detail-trigger]');
        var share = row && row.querySelector('[data-personal-library-share]');
        if (share) share.click();
      });
    });

    document.querySelectorAll('[data-row-detail-trigger]').forEach(function (button) {
      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        var row = button.closest('[data-library-detail-trigger]');
        var menu = button.closest('details.personal-library-more');
        if (menu) menu.removeAttribute('open');
        if (row) openPanel(row);
      });
    });

    shell.querySelectorAll('[data-detail-close]').forEach(function (button) {
      button.addEventListener('click', closePanel);
    });

    var shareButton = field('[data-detail-share]');
    if (shareButton) {
      shareButton.addEventListener('click', function () {
        var rowShare = activeTrigger && activeTrigger.querySelector('[data-personal-library-share]');
        if (!rowShare && activeTrigger) {
          var assetId = activeTrigger.getAttribute('data-asset-id');
          Array.from(document.querySelectorAll('.personal-library-row')).some(function (row) {
            if (row.getAttribute('data-asset-id') !== assetId) return false;
            rowShare = row.querySelector('[data-personal-library-share]');
            return Boolean(rowShare);
          });
        }
        closePanel();
        if (rowShare) rowShare.click();
      });
    }

    var tabs = Array.from(shell.querySelectorAll('.personal-library-detail-tabs button'));
    var sections = Array.from(shell.querySelectorAll('.personal-library-detail-content > section'));
    tabs.forEach(function (tab, index) {
      tab.addEventListener('click', function () {
        tabs.forEach(function (item) { item.classList.remove('is-active'); });
        tab.classList.add('is-active');
        if (sections[index]) sections[index].scrollIntoView({behavior: 'smooth', block: 'start'});
      });
    });

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !shell.hidden) closePanel();
    });

    if (panel) {
      panel.addEventListener('click', function (event) {
        event.stopPropagation();
      });
    }
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
    setupDetailPanel();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ready);
  } else {
    ready();
  }
})();
