(function () {
  'use strict';

  function init(form) {
    if (!form || form.dataset.personalLibraryPickerInitialized === 'true') return;
    var list = form.querySelector('[data-selected-library-assets]');
    var empty = form.querySelector('[data-selected-library-assets-empty]');
    var openButton = form.querySelector('[data-open-personal-library-picker]');
    if (!list || !openButton) return;
    form.dataset.personalLibraryPickerInitialized = 'true';

    var contentType = form.querySelector('[data-library-content-type]');
    var provider = form.querySelector('[data-library-video-provider]');
    var videoId = form.querySelector('[data-primary-video-id]');
    var videoUrl = form.querySelector('[data-library-video-url]');
    var videoSelection = form.querySelector('[data-primary-video-selection]');
    var videoName = form.querySelector('[data-primary-video-name]');
    var videoPicker = form.querySelector('[data-open-primary-video-picker]');
    var clearVideo = form.querySelector('[data-clear-primary-video]');
    var tabs = Array.prototype.slice.call(form.querySelectorAll('[data-library-form-tab]'));
    var panels = Array.prototype.slice.call(form.querySelectorAll('[data-library-form-panel]'));
    var resourceOnly = form.getAttribute('data-resource-only') === 'true';

    function showPanel(name, moveFocus) {
      var activeTab = null;
      panels.forEach(function (panel) {
        panel.hidden = panel.getAttribute('data-library-form-panel') !== name;
      });
      tabs.forEach(function (tab) {
        var active = tab.getAttribute('data-library-form-tab') === name;
        tab.classList.toggle('is-active', active);
        tab.setAttribute('aria-selected', String(active));
        tab.tabIndex = active ? 0 : -1;
        if (active) activeTab = tab;
      });
      if (moveFocus && activeTab) activeTab.focus();
    }

    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        showPanel(tab.getAttribute('data-library-form-tab'));
      });
      tab.addEventListener('keydown', function (event) {
        var currentIndex = tabs.indexOf(tab);
        var nextIndex = currentIndex;
        if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % tabs.length;
        else if (event.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
        else if (event.key === 'Home') nextIndex = 0;
        else if (event.key === 'End') nextIndex = tabs.length - 1;
        else return;

        event.preventDefault();
        showPanel(tabs[nextIndex].getAttribute('data-library-form-tab'), true);
      });
    });

    var infoPanel = form.querySelector('[data-library-form-panel="INFO"]');
    var infoNext = form.querySelector('[data-library-info-next]');
    if (infoNext) {
      infoNext.addEventListener('click', function () {
        var invalid = infoPanel && infoPanel.querySelector(':invalid');
        if (invalid) {
          invalid.reportValidity();
          invalid.focus();
          return;
        }
        showPanel('CONTENT', true);
      });
    }

    function primaryVideoId() {
      return videoId && videoId.value ? String(videoId.value) : '';
    }

    function renderPrimaryVideo(item) {
      var id = item && item.id != null ? String(item.id) : primaryVideoId();
      var selected = Boolean(id);
      if (videoSelection) {
        videoSelection.classList.toggle('is-empty', !selected);
        if (selected) videoSelection.setAttribute('data-video-id', id);
        else videoSelection.removeAttribute('data-video-id');
      }
      if (videoName) {
        videoName.textContent = selected
          ? (item && (item.title || item.originalFilename)
            ? item.title || item.originalFilename
            : 'Video từ kho cá nhân · ID ' + id)
          : 'Chưa chọn video từ kho cá nhân';
      }
      if (clearVideo) clearVideo.hidden = !selected;
    }

    function inferExternalProvider(value) {
      var normalized = String(value || '').toLowerCase();
      if (normalized.indexOf('youtu.be') >= 0 || normalized.indexOf('youtube.com') >= 0) return 'YOUTUBE';
      if (normalized.indexOf('vimeo.com') >= 0) return 'VIMEO';
      return '';
    }

    function clearPrimaryVideo(preserveVideoType) {
      if (videoId) videoId.value = '';
      if (provider && provider.value === 'UPLOAD') provider.value = '';
      if (!preserveVideoType && contentType && contentType.value === 'VIDEO') {
        contentType.value = 'RICHTEXT';
      }
      renderPrimaryVideo(null);
    }

    if (clearVideo) clearVideo.addEventListener('click', function () {
      clearPrimaryVideo(false);
    });
    if (videoUrl) {
      videoUrl.addEventListener('input', function () {
        if (!videoUrl.value.trim() || !primaryVideoId()) return;
        var preserveVideoType = contentType && contentType.value === 'VIDEO';
        clearPrimaryVideo(preserveVideoType);
        if (preserveVideoType && provider) {
          provider.value = inferExternalProvider(videoUrl.value);
        }
      });
    }

    if (videoPicker) {
      videoPicker.addEventListener('click', function () {
        if (!window.KshLibraryPicker) {
          if (window.KshToast) window.KshToast.error('Chưa thể mở Kho tài liệu cá nhân');
          return;
        }
        window.KshLibraryPicker.open({
          kind: 'VIDEO',
          selectedIds: primaryVideoId() ? [primaryVideoId()] : [],
          opener: videoPicker,
          onSelect: function (item) {
            if (!item || item.kind !== 'VIDEO') return;
            if (videoId) videoId.value = String(item.id);
            if (provider) provider.value = 'UPLOAD';
            if (videoUrl) videoUrl.value = '';
            renderPrimaryVideo(item);
            showPanel('VIDEO');
          }
        });
      });
    }

    renderPrimaryVideo(null);
    showPanel(resourceOnly ? 'ATTACHMENTS' : (infoPanel ? 'INFO'
      : (contentType && contentType.value === 'VIDEO' ? 'VIDEO' : 'CONTENT')));

    form.addEventListener('submit', function (event) {
      var invalidIdentity = infoPanel && infoPanel.querySelector(':invalid');
      if (invalidIdentity) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPanel('INFO');
        invalidIdentity.reportValidity();
        invalidIdentity.focus();
        return;
      }
      if (primaryVideoId()) {
        if (provider) provider.value = 'UPLOAD';
        if (videoUrl) videoUrl.value = '';
      } else if (contentType && contentType.value === 'VIDEO' && provider && videoUrl) {
        provider.value = inferExternalProvider(videoUrl.value);
      }
    });

    function rows() {
      return Array.prototype.slice.call(list.querySelectorAll('[data-library-asset-id]'));
    }

    function selectedIds() {
      return rows().map(function (row) { return row.getAttribute('data-library-asset-id'); });
    }

    function syncEmptyState() {
      if (empty) empty.hidden = rows().length > 0;
    }

    function bindRemove(row) {
      var remove = row.querySelector('[data-remove-library-asset]');
      if (!remove || remove.dataset.bound === 'true') return;
      remove.dataset.bound = 'true';
      remove.addEventListener('click', function () {
        var title = row.getAttribute('data-library-asset-title') || 'tài liệu';
        row.remove();
        syncEmptyState();
        if (empty) empty.textContent = 'Đã gỡ “' + title + '” khỏi bài giảng. Thay đổi sẽ được lưu khi bạn bấm Lưu.';
      });
    }

    function addAsset(item) {
      if (!item || item.id == null || selectedIds().indexOf(String(item.id)) >= 0) return;
      var title = item.title || item.originalFilename || 'Tài liệu chưa đặt tên';

      var row = document.createElement('div');
      row.className = 'library-selected-asset';
      row.setAttribute('data-library-asset-id', String(item.id));
      row.setAttribute('data-library-asset-title', title);
      row.setAttribute('data-library-asset-kind', item.kind || 'DOCUMENT');

      var copy = document.createElement('span');
      copy.textContent = title;
      var input = document.createElement('input');
      input.type = 'hidden';
      input.name = 'materialAssetIds';
      input.value = String(item.id);
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.setAttribute('data-remove-library-asset', '');
      remove.setAttribute('aria-label', 'Gỡ ' + title);
      remove.textContent = 'Gỡ';

      row.append(copy, input, remove);
      list.appendChild(row);
      bindRemove(row);
      syncEmptyState();
    }

    rows().forEach(bindRemove);
    syncEmptyState();

    openButton.addEventListener('click', function () {
      if (!window.KshLibraryPicker) {
        if (window.KshToast) window.KshToast.error('Chưa thể mở Kho tài liệu cá nhân');
        return;
      }
      window.KshLibraryPicker.open({
        kind: '',
        selectedIds: selectedIds(),
        opener: openButton,
        onSelect: addAsset
      });
    });
  }

  function initPage() {
    document.querySelectorAll('[data-library-lesson-form]').forEach(init);
  }

  window.KshLibraryLessonForm = {init: init};
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initPage);
  } else {
    initPage();
  }
})();
