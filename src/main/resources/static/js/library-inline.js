(function () {
  'use strict';

  const dialog = document.querySelector('[data-library-editor-dialog]');
  const editorContent = dialog?.querySelector('[data-library-editor-content]');
  const dropSurface = dialog?.querySelector('[data-library-drop-surface]');
  let editorRequestSequence = 0;
  let editorRequestController = null;

  const distributionForm = document.querySelector('.library-subject-distribution-form');
  if (distributionForm) {
    const submit = distributionForm.querySelector('.library-distribute-submit');
    const selectable = [...distributionForm.querySelectorAll('input[name="classIds"]:not(:disabled)')];
    const syncDistributionButton = () => {
      if (submit) submit.disabled = !selectable.some(input => input.checked);
    };
    selectable.forEach(input => input.addEventListener('change', syncDistributionButton));
    syncDistributionButton();
  }

  function csrf() { return document.querySelector('input[name="_csrf"]'); }

  async function postForm(url, values) {
    const data = new FormData();
    const token = csrf();
    if (token) data.append(token.name, token.value);
    Object.entries(values).forEach(([key, value]) => {
      (Array.isArray(value) ? value : [value]).forEach(item => data.append(key, item));
    });
    const response = await fetch(url, {
      method: 'POST', body: data, headers: { 'X-Requested-With': 'XMLHttpRequest' }
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.message || 'Không thể lưu thay đổi');
    return payload;
  }

  document.querySelectorAll('.library-chapter-toggle').forEach(toggle => {
    toggle.addEventListener('click', () => {
      const chapter = toggle.closest('.library-chapter');
      const open = chapter.classList.toggle('is-open');
      toggle.setAttribute('aria-expanded', String(open));
    });
  });

  document.querySelectorAll('[data-inline-edit]').forEach(button => {
    const input = button.parentElement.querySelector('.library-title-input');
    if (!input) return;
    let original = input.value;
    const finish = async save => {
      if (input.hidden) return;
      const value = input.value.trim();
      if (!save || !value) input.value = original;
      else if (value !== original) {
        input.disabled = true;
        try {
          await postForm(button.dataset.saveUrl, { title: value });
          original = value;
          button.textContent = value;
        } catch (error) {
          input.value = original;
          window.alert(error.message);
        } finally { input.disabled = false; }
      }
      input.hidden = true;
      button.hidden = false;
    };
    button.addEventListener('click', event => {
      event.stopPropagation();
      button.hidden = true;
      input.hidden = false;
      input.focus();
      input.select();
    });
    input.addEventListener('blur', () => finish(true));
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); input.blur(); }
      if (event.key === 'Escape') { event.preventDefault(); finish(false); }
    });
  });

  const chapterTree = document.querySelector('.library-subject-tree');
  let draggedChapter = null;
  let dragArmed = false;
  let originalOrder = '';
  let reorderSaving = false;
  const order = () => chapterTree
    ? [...chapterTree.querySelectorAll('.library-chapter')].map(row => row.dataset.chapterNumber).join(',')
    : '';

  async function persistOrder() {
    if (!chapterTree || reorderSaving || order() === originalOrder) return;
    const subjectId = new URLSearchParams(window.location.search).get('subjectId');
    if (!subjectId) return;
    reorderSaving = true;
    chapterTree.classList.add('is-saving-order');
    try {
      await postForm(`/lecturer/library/templates/subjects/${subjectId}/chapters/reorder`, {
        chapterNumbers: [...chapterTree.querySelectorAll('.library-chapter')]
          .map(row => row.dataset.chapterNumber)
      });
      window.location.reload();
    } catch (error) {
      window.alert(error.message);
      window.location.reload();
    }
  }

  document.querySelectorAll('.library-drag-handle').forEach(handle => {
    handle.addEventListener('pointerdown', () => { dragArmed = true; });
    handle.addEventListener('pointerup', () => { dragArmed = false; });
  });
  document.querySelectorAll('.library-chapter').forEach(chapter => {
    chapter.addEventListener('dragstart', event => {
      if (event.target.closest('.library-lesson-row')) return;
      if (!dragArmed) { event.preventDefault(); return; }
      originalOrder = order();
      draggedChapter = chapter;
      chapter.classList.add('is-dragging');
      event.dataTransfer.effectAllowed = 'move';
    });
    chapter.addEventListener('dragover', event => {
      if (!draggedChapter || draggedChapter === chapter) return;
      event.preventDefault();
      const box = chapter.getBoundingClientRect();
      chapter.parentElement.insertBefore(draggedChapter,
        event.clientY < box.top + box.height / 2 ? chapter : chapter.nextSibling);
    });
    chapter.addEventListener('drop', event => event.preventDefault());
    chapter.addEventListener('dragend', () => {
      if (!draggedChapter) return;
      chapter.classList.remove('is-dragging');
      draggedChapter = null;
      dragArmed = false;
      window.setTimeout(persistOrder, 0);
    });
  });

  let draggedLesson = null;
  let lessonDragArmed = false;
  document.querySelectorAll('.library-lesson-drag-handle').forEach(handle => {
    handle.addEventListener('pointerdown', () => { lessonDragArmed = true; });
    handle.addEventListener('pointerup', () => { lessonDragArmed = false; });
  });
  document.querySelectorAll('.library-lesson-row[draggable="true"]').forEach(row => {
    row.addEventListener('dragstart', event => {
      event.stopPropagation();
      if (!lessonDragArmed) { event.preventDefault(); return; }
      draggedLesson = row;
      row.classList.add('is-dragging');
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', row.dataset.lessonId);
    });
    row.addEventListener('dragend', event => {
      event.stopPropagation();
      row.classList.remove('is-dragging');
      draggedLesson = null;
      lessonDragArmed = false;
    });
  });
  document.querySelectorAll('.library-chapter.is-editable').forEach(chapter => {
    chapter.addEventListener('dragover', event => {
      if (draggedLesson) event.preventDefault();
    });
    chapter.addEventListener('drop', async event => {
      if (!draggedLesson || reorderSaving) return;
      event.preventDefault();
      event.stopPropagation();
      const row = event.target.closest('.library-lesson-row');
      let before = row;
      if (row && event.clientY > row.getBoundingClientRect().top + row.offsetHeight / 2) {
        before = row.nextElementSibling;
      }
      const values = { chapterNumber: chapter.dataset.chapterNumber };
      if (before?.dataset.lessonId) values.beforeId = before.dataset.lessonId;
      reorderSaving = true;
      try {
        await postForm(`/lecturer/library/templates/${draggedLesson.dataset.lessonId}/move`, values);
        window.location.reload();
      } catch (error) {
        reorderSaving = false;
        window.alert(error.message);
      }
    });
  });

  function fileKey(file) {
    return `${file.name}:${file.size}:${file.lastModified}`;
  }

  function setFiles(input, files, append) {
    if (!input) return;
    const transfer = new DataTransfer();
    const merged = append ? [...input.files, ...files] : [...files];
    const unique = new Map(merged.map(file => [fileKey(file), file]));
    unique.forEach(file => transfer.items.add(file));
    input.files = transfer.files;
    input.dispatchEvent(new CustomEvent('libraryfileschange', { bubbles: true }));
  }

  function acceptFiles(form, fileList) {
    const files = [...fileList];
    if (!files.length) return;
    setFiles(form.querySelector('[name="materialUploads"]'), files, true);
  }

  function uploadForm(form, submit, fileCount) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', form.dataset.inlineAction || form.action);
      xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
      xhr.upload.addEventListener('progress', event => {
        if (!submit || !event.lengthComputable) return;
        submit.textContent = `Đang tải ${Math.round(event.loaded * 100 / event.total)}%`;
      });
      xhr.addEventListener('load', () => {
        let payload = {};
        try { payload = JSON.parse(xhr.responseText || '{}'); } catch (ignored) { /* noop */ }
        if (xhr.status >= 200 && xhr.status < 300) resolve(payload);
        else reject(new Error(payload.message || 'Không thể lưu bài học'));
      });
      xhr.addEventListener('error', () => reject(new Error('Mất kết nối khi tải tài nguyên')));
      if (submit) submit.textContent = fileCount ? `Đang tải 0%` : 'Đang lưu…';
      xhr.send(new FormData(form));
    });
  }

  function initializeEditorForm(form) {
    if (!form || form.dataset.libraryEditorReady === 'true') return;
    form.dataset.libraryEditorReady = 'true';
    const inlineDialogForm = Boolean(dialog && dialog.contains(form));
    if (window.KshLibraryLessonForm) window.KshLibraryLessonForm.init(form);
    const richValue = form.querySelector('[data-library-richtext-value]');
    const richHost = form.querySelector('[data-library-richtext-editor]');
    let editor = null;
    if (richHost && window.Quill) {
      editor = new window.Quill(richHost, {
        theme: 'snow', placeholder: 'Nhập nội dung bài học…',
        modules: { toolbar: [[{ header: [2, 3, false] }], ['bold', 'italic', 'underline'],
          [{ list: 'ordered' }, { list: 'bullet' }], ['link'], ['clean']] }
      });
      editor.clipboard.dangerouslyPasteHTML(richValue?.value || '');
      const syncRichEditor = () => {
        if (richValue) richValue.value = editor.root.innerHTML;
        const hasContent = editor.getText().trim().length > 0
          || Boolean(editor.root.querySelector('img, video, iframe, table'));
        editor.root.classList.toggle('has-visible-content', hasContent);
      };
      editor.on('text-change', syncRichEditor);
      editor.root.addEventListener('input', syncRichEditor);
      syncRichEditor();
    } else if (richHost) {
      richHost.contentEditable = 'true';
      richHost.innerHTML = richValue?.value || '';
      richHost.addEventListener('input', () => { if (richValue) richValue.value = richHost.innerHTML; });
    }

    form.querySelector('[data-library-cancel]')?.addEventListener('click', event => {
      if (inlineDialogForm && dialog?.open) {
        dialog.close();
        return;
      }
      const cancelUrl = event.currentTarget.dataset.libraryCancelUrl;
      window.location.assign(cancelUrl || '/lecturer/library/templates');
    });
    const input = form.querySelector('[name="materialUploads"]');
    const zone = form.querySelector('[data-library-file-dropzone]');
    let selectedFiles = input ? [...input.files] : [];
    const renderSelectedFiles = () => {
      const list = form.querySelector('[data-library-file-list]');
      if (!list) return;
      list.replaceChildren();
      selectedFiles.forEach((file, index) => {
        const row = document.createElement('div');
        row.className = 'library-selected-file';
        const name = document.createElement('span');
        name.textContent = file.name;
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'library-selected-file-remove';
        remove.setAttribute('aria-label', `Bỏ ${file.name}`);
        remove.title = 'Bỏ file khỏi danh sách tải lên';
        remove.textContent = '×';
        remove.addEventListener('click', event => {
          event.stopPropagation();
          selectedFiles.splice(index, 1);
          if (input) setFiles(input, selectedFiles, false);
          renderSelectedFiles();
        });
        row.append(name, remove);
        list.append(row);
      });
    };
    form.querySelector('[data-library-choose-files]')?.addEventListener('click', event => {
      event.stopPropagation();
      input?.click();
    });
    zone?.addEventListener('click', event => {
      if (!event.target.closest('button')) input?.click();
    });
    zone?.addEventListener('keydown', event => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      input?.click();
    });
    zone?.addEventListener('dragover', event => {
      if (!Array.from(event.dataTransfer?.types || []).includes('Files')) return;
      event.preventDefault();
    });
    zone?.addEventListener('drop', event => {
      if (!event.dataTransfer?.files?.length) return;
      event.preventDefault();
      event.stopPropagation();
      acceptFiles(form, event.dataTransfer.files);
    });
    input?.addEventListener('change', () => {
      const merged = new Map([...selectedFiles, ...input.files]
        .map(file => [fileKey(file), file]));
      selectedFiles = [...merged.values()];
      setFiles(input, selectedFiles, false);
    });
    input?.addEventListener('libraryfileschange', () => {
      selectedFiles = [...input.files];
      renderSelectedFiles();
    });
    renderSelectedFiles();

    if (!inlineDialogForm) {
      form.addEventListener('submit', () => {
        if (editor && richValue) richValue.value = editor.root.innerHTML;
      });
      return;
    }

    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (editor && richValue) richValue.value = editor.root.innerHTML;
      const submit = form.querySelector('[type="submit"]');
      const originalLabel = submit?.textContent;
      if (submit) submit.disabled = true;
      form.setAttribute('aria-busy', 'true');
      try {
        await uploadForm(form, submit, selectedFiles.length);
        if (submit) submit.textContent = 'Đã lưu';
        if (dialog?.open) dialog.close();
        window.location.replace(window.location.href);
      } catch (error) {
        window.alert(error.message);
        if (submit) { submit.disabled = false; submit.textContent = originalLabel; }
        form.removeAttribute('aria-busy');
      }
    });
  }

  async function openEditor(url) {
    if (!dialog || !editorContent) return;
    const requestId = ++editorRequestSequence;
    editorRequestController?.abort();
    editorRequestController = typeof window.AbortController === 'function'
      ? new window.AbortController()
      : null;
    editorContent.innerHTML = '<div class="library-editor-loading">Đang tải biểu mẫu…</div>';
    document.documentElement.classList.add('library-dialog-open');
    if (!dialog.open) dialog.showModal();
    try {
      const options = { headers: { 'X-Requested-With': 'XMLHttpRequest' } };
      if (editorRequestController) options.signal = editorRequestController.signal;
      const response = await fetch(url, options);
      if (!response.ok) throw new Error('Không thể mở biểu mẫu');
      const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (requestId !== editorRequestSequence || !dialog.open) return;
      const form = parsed.querySelector('[data-library-lesson-form]');
      if (!form) throw new Error('Biểu mẫu không hợp lệ');
      editorContent.replaceChildren(document.importNode(form, true));
      initializeEditorForm(editorContent.querySelector('[data-library-lesson-form]'));
    } catch (error) {
      if (requestId !== editorRequestSequence || error.name === 'AbortError' || !dialog.open) return;
      const message = document.createElement('p');
      message.className = 'library-editor-error';
      message.textContent = error.message;
      editorContent.replaceChildren(message);
    } finally {
      if (requestId === editorRequestSequence) editorRequestController = null;
    }
  }

  function cancelEditorRequest() {
    editorRequestSequence += 1;
    editorRequestController?.abort();
    editorRequestController = null;
  }

  document.querySelectorAll('[data-library-lesson-form]').forEach(form => {
    if (!dialog || !dialog.contains(form)) initializeEditorForm(form);
  });
  document.querySelectorAll('.js-library-editor').forEach(button => {
    button.addEventListener('click', () => openEditor(button.dataset.formUrl));
  });
  dialog?.querySelector('[data-library-editor-close]')?.addEventListener('click', () => dialog.close());
  dialog?.addEventListener('close', () => {
    cancelEditorRequest();
    document.documentElement.classList.remove('library-dialog-open');
  });
  dialog?.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
  const shareButton = document.querySelector('[data-library-share]');
  const distribution = document.querySelector('#libraryDistribution');
  const distributionClose = distribution?.querySelector('[data-library-share-close]');
  let distributionOpener = null;
  let distributionFocusTimer = null;

  function closeDistribution() {
    if (!distribution || distribution.hidden) return;
    if (distributionFocusTimer !== null) {
      window.clearTimeout(distributionFocusTimer);
      distributionFocusTimer = null;
    }
    distribution.hidden = true;
    shareButton?.setAttribute('aria-expanded', 'false');
    const focusTarget = distributionOpener?.isConnected ? distributionOpener : shareButton;
    distributionOpener = null;
    focusTarget?.focus();
  }

  shareButton?.addEventListener('click', event => {
    if (!distribution) return;
    distributionOpener = event.currentTarget;
    distribution.hidden = false;
    shareButton.setAttribute('aria-expanded', 'true');
    distribution.scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (distributionFocusTimer !== null) window.clearTimeout(distributionFocusTimer);
    distributionFocusTimer = window.setTimeout(() => {
      distributionFocusTimer = null;
      if (!distribution.hidden) distribution.querySelector('input[type="search"]')?.focus();
    }, 350);
  });
  distributionClose?.addEventListener('click', closeDistribution);
  distribution?.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDistribution();
    }
  });

  if (dropSurface) {
    ['dragenter', 'dragover'].forEach(name => dropSurface.addEventListener(name, event => {
      if (!Array.from(event.dataTransfer.types || []).includes('Files')) return;
      event.preventDefault();
      dropSurface.classList.add('is-file-dragging');
    }));
    ['dragleave', 'drop'].forEach(name => dropSurface.addEventListener(name, event => {
      if (name === 'drop') event.preventDefault();
      dropSurface.classList.remove('is-file-dragging');
      if (name === 'drop') {
        const form = editorContent.querySelector('[data-library-lesson-form]');
        if (form) acceptFiles(form, event.dataTransfer.files);
      }
    }));
  }
})();
