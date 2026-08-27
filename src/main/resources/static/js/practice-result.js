(() => {
  'use strict';
  const reducedMotion = window.matchMedia(
    '(prefers-reduced-motion: reduce)'
  );

  function runResultCelebration() {
    const page = document.body;
    const celebration = document.querySelector('[data-result-celebration]');
    if (!celebration
        || page.dataset.resultCelebrationEligible !== 'true') return;

    const stateKey = page.dataset.resultCelebrationKey;
    if (!stateKey || celebration.dataset.played === 'true') return;
    // One celebration per rendered document. A real navigation or F5 creates
    // a new document and intentionally replays the short welcome animation.
    celebration.dataset.played = 'true';

    const deterministicMotionOff =
      window.__KSH_DISABLE_RESULT_MOTION__ === true
      || document.documentElement.dataset.practiceMotion === 'off';
    if (reducedMotion.matches || deterministicMotionOff) return;

    const pieces = [
      ['dot', 10, 70, -10, -52, -25, 0],
      ['star', 17, 62, -18, -76, 32, 45],
      ['dash', 25, 74, -12, -46, 65, 90],
      ['dot', 34, 66, -5, -68, -35, 120],
      ['star', 43, 76, -4, -54, 24, 165],
      ['dash', 53, 70, 4, -78, -62, 30],
      ['dot', 62, 76, 8, -58, 30, 105],
      ['star', 71, 63, 13, -72, 55, 145],
      ['dash', 80, 72, 18, -48, -40, 70],
      ['dot', 89, 65, 11, -62, 28, 190],
      ['star', 21, 82, -8, -44, -52, 215],
      ['dot', 76, 84, 7, -50, 40, 235],
      ['star', 7, 48, -18, -64, -72, 125],
      ['dash', 14, 86, -20, -42, 85, 255],
      ['dot', 28, 54, -8, -82, 20, 310],
      ['star', 38, 88, -5, -58, -42, 345],
      ['dash', 48, 58, 2, -86, 76, 205],
      ['dot', 58, 88, 5, -52, -28, 365],
      ['star', 67, 50, 12, -84, 68, 285],
      ['dash', 75, 91, 16, -46, -74, 395],
      ['dot', 84, 51, 18, -78, 42, 330],
      ['star', 94, 80, 20, -56, -58, 420],
      ['dash', 31, 91, -9, -38, 95, 455],
      ['star', 69, 92, 10, -40, -90, 485]
    ];
    const colors = ['#ffd23f', '#ff6b6b', '#4d7cff', '#35b86b'];

    pieces.forEach(([shape, x, y, dx, dy, rotation, delay], index) => {
      const piece = document.createElement('i');
      piece.className = `pr-result-celebration-piece is-${shape}`;
      piece.style.setProperty('--celebration-x', `${x}%`);
      piece.style.setProperty('--celebration-y', `${y}%`);
      piece.style.setProperty('--celebration-dx', `${dx}px`);
      piece.style.setProperty('--celebration-dy', `${dy}px`);
      piece.style.setProperty('--celebration-rotation', `${rotation}deg`);
      piece.style.setProperty('--celebration-dx-mid', `${dx * 0.45}px`);
      piece.style.setProperty('--celebration-dy-mid', `${dy * 0.58}px`);
      piece.style.setProperty('--celebration-rotation-mid', `${rotation * 0.55}deg`);
      piece.style.setProperty('--celebration-dx-late', `${dx * 0.75}px`);
      piece.style.setProperty('--celebration-dy-late', `${dy * 0.86}px`);
      piece.style.setProperty('--celebration-rotation-late', `${rotation * 0.82}deg`);
      piece.style.setProperty('--celebration-delay', `${delay}ms`);
      piece.style.setProperty('--celebration-color', colors[index % colors.length]);
      celebration.append(piece);
    });

    celebration.hidden = false;
    window.requestAnimationFrame(() => celebration.classList.add('is-active'));
    window.setTimeout(() => {
      celebration.classList.remove('is-active');
      celebration.replaceChildren();
      celebration.hidden = true;
    }, 2850);
  }

  runResultCelebration();

  document.querySelectorAll('[data-speaking-question-drawer-trigger]')
    .forEach((trigger) => {
      const drawerId = trigger.getAttribute('aria-controls');
      const drawer = drawerId ? document.getElementById(drawerId) : null;
      if (!(drawer instanceof HTMLDialogElement)) return;
      const closeButton = drawer.querySelector(
        '[data-speaking-question-drawer-close]'
      );

      const openDrawer = () => {
        if (!drawer.open) drawer.showModal();
        document.body.classList.add('is-speaking-question-drawer-open');
        trigger.setAttribute('aria-expanded', 'true');
        const current = drawer.querySelector('[aria-current="page"]');
        (current || closeButton)?.focus({ preventScroll: true });
      };
      const restoreDrawerState = () => {
        document.body.classList.remove('is-speaking-question-drawer-open');
        trigger.setAttribute('aria-expanded', 'false');
        trigger.focus({ preventScroll: true });
      };
      const closeDrawer = () => {
        if (drawer.open) drawer.close();
        restoreDrawerState();
      };

      trigger.addEventListener('click', openDrawer);
      closeButton?.addEventListener('click', closeDrawer);
      drawer.addEventListener('click', (event) => {
        if (event.target === drawer) closeDrawer();
      });
      drawer.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        closeDrawer();
      });
      drawer.addEventListener('close', () => {
        restoreDrawerState();
      });
    });

  const tabLists = document.querySelectorAll('[data-result-tabs]');
  const hasToken = (value, token) => String(value || '')
    .split(/\s+/)
    .filter(Boolean)
    .includes(token);

  function setDiagnosticGroupExpanded(toggle, expanded) {
    if (!toggle) return;
    const childrenId = toggle.getAttribute('aria-controls');
    const children = childrenId ? document.getElementById(childrenId) : null;
    toggle.setAttribute('aria-expanded', String(expanded));
    if (children) children.hidden = !expanded;
  }

  function closeDiagnosticPicker(picker, restoreFocus = false) {
    if (!picker) return;
    const trigger = picker.querySelector('[data-diagnostic-group-trigger]');
    const menu = picker.querySelector('[data-diagnostic-group-menu]');
    if (!trigger || !menu) return;
    trigger.setAttribute('aria-expanded', 'false');
    menu.hidden = true;
    if (restoreFocus) trigger.focus();
  }

  function syncDiagnosticPicker(select) {
    const picker = select?.closest('.prd-diagnostic-parent-picker');
    const trigger = picker?.querySelector('[data-diagnostic-group-trigger]');
    const value = trigger?.querySelector('[data-diagnostic-group-value]');
    const selected = select?.selectedOptions?.[0];
    if (!trigger || !value || !selected) return;

    value.textContent = selected.textContent.trim();
    picker.querySelectorAll('[data-diagnostic-group-option]').forEach((option) => {
      option.setAttribute(
        'aria-selected',
        String(option.dataset.diagnosticGroupTarget === select.value)
      );
    });
  }

  function enhanceDiagnosticGroupSelect(select, pickerIndex) {
    const picker = select.closest('.prd-diagnostic-parent-picker');
    if (!picker || picker.querySelector('[data-diagnostic-group-trigger]')) return;

    const menuId = `${select.id || `diagnostic-group-${pickerIndex}`}-menu`;
    const control = document.createElement('div');
    control.className = 'prd-diagnostic-custom-select';
    control.dataset.diagnosticGroupPicker = '';

    const trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.className = 'prd-diagnostic-group-trigger';
    trigger.dataset.diagnosticGroupTrigger = '';
    trigger.setAttribute('aria-haspopup', 'listbox');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.setAttribute('aria-controls', menuId);
    trigger.innerHTML = '<span data-diagnostic-group-value></span><i aria-hidden="true"></i>';

    const menu = document.createElement('div');
    menu.id = menuId;
    menu.className = 'prd-diagnostic-group-menu';
    menu.dataset.diagnosticGroupMenu = '';
    menu.setAttribute('role', 'listbox');
    menu.tabIndex = -1;
    menu.hidden = true;

    Array.from(select.options).forEach((sourceOption) => {
      const option = document.createElement('button');
      option.type = 'button';
      option.className = 'prd-diagnostic-group-option';
      option.dataset.diagnosticGroupOption = '';
      option.dataset.diagnosticGroupTarget = sourceOption.value;
      option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', String(sourceOption.selected));
      option.textContent = sourceOption.textContent.trim();
      menu.append(option);
    });

    control.append(trigger, menu);
    select.insertAdjacentElement('afterend', control);
    select.hidden = true;
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');
    syncDiagnosticPicker(select);

    const options = () => Array.from(
      menu.querySelectorAll('[data-diagnostic-group-option]')
    );
    const focusOption = (index) => {
      const items = options();
      if (items.length === 0) return;
      items[(index + items.length) % items.length].focus();
    };
    const selectOption = (option) => {
      select.value = option.dataset.diagnosticGroupTarget || '';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      closeDiagnosticPicker(picker, true);
    };

    trigger.addEventListener('click', () => {
      const opening = trigger.getAttribute('aria-expanded') !== 'true';
      document.querySelectorAll('[data-diagnostic-group-picker]')
        .forEach((candidate) => closeDiagnosticPicker(candidate));
      if (!opening) return;
      trigger.setAttribute('aria-expanded', 'true');
      menu.hidden = false;
      picker.classList.remove('opens-upward');
      const triggerRect = trigger.getBoundingClientRect();
      const availableBelow = window.innerHeight - triggerRect.bottom - 12;
      const availableAbove = triggerRect.top - 12;
      const wantedHeight = Math.min(menu.scrollHeight, 320);
      const opensUpward = availableBelow < wantedHeight
        && availableAbove > availableBelow;
      picker.classList.toggle('opens-upward', opensUpward);
      menu.style.setProperty(
        '--diagnostic-menu-max-height',
        `${Math.max(140, Math.min(320, opensUpward
          ? availableAbove
          : availableBelow))}px`
      );
      const items = options();
      const selectedIndex = Math.max(
        0,
        items.findIndex((item) => item.getAttribute('aria-selected') === 'true')
      );
      items[selectedIndex]?.focus();
    });

    trigger.addEventListener('keydown', (event) => {
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      if (menu.hidden) {
        trigger.click();
        return;
      }
      const items = options();
      focusOption(event.key === 'End' || event.key === 'ArrowUp'
        ? items.length - 1
        : 0);
    });

    menu.addEventListener('keydown', (event) => {
      const items = options();
      const current = items.indexOf(document.activeElement);
      if (event.key === 'Escape') {
        event.preventDefault();
        closeDiagnosticPicker(picker, true);
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        focusOption(current + 1);
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        focusOption(current - 1);
      } else if (event.key === 'Home') {
        event.preventDefault();
        focusOption(0);
      } else if (event.key === 'End') {
        event.preventDefault();
        focusOption(items.length - 1);
      } else if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        const option = document.activeElement.closest(
          '[data-diagnostic-group-option]'
        );
        if (option) selectOption(option);
      }
    });

    menu.addEventListener('click', (event) => {
      const option = event.target.closest('[data-diagnostic-group-option]');
      if (option) selectOption(option);
    });
    select.addEventListener('change', () => syncDiagnosticPicker(select));
  }

  document.querySelectorAll('[data-diagnostic-group-select]')
    .forEach(enhanceDiagnosticGroupSelect);
  document.addEventListener('pointerdown', (event) => {
    document.querySelectorAll('[data-diagnostic-group-picker]').forEach((picker) => {
      if (!picker.contains(event.target)) closeDiagnosticPicker(picker);
    });
  });

  document.querySelectorAll(
    '.prd-writing-overview, .prd-speaking-overview'
  ).forEach((overview) => {
    const triggers = Array.from(
      overview.querySelectorAll('[data-overview-criterion-trigger]')
    );
    const details = Array.from(
      overview.querySelectorAll('[data-overview-criterion-detail]')
    );
    const activateCriterion = (trigger, focus = false) => {
      const targetId = trigger?.getAttribute('aria-controls');
      const target = targetId
        ? details.find((detail) => detail.id === targetId)
        : null;
      if (!target) return;
      triggers.forEach((candidate) => {
        const active = candidate === trigger;
        candidate.classList.toggle('is-active', active);
        candidate.setAttribute('aria-selected', String(active));
        candidate.setAttribute('aria-expanded', String(active));
        if (active) candidate.setAttribute('aria-current', 'true');
        else candidate.removeAttribute('aria-current');
        candidate.tabIndex = active ? 0 : -1;
      });
      details.forEach((detail) => {
        const active = detail === target;
        detail.hidden = !active;
        detail.setAttribute('aria-hidden', String(!active));
      });
      if (focus) trigger.focus({ preventScroll: true });
    };

    triggers.forEach((trigger, index) => {
      trigger.addEventListener('click', (event) => {
        event.preventDefault();
        activateCriterion(trigger);
      });
      trigger.addEventListener('keydown', (event) => {
        let next = null;
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
          next = (index + 1) % triggers.length;
        } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
          next = (index - 1 + triggers.length) % triggers.length;
        } else if (event.key === 'Home') {
          next = 0;
        } else if (event.key === 'End') {
          next = triggers.length - 1;
        }
        if (next == null) return;
        event.preventDefault();
        activateCriterion(triggers[next], true);
      });
    });
    if (triggers.length) activateCriterion(triggers[0]);

    overview.querySelectorAll('[data-overview-coverage-trigger]')
      .forEach((trigger) => {
        trigger.addEventListener('click', () => {
          const targetId = trigger.getAttribute('aria-controls');
          const detail = targetId ? document.getElementById(targetId) : null;
          if (!detail) return;
          const expanded = trigger.getAttribute('aria-expanded') === 'true';
          trigger.setAttribute('aria-expanded', String(!expanded));
          detail.hidden = expanded;
        });
      });
  });

  function resetDiagnosticState(review) {
    if (!review) return;

    review.querySelectorAll(
      '[data-writing-diagnostic-filter], [data-writing-upgrade-filter], '
      + '[data-speaking-diagnostic-filter], [data-speaking-upgrade-filter]'
    ).forEach((filter) => filter.setAttribute('aria-pressed', 'false'));

    review.querySelectorAll(
      '[data-writing-feature], [data-speaking-feature]'
    ).forEach((item) => {
      if (!item.hasAttribute('data-writing-diagnostic-filter')
          && !item.hasAttribute('data-writing-upgrade-filter')
          && !item.hasAttribute('data-speaking-diagnostic-filter')
          && !item.hasAttribute('data-speaking-upgrade-filter')) {
        item.hidden = Boolean(item.closest('[data-requires-child-filter]'));
      }
    });

    review.querySelectorAll('[data-diagnostic-group-toggle]')
      .forEach((toggle) => setDiagnosticGroupExpanded(toggle, false));
    review.querySelectorAll('[data-diagnostic-group-select]')
      .forEach((select) => {
        select.value = '';
        syncDiagnosticPicker(select);
        closeDiagnosticPicker(select.closest('.prd-diagnostic-parent-picker'));
      });

    review.querySelectorAll(
      '[data-writing-occurrence-trigger], [data-speaking-occurrence-trigger]'
    ).forEach((trigger) => trigger.setAttribute('aria-expanded', 'false'));
    review.querySelectorAll('.prd-occurrence-detail').forEach((detail) => {
      detail.hidden = true;
    });
    review.querySelectorAll(
      '[data-writing-zero-chip-empty], [data-speaking-zero-chip-empty]'
    ).forEach((empty) => { empty.hidden = true; });
    review.querySelectorAll(
      '[data-writing-number-feature], [data-speaking-number-feature]'
    ).forEach((number) => { number.hidden = false; });

    review.querySelectorAll(
      '.prd-writing-inline-annotation, .prd-speaking-inline-annotation'
    ).forEach((annotation) => {
      annotation.classList.remove(
        'is-selected', 'is-muted', 'is-upgrade', 'is-occurrence-selected'
      );
    });
    review.querySelectorAll('.prd-writing-blank-answer').forEach((blank) => {
      blank.classList.remove('is-selected');
    });
    review.querySelectorAll(
      '.prd-writing-rewrite, .prd-speaking-rewrite'
    ).forEach((rewrite) => {
      rewrite.classList.remove('is-upgrade-selected', 'is-muted');
    });

    review.querySelectorAll(
      '[data-writing-filter-status], [data-writing-upgrade-filter-status], '
      + '[data-speaking-filter-status], [data-speaking-upgrade-filter-status]'
    ).forEach((status) => {
      const panel = status.closest('[role="tabpanel"]');
      status.textContent = panel?.querySelector(
        '[data-diagnostic-group-select], [data-diagnostic-group-toggle]'
      )
        ? 'Chọn một nhóm tiêu chí để xem các tiêu chí con.'
        : 'Đang hiển thị toàn bộ occurrence có bằng chứng.';
    });
  }

  function activate(tabList, nextTab, focus) {
    const tabs = Array.from(tabList.querySelectorAll(':scope > [role="tab"]'));
    if (!tabs.includes(nextTab)) return;

    const currentTab = tabs.find(
      (tab) => tab.getAttribute('aria-selected') === 'true'
    );
    if (currentTab && currentTab !== nextTab) {
      resetDiagnosticState(
        tabList.closest(
          '[data-writing-active-question], [data-speaking-active-question]'
        )
      );
    }

    tabs.forEach((tab) => {
      const selected = tab === nextTab;
      const panelId = tab.dataset.resultTarget;
      const panel = panelId ? document.getElementById(panelId) : null;

      tab.classList.toggle('is-active', selected);
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      if (panel) panel.hidden = !selected;
    });

    const feedback = tabList.closest(
      '.prd-writing-feedback, .prd-speaking-feedback'
    );
    if (feedback && currentTab !== nextTab) {
      feedback.scrollTop = 0;
      feedback.querySelectorAll(':scope > [role="tabpanel"]')
        .forEach((panel) => { panel.scrollTop = 0; });
    }

    if (focus) nextTab.focus();
  }

  tabLists.forEach((tabList) => {
    const tabs = Array.from(tabList.querySelectorAll(':scope > [role="tab"]'));
    if (tabs.length === 0) return;

    const feedback = tabList.closest(
      '.prd-writing-feedback, .prd-speaking-feedback'
    );
    const reviewLayout = tabList.closest(
      '.prd-writing-review-layout, .prd-speaking-review-layout'
    );
    reviewLayout?.addEventListener('scroll', () => {
      if (window.matchMedia('(min-width: 981px)').matches
          && reviewLayout.scrollTop !== 0) {
        reviewLayout.scrollTop = 0;
      }
    }, { passive: true });
    feedback?.addEventListener('scroll', () => {
      if (window.matchMedia('(min-width: 981px)').matches
          && feedback.scrollTop !== 0) {
        feedback.scrollTop = 0;
      }
    }, { passive: true });

    const initial = tabs.find((tab) => tab.getAttribute('aria-selected') === 'true') || tabs[0];
    activate(tabList, initial, false);

    tabs.forEach((tab) => {
      tab.addEventListener('click', () => activate(tabList, tab, false));
      tab.addEventListener('keydown', (event) => {
        const currentIndex = tabs.indexOf(tab);
        let nextIndex = currentIndex;

        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
          nextIndex = (currentIndex + 1) % tabs.length;
        } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
          nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
        } else if (event.key === 'Home') {
          nextIndex = 0;
        } else if (event.key === 'End') {
          nextIndex = tabs.length - 1;
        } else {
          return;
        }

        event.preventDefault();
        activate(tabList, tabs[nextIndex], true);
      });
    });
  });

  document.querySelectorAll(
    '[data-writing-active-question], [data-speaking-active-question]'
  ).forEach((review) => resetDiagnosticState(review));

  document.querySelectorAll('[data-diagnostic-group-toggle]').forEach((toggle) => {
    toggle.addEventListener('click', () => {
      const groupSet = toggle.closest('[data-diagnostic-chip-groups]');
      const review = toggle.closest(
        '[data-writing-active-question], [data-speaking-active-question]'
      );
      const panel = toggle.closest('[role="tabpanel"]');
      const open = toggle.getAttribute('aria-expanded') !== 'true';

      resetDiagnosticState(review);
      groupSet?.querySelectorAll('[data-diagnostic-group-toggle]')
        .forEach((candidate) => setDiagnosticGroupExpanded(candidate, false));
      if (open) setDiagnosticGroupExpanded(toggle, true);

      const status = panel?.querySelector(
        '[data-writing-filter-status], [data-writing-upgrade-filter-status], '
        + '[data-speaking-filter-status], [data-speaking-upgrade-filter-status]'
      );
      if (status && open) {
        status.textContent = 'Chọn một tiêu chí con để xem đúng occurrence tương ứng.';
      }
    });
  });

  document.querySelectorAll('[data-diagnostic-group-select]').forEach((select) => {
    select.addEventListener('change', () => {
      const targetId = select.value;
      const groupSet = select.closest('[data-diagnostic-chip-groups]');
      const review = select.closest(
        '[data-writing-active-question], [data-speaking-active-question]'
      );
      const panel = select.closest('[role="tabpanel"]');

      resetDiagnosticState(review);
      select.value = targetId;
      syncDiagnosticPicker(select);
      groupSet?.querySelectorAll('[data-diagnostic-group-children]')
        .forEach((children) => { children.hidden = children.id !== targetId; });
      groupSet?.querySelectorAll('[data-diagnostic-group-toggle]')
        .forEach((toggle) => setDiagnosticGroupExpanded(
          toggle,
          toggle.getAttribute('aria-controls') === targetId
        ));

      const status = panel?.querySelector(
        '[data-writing-filter-status], [data-writing-upgrade-filter-status], '
        + '[data-speaking-filter-status], [data-speaking-upgrade-filter-status]'
      );
      if (status) {
        status.textContent = targetId
          ? 'Chọn một tiêu chí con để xem đúng occurrence tương ứng.'
          : 'Chọn một nhóm tiêu chí để xem các tiêu chí con.';
      }
    });
  });

  const writingFilters = document.querySelectorAll(
    '[data-writing-diagnostic-filter], [data-writing-upgrade-filter]'
  );

  writingFilters.forEach((filter) => {
    filter.addEventListener('click', () => {
      const panel = filter.closest('[role="tabpanel"]');
      if (!panel) return;
      const review = filter.closest('[data-writing-active-question]');
      const upgradeFilter = filter.hasAttribute('data-writing-upgrade-filter');
      panel.querySelectorAll('[data-writing-occurrence-trigger]')
        .forEach((trigger) => trigger.setAttribute('aria-expanded', 'false'));
      panel.querySelectorAll('.prd-occurrence-detail')
        .forEach((detail) => { detail.hidden = true; });

      const scopedFilters = Array.from(
        panel.querySelectorAll(
          '[data-writing-diagnostic-filter], [data-writing-upgrade-filter]'
        )
      );
      const findings = Array.from(
        panel.querySelectorAll('[data-writing-feature]')
      ).filter((item) =>
        !item.hasAttribute('data-writing-diagnostic-filter')
        && !item.hasAttribute('data-writing-upgrade-filter')
      );
      const annotations = review
        ? Array.from(
          review.querySelectorAll(
            '.prd-writing-inline-annotation[data-writing-features]'
          )
        )
        : [];
      const knownFindings = review
        ? Array.from(review.querySelectorAll(
          '[data-writing-finding-id][data-writing-feature]'
        )).filter((item) =>
          !item.hasAttribute('data-writing-diagnostic-filter')
          && !item.hasAttribute('data-writing-upgrade-filter'))
        : [];
      const matchingFindingIds = new Set(knownFindings
        .filter((finding) => finding.dataset.writingFeature
          === filter.dataset.writingFeature)
        .map((finding) => finding.dataset.writingFindingId)
        .filter(Boolean));
      const upgradeSuggestions = Array.from(
        panel.querySelectorAll('[data-writing-upgrade-finding-ids]')
      );
      const feature = filter.dataset.writingFeature;
      const activateFilter = filter.getAttribute('aria-pressed') !== 'true';
      const requiresChildFilter = panel.hasAttribute('data-requires-child-filter');

      scopedFilters.forEach((item) => item.setAttribute('aria-pressed', 'false'));
      findings.forEach((finding) => {
        finding.hidden = requiresChildFilter
          ? (!activateFilter || finding.dataset.writingFeature !== feature)
          : (activateFilter && finding.dataset.writingFeature !== feature);
      });
      annotations.forEach((annotation) => {
        const selected = activateFilter
          && hasToken(annotation.dataset.writingFeatures, feature);
        annotation.classList.toggle('is-selected', selected);
        annotation.classList.toggle('is-muted', activateFilter && !selected);
        annotation.classList.toggle('is-upgrade', selected && upgradeFilter);
      });
      upgradeSuggestions.forEach((suggestion) => {
        const selected = activateFilter && upgradeFilter
          && Array.from(matchingFindingIds).some((findingId) =>
            hasToken(suggestion.dataset.writingUpgradeFindingIds, findingId));
        suggestion.classList.toggle('is-upgrade-selected', selected);
        suggestion.classList.toggle(
          'is-muted', activateFilter && upgradeFilter && !selected
        );
      });
      review?.querySelectorAll('[data-writing-number-feature]').forEach((number) => {
        number.hidden = activateFilter
          && number.dataset.writingNumberFeature !== feature;
      });
      const matchingBlankTargets = knownFindings.filter((finding) =>
        activateFilter
        && finding.dataset.writingFeature === feature
        && finding.dataset.writingTargetKind === 'BLANK'
      );
      review?.querySelectorAll('.prd-writing-blank-answer').forEach((blank) => {
        const selected = matchingBlankTargets.some((finding) =>
          (finding.dataset.writingTargetBlankId
            && finding.dataset.writingTargetBlankId === blank.dataset.writingBlankId)
          || (finding.dataset.writingTargetBlankIndex
            && finding.dataset.writingTargetBlankIndex === blank.dataset.writingBlankIndex)
        );
        blank.classList.toggle('is-selected', selected);
      });
      const selectedAnnotationCount = annotations.filter((annotation) =>
        annotation.classList.contains('is-selected')
        && !annotation.closest('.prd-writing-blank-answer')).length;
      const selectedBlankCount = review
        ? Array.from(review.querySelectorAll('.prd-writing-blank-answer.is-selected')).length
        : 0;

      const zeroState = panel.querySelector('[data-writing-zero-chip-empty]');
      if (zeroState) {
        zeroState.hidden = !(activateFilter
          && Number(filter.dataset.writingChipCount || 0) === 0);
      }

      const status = panel.querySelector(
        '[data-writing-filter-status], [data-writing-upgrade-filter-status]'
      );
      if (!activateFilter) {
        if (status) status.textContent = panel.querySelector(
          '[data-diagnostic-group-select]'
        )?.value || panel.querySelector(
          '[data-diagnostic-group-toggle][aria-expanded="true"]'
        )
          ? 'Chọn một tiêu chí con để xem đúng occurrence tương ứng.'
          : (requiresChildFilter
            ? 'Chọn một nhóm tiêu chí để xem các tiêu chí con.'
            : 'Đang hiển thị toàn bộ occurrence có bằng chứng.');
        return;
      }

      filter.setAttribute('aria-pressed', 'true');
      const visibleCount = findings.filter(
        (finding) => finding.dataset.writingFeature === feature
      ).length;
      if (status) {
        if (upgradeFilter) {
          const markedSources = [];
          if (selectedAnnotationCount > 0) {
            markedSources.push(`${selectedAnnotationCount} đoạn trong bài làm`);
          }
          if (selectedBlankCount > 0) {
            markedSources.push(`${selectedBlankCount} ô trả lời`);
          }
          const sourceSummary = markedSources.length > 0
            ? markedSources.join(' và ')
            : '0 vùng trong bài làm';
          status.textContent = `Đang đánh dấu ${sourceSummary} và ${upgradeSuggestions.filter((suggestion) =>
            suggestion.classList.contains('is-upgrade-selected')).length} gợi ý nâng cấp.`;
        } else {
          status.textContent = `Đang hiển thị ${visibleCount} phản hồi phù hợp.`;
        }
      }
    });
  });

  document.querySelectorAll('[data-writing-splitter]').forEach((splitter) => {
    const shell = splitter.closest('.prd-writing-shell');
    if (!shell) return;

    const min = Number(splitter.getAttribute('aria-valuemin')) || 35;
    const max = Number(splitter.getAttribute('aria-valuemax')) || 65;
    const clamp = (value) => Math.min(max, Math.max(min, value));
    const setSplit = (value) => {
      const next = Math.round(clamp(value) * 10) / 10;
      shell.style.setProperty('--writing-split', `${next}%`);
      splitter.setAttribute('aria-valuenow', String(Math.round(next)));
    };
    const setFromPointer = (clientX) => {
      const bounds = shell.getBoundingClientRect();
      if (!bounds.width) return;
      setSplit(((clientX - bounds.left) / bounds.width) * 100);
    };

    splitter.addEventListener('pointerdown', (event) => {
      splitter.classList.add('is-dragging');
      splitter.setPointerCapture(event.pointerId);
      setFromPointer(event.clientX);
    });
    splitter.addEventListener('pointermove', (event) => {
      if (!splitter.classList.contains('is-dragging')) return;
      setFromPointer(event.clientX);
    });
    splitter.addEventListener('pointerup', (event) => {
      splitter.classList.remove('is-dragging');
      if (splitter.hasPointerCapture(event.pointerId)) {
        splitter.releasePointerCapture(event.pointerId);
      }
    });
    splitter.addEventListener('pointercancel', () => {
      splitter.classList.remove('is-dragging');
    });
    splitter.addEventListener('keydown', (event) => {
      const current = Number(splitter.getAttribute('aria-valuenow')) || 52;
      let next = null;
      if (event.key === 'ArrowLeft') next = current - 2;
      if (event.key === 'ArrowRight') next = current + 2;
      if (event.key === 'Home') next = min;
      if (event.key === 'End') next = max;
      if (next == null) return;
      event.preventDefault();
      setSplit(next);
    });
  });

  const speakingFilters = document.querySelectorAll(
    '[data-speaking-diagnostic-filter], [data-speaking-upgrade-filter]'
  );

  document.querySelectorAll('[data-speaking-filter-status]').forEach((status) => {
    status.textContent = status.closest('[data-requires-child-filter]')
      ? 'Chọn một nhóm tiêu chí để xem các tiêu chí con.'
      : 'Đang hiển thị toàn bộ occurrence có bằng chứng.';
  });

  speakingFilters.forEach((filter) => {
    filter.addEventListener('click', () => {
      const panel = filter.closest('[role="tabpanel"]');
      if (!panel) return;
      const review = filter.closest('[data-speaking-active-question]');
      const upgradeFilter = filter.hasAttribute('data-speaking-upgrade-filter');
      panel.querySelectorAll('[data-speaking-occurrence-trigger]')
        .forEach((trigger) => trigger.setAttribute('aria-expanded', 'false'));
      panel.querySelectorAll('.prd-occurrence-detail')
        .forEach((detail) => { detail.hidden = true; });

      const scopedFilters = Array.from(
        panel.querySelectorAll(
          '[data-speaking-diagnostic-filter], [data-speaking-upgrade-filter]'
        )
      );
      const findings = Array.from(
        panel.querySelectorAll('[data-speaking-feature]')
      ).filter((item) =>
        !item.hasAttribute('data-speaking-diagnostic-filter')
        && !item.hasAttribute('data-speaking-upgrade-filter')
      );
      const annotations = review
        ? Array.from(
          review.querySelectorAll(
            '.prd-speaking-inline-annotation[data-speaking-features]'
          )
        )
        : [];
      const upgradeSuggestions = Array.from(
        panel.querySelectorAll('[data-speaking-upgrade-feature]')
      );
      const feature = filter.dataset.speakingFeature;
      const activateFilter = filter.getAttribute('aria-pressed') !== 'true';
      const requiresChildFilter = panel.hasAttribute('data-requires-child-filter');

      scopedFilters.forEach((item) => item.setAttribute('aria-pressed', 'false'));
      findings.forEach((finding) => {
        finding.hidden = requiresChildFilter
          ? (!activateFilter || finding.dataset.speakingFeature !== feature)
          : (activateFilter && finding.dataset.speakingFeature !== feature);
      });
      annotations.forEach((annotation) => {
        const selected = activateFilter
          && hasToken(annotation.dataset.speakingFeatures, feature);
        annotation.classList.toggle('is-selected', selected);
        annotation.classList.toggle('is-muted', activateFilter && !selected);
        annotation.classList.toggle('is-upgrade', selected && upgradeFilter);
      });
      upgradeSuggestions.forEach((suggestion) => {
        const selected = activateFilter && upgradeFilter
          && suggestion.dataset.speakingUpgradeFeature === feature;
        suggestion.classList.toggle('is-upgrade-selected', selected);
        suggestion.classList.toggle(
          'is-muted', activateFilter && upgradeFilter && !selected
        );
      });
      review?.querySelectorAll('[data-speaking-number-feature]').forEach((number) => {
        number.hidden = activateFilter
          && number.dataset.speakingNumberFeature !== feature;
      });

      const zeroState = panel.querySelector('[data-speaking-zero-chip-empty]');
      if (zeroState) {
        zeroState.hidden = !(activateFilter
          && Number(filter.dataset.speakingChipCount || 0) === 0);
      }

      const status = panel.querySelector(
        '[data-speaking-filter-status], [data-speaking-upgrade-filter-status]'
      );
      if (!activateFilter) {
        if (status) {
          status.textContent = panel.querySelector(
            '[data-diagnostic-group-select]'
          )?.value || panel.querySelector(
            '[data-diagnostic-group-toggle][aria-expanded="true"]'
          )
            ? 'Chọn một tiêu chí con để xem đúng occurrence tương ứng.'
            : (requiresChildFilter
              ? 'Chọn một nhóm tiêu chí để xem các tiêu chí con.'
              : (upgradeFilter ? ''
                : 'Đang hiển thị toàn bộ occurrence có bằng chứng.'));
        }
        return;
      }

      filter.setAttribute('aria-pressed', 'true');
      const visibleCount = findings.filter(
        (finding) => finding.dataset.speakingFeature === feature
      ).length;
      if (status) {
        status.textContent =
          upgradeFilter
            ? `Đang đánh dấu ${annotations.filter((annotation) =>
              hasToken(annotation.dataset.speakingFeatures, feature)).length} đoạn trong bản chép lời và ${upgradeSuggestions.filter((suggestion) =>
              suggestion.classList.contains('is-upgrade-selected')).length} gợi ý nâng cấp.`
            : `Đang hiển thị ${visibleCount} phản hồi phù hợp.`;
      }
    });
  });

  function activateOccurrence(trigger, kind) {
    const panel = trigger.closest('[role="tabpanel"]');
    if (!panel) return;
    const card = trigger.closest(
      kind === 'writing'
        ? '[data-writing-occurrence]'
        : '[data-speaking-occurrence]'
    );
    if (!card) return;
    const identity = kind === 'writing'
      ? card.dataset.writingOccurrence
      : card.dataset.speakingOccurrence;
    const expanded = trigger.getAttribute('aria-expanded') === 'true';

    panel.querySelectorAll(
      '[data-writing-occurrence-trigger], [data-speaking-occurrence-trigger]'
    ).forEach((candidate) => candidate.setAttribute('aria-expanded', 'false'));
    panel.querySelectorAll('.prd-occurrence-detail').forEach((detail) => {
      detail.hidden = true;
    });
    const review = trigger.closest(
      '[data-writing-active-question], [data-speaking-active-question]'
    );
    review?.querySelectorAll(
      '.prd-writing-inline-annotation, .prd-speaking-inline-annotation'
    ).forEach((annotation) => {
      const ids = kind === 'writing'
        ? annotation.dataset.writingFindingIds
        : annotation.dataset.speakingFindingIds;
      annotation.classList.toggle(
        'is-occurrence-selected', !expanded && hasToken(ids, identity)
      );
    });
    if (kind === 'writing') {
      review?.querySelectorAll('.prd-writing-blank-answer').forEach((blank) => {
        const selected = !expanded
          && card.dataset.writingTargetKind === 'BLANK'
          && ((card.dataset.writingTargetBlankId
              && card.dataset.writingTargetBlankId === blank.dataset.writingBlankId)
            || (card.dataset.writingTargetBlankIndex
              && card.dataset.writingTargetBlankIndex === blank.dataset.writingBlankIndex));
        blank.classList.toggle('is-selected', selected);
      });
    }
    if (expanded) return;

    trigger.setAttribute('aria-expanded', 'true');
    const detailId = trigger.getAttribute('aria-controls');
    const detail = detailId ? document.getElementById(detailId) : null;
    if (detail) detail.hidden = false;
  }

  document.querySelectorAll('[data-writing-occurrence-trigger]').forEach((trigger) => {
    trigger.addEventListener('click', () => activateOccurrence(trigger, 'writing'));
  });
  document.querySelectorAll('[data-speaking-occurrence-trigger]').forEach((trigger) => {
    trigger.addEventListener('click', () => activateOccurrence(trigger, 'speaking'));
  });

  document.querySelectorAll(
    '.prd-writing-inline-annotation[data-writing-finding-ids], '
    + '.prd-speaking-inline-annotation[data-speaking-finding-ids]'
  ).forEach((annotation) => {
    annotation.addEventListener('click', () => {
      const writing = annotation.classList.contains('prd-writing-inline-annotation');
      const ids = String(writing
        ? annotation.dataset.writingFindingIds
        : annotation.dataset.speakingFindingIds).split(/\s+/).filter(Boolean);
      const prefix = writing ? 'writing-finding-' : 'speaking-finding-';
      const card = ids.map((id) => document.getElementById(`${prefix}${id}`))
        .find(Boolean);
      const trigger = card?.querySelector(
        writing
          ? '[data-writing-occurrence-trigger]'
          : '[data-speaking-occurrence-trigger]'
      );
      if (!trigger) return;
      const groupChildren = card.closest('[data-diagnostic-group-children]');
      if (groupChildren?.hidden) {
        const groupSelect = groupChildren.closest(
          '[data-diagnostic-chip-groups]'
        )?.querySelector('[data-diagnostic-group-select]');
        if (groupSelect) {
          groupSelect.value = groupChildren.id;
          syncDiagnosticPicker(groupSelect);
        }
        const groupToggle = document.querySelector(
          `[data-diagnostic-group-toggle][aria-controls="${groupChildren.id}"]`
        );
        setDiagnosticGroupExpanded(groupToggle, true);
        groupChildren.hidden = false;
      }
      card.hidden = false;
      activateOccurrence(trigger, writing ? 'writing' : 'speaking');
      card.scrollIntoView({
        behavior: reducedMotion.matches ? 'auto' : 'smooth',
        block: 'center'
      });
    });
  });

  document.querySelectorAll('[data-speaking-splitter]').forEach((splitter) => {
    const shell = splitter.closest('.prd-speaking-shell');
    if (!shell) return;

    const min = Number(splitter.getAttribute('aria-valuemin')) || 35;
    const max = Number(splitter.getAttribute('aria-valuemax')) || 65;
    const clamp = (value) => Math.min(max, Math.max(min, value));
    const setSplit = (value) => {
      const next = Math.round(clamp(value) * 10) / 10;
      shell.style.setProperty('--speaking-split', `${next}%`);
      splitter.setAttribute('aria-valuenow', String(Math.round(next)));
    };
    const setFromPointer = (clientX) => {
      const bounds = shell.getBoundingClientRect();
      if (!bounds.width) return;
      setSplit(((clientX - bounds.left) / bounds.width) * 100);
    };

    splitter.addEventListener('pointerdown', (event) => {
      splitter.classList.add('is-dragging');
      splitter.setPointerCapture(event.pointerId);
      setFromPointer(event.clientX);
    });
    splitter.addEventListener('pointermove', (event) => {
      if (!splitter.classList.contains('is-dragging')) return;
      setFromPointer(event.clientX);
    });
    splitter.addEventListener('pointerup', (event) => {
      splitter.classList.remove('is-dragging');
      if (splitter.hasPointerCapture(event.pointerId)) {
        splitter.releasePointerCapture(event.pointerId);
      }
    });
    splitter.addEventListener('pointercancel', () => {
      splitter.classList.remove('is-dragging');
    });
    splitter.addEventListener('keydown', (event) => {
      const current = Number(splitter.getAttribute('aria-valuenow')) || 52;
      let next = null;
      if (event.key === 'ArrowLeft') next = current - 2;
      if (event.key === 'ArrowRight') next = current + 2;
      if (event.key === 'Home') next = min;
      if (event.key === 'End') next = max;
      if (next == null) return;
      event.preventDefault();
      setSplit(next);
    });
  });

  const annotationSelectors = [
    '.prd-writing-inline-annotation',
    '.prd-speaking-inline-annotation'
  ].join(', ');
  let floatingTooltip = null;
  let floatingAnchor = null;

  function hideAnnotationTooltip() {
    if (floatingTooltip) floatingTooltip.remove();
    floatingTooltip = null;
    floatingAnchor = null;
  }

  function positionAnnotationTooltip() {
    if (!floatingTooltip || !floatingAnchor) return;

    const anchor = floatingAnchor.getBoundingClientRect();
    const tooltip = floatingTooltip.getBoundingClientRect();
    const gutter = 12;
    const gap = 9;
    const left = Math.min(
      Math.max(anchor.left, gutter),
      Math.max(gutter, window.innerWidth - tooltip.width - gutter)
    );
    let top = anchor.top - tooltip.height - gap;
    if (top < gutter) top = anchor.bottom + gap;
    top = Math.min(
      Math.max(top, gutter),
      Math.max(gutter, window.innerHeight - tooltip.height - gutter)
    );

    floatingTooltip.style.left = `${Math.round(left)}px`;
    floatingTooltip.style.top = `${Math.round(top)}px`;
  }

  function showAnnotationTooltip(annotation) {
    if (annotation.classList.contains('prd-speaking-inline-annotation')
        && !annotation.classList.contains('is-selected')) {
      return;
    }
    const source = annotation.querySelector(
      '.prd-writing-inline-tooltip, .prd-speaking-inline-tooltip'
    );
    if (!source) return;

    hideAnnotationTooltip();
    const tooltip = document.createElement('div');
    tooltip.className = 'prd-inline-floating-tooltip';
    tooltip.setAttribute('aria-hidden', 'true');
    Array.from(source.childNodes).forEach((node) => {
      tooltip.appendChild(node.cloneNode(true));
    });
    document.body.appendChild(tooltip);
    floatingTooltip = tooltip;
    floatingAnchor = annotation;
    positionAnnotationTooltip();
  }

  document.querySelectorAll(annotationSelectors).forEach((annotation) => {
    annotation.addEventListener(
      'pointerenter',
      () => showAnnotationTooltip(annotation)
    );
    annotation.addEventListener('pointerleave', hideAnnotationTooltip);
    annotation.addEventListener(
      'focusin',
      () => showAnnotationTooltip(annotation)
    );
    annotation.addEventListener('focusout', hideAnnotationTooltip);
  });

  window.addEventListener('resize', positionAnnotationTooltip);
  window.addEventListener('scroll', positionAnnotationTooltip, true);

  const objectiveShell = document.querySelector(
    '[data-result-detail-kind="OBJECTIVE_DETAIL"]'
  );

  if (objectiveShell) {
    const groupPanels = Array.from(
      objectiveShell.querySelectorAll('[data-objective-group-panel]')
    );
    const questionPanels = Array.from(
      objectiveShell.querySelectorAll('[data-objective-question-panel]')
    );
    const sourcePanels = Array.from(
      objectiveShell.querySelectorAll('[data-objective-source-panel]')
    );
    const questionLinks = Array.from(
      objectiveShell.querySelectorAll('[data-objective-question-link]')
    );
    const sourceLinks = Array.from(
      objectiveShell.querySelectorAll('[data-objective-source-link]')
    );
    const groupNavItems = Array.from(
      objectiveShell.querySelectorAll('[data-objective-group-nav-item]')
    );
    const materialPinButtons = Array.from(
      objectiveShell.querySelectorAll('[data-objective-material-pin]')
    );
    const objectiveSplitters = Array.from(
      objectiveShell.querySelectorAll('[data-objective-splitter]')
    );
    const objectiveAttemptId = objectiveShell.dataset.objectiveAttemptId || 'unknown';
    const materialPinStorageKey =
      `ksh:practice-result-detail:${objectiveAttemptId}:pinned-material-groups`;
    const objectiveSplitStorageKey =
      `ksh:practice-result-detail:${objectiveAttemptId}:split-ratio`;

    function setObjectiveSplitRatio(rawRatio) {
      const ratio = Math.max(32, Math.min(68, Number(rawRatio) || 50));
      groupPanels.forEach((panel) => {
        panel.style.setProperty('--prd-objective-split', `${ratio}%`);
      });
      objectiveSplitters.forEach((splitter) => {
        splitter.setAttribute('aria-valuenow', String(Math.round(ratio)));
      });
      return ratio;
    }

    let objectiveSplitRatio = 50;
    try {
      objectiveSplitRatio = setObjectiveSplitRatio(
        window.localStorage.getItem(objectiveSplitStorageKey) || 50
      );
    } catch (error) {
      objectiveSplitRatio = setObjectiveSplitRatio(50);
    }

    function persistObjectiveSplitRatio() {
      try {
        window.localStorage.setItem(
          objectiveSplitStorageKey,
          String(Math.round(objectiveSplitRatio * 100) / 100)
        );
      } catch (error) {
        // The splitter remains functional when browser storage is unavailable.
      }
    }

    objectiveSplitters.forEach((splitter) => {
      const panel = splitter.closest('[data-objective-group-panel]');
      if (!panel) return;
      const moveToPointer = (event) => {
        const bounds = panel.getBoundingClientRect();
        if (!bounds.width) return;
        objectiveSplitRatio = setObjectiveSplitRatio(
          ((event.clientX - bounds.left) / bounds.width) * 100
        );
      };
      splitter.addEventListener('pointerdown', (event) => {
        event.preventDefault();
        splitter.classList.add('is-dragging');
        splitter.setPointerCapture(event.pointerId);
        moveToPointer(event);
      });
      splitter.addEventListener('pointermove', (event) => {
        if (!splitter.classList.contains('is-dragging')) return;
        moveToPointer(event);
      });
      splitter.addEventListener('pointerup', (event) => {
        splitter.classList.remove('is-dragging');
        if (splitter.hasPointerCapture(event.pointerId)) {
          splitter.releasePointerCapture(event.pointerId);
        }
        persistObjectiveSplitRatio();
      });
      splitter.addEventListener('pointercancel', () => {
        splitter.classList.remove('is-dragging');
      });
      splitter.addEventListener('keydown', (event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        if (event.key === 'Home') objectiveSplitRatio = setObjectiveSplitRatio(32);
        else if (event.key === 'End') objectiveSplitRatio = setObjectiveSplitRatio(68);
        else objectiveSplitRatio = setObjectiveSplitRatio(
          objectiveSplitRatio + (event.key === 'ArrowLeft' ? -2 : 2)
        );
        persistObjectiveSplitRatio();
      });
    });

    let pinnedMaterialGroups = new Set();
    try {
      const stored = window.localStorage.getItem(materialPinStorageKey) || '';
      pinnedMaterialGroups = new Set(stored.split('\n')
        .filter(Boolean)
        .map((value) => decodeURIComponent(value)));
    } catch (error) {
      pinnedMaterialGroups = new Set();
    }

    function persistMaterialPins() {
      try {
        window.localStorage.setItem(
          materialPinStorageKey,
          Array.from(pinnedMaterialGroups)
            .map((value) => encodeURIComponent(value))
            .join('\n')
        );
      } catch (error) {
        // Result review stays functional when persistent browser storage is unavailable.
      }
    }

    function paintMaterialPins() {
      materialPinButtons.forEach((button) => {
        const groupKey = String(button.dataset.objectiveGroupKey || '');
        const pinned = pinnedMaterialGroups.has(groupKey);
        button.setAttribute('aria-pressed', String(pinned));
        button.title = pinned
          ? 'Bỏ ghim học liệu dùng chung'
          : 'Ghim học liệu dùng chung';
        const label = button.querySelector('[data-objective-material-pin-label]');
        if (label) label.textContent = pinned ? 'Bỏ ghim' : 'Ghim học liệu';
      });
      groupPanels.forEach((panel) => {
        panel.classList.toggle(
          'is-material-pinned',
          pinnedMaterialGroups.has(String(panel.dataset.objectiveGroupKey || ''))
        );
      });
    }

    materialPinButtons.forEach((button) => {
      button.addEventListener('click', () => {
        const groupKey = String(button.dataset.objectiveGroupKey || '');
        if (pinnedMaterialGroups.has(groupKey)) pinnedMaterialGroups.delete(groupKey);
        else pinnedMaterialGroups.add(groupKey);
        persistMaterialPins();
        paintMaterialPins();
        button.focus();
      });
    });

    const helper = document.querySelector('[data-objective-helper]');
    const helperToggle = document.querySelector('[data-objective-helper-toggle]');
    const helperClose = helper?.querySelector('[data-objective-helper-close]');
    const helperBackdrop = document.querySelector('[data-objective-helper-backdrop]');
    const helperPageRegions = [
      document.querySelector('.prd-header'),
      objectiveShell
    ].filter(Boolean);
    let helperReturnFocus = null;

    function closeObjectiveHelper() {
      if (!helper || helper.getAttribute('aria-hidden') === 'true') return;
      helper.classList.remove('is-open');
      helper.setAttribute('aria-hidden', 'true');
      helper.setAttribute('inert', '');
      if (helperBackdrop) helperBackdrop.hidden = true;
      helperPageRegions.forEach((region) => region.removeAttribute('inert'));
      if (helperToggle) helperToggle.setAttribute('aria-expanded', 'false');
      document.body.classList.remove('is-objective-helper-open');
      if (helperReturnFocus && typeof helperReturnFocus.focus === 'function') {
        helperReturnFocus.focus();
      }
      helperReturnFocus = null;
    }

    function openObjectiveHelper() {
      if (!helper || !helperToggle) return;
      helperReturnFocus = document.activeElement;
      helper.removeAttribute('inert');
      helper.setAttribute('aria-hidden', 'false');
      helper.classList.add('is-open');
      if (helperBackdrop) helperBackdrop.hidden = false;
      helperPageRegions.forEach((region) => region.setAttribute('inert', ''));
      helperToggle.setAttribute('aria-expanded', 'true');
      document.body.classList.add('is-objective-helper-open');
      helperClose?.focus();
    }

    helperToggle?.addEventListener('click', openObjectiveHelper);
    helperClose?.addEventListener('click', closeObjectiveHelper);
    helperBackdrop?.addEventListener('click', closeObjectiveHelper);
    helper?.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeObjectiveHelper();
        return;
      }
      if (event.key !== 'Tab') return;
      const focusable = Array.from(helper.querySelectorAll(
        'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
      ));
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    });

    paintMaterialPins();

    function activeQuestionFromHash() {
      const anchor = window.location.hash
        .replace(/^#/, '')
        .replace(/-explanation$/, '');
      return questionPanels.find(
        (panel) => panel.dataset.objectiveQuestionId === anchor
      ) || null;
    }

    function setObjectiveQuestion(questionPanel, focusPanel) {
      if (!questionPanel || !questionPanels.includes(questionPanel)) return;

      const questionId = questionPanel.dataset.objectiveQuestionId;
      const sourceId = questionPanel.dataset.objectiveSourceId;
      const groupKey = questionPanel.dataset.objectiveGroupKey;
      const sourcePanel = sourcePanels.find(
        (panel) => panel.dataset.objectiveSourceId === sourceId
      );
      const groupPanel = groupPanels.find(
        (panel) => panel.dataset.objectiveGroupKey === groupKey
      );

      groupPanels.forEach((panel) => {
        const active = panel === groupPanel;
        panel.hidden = !active;
        panel.classList.toggle('is-active', active);
      });

      questionPanels.forEach((panel) => {
        const active = panel === questionPanel;
        panel.hidden = panel.dataset.objectiveGroupKey !== groupKey;
        panel.classList.toggle('is-active', active);
      });

      sourcePanels.forEach((panel) => {
        const active = panel === sourcePanel;
        panel.hidden = !active;
        panel.classList.toggle('is-active', active);
      });

      questionLinks.forEach((link) => {
        const active = link.dataset.objectiveQuestionId === questionId;
        link.classList.toggle('is-active', active);
        if (active) {
          link.setAttribute('aria-current', 'page');
        } else {
          link.removeAttribute('aria-current');
        }
      });

      sourceLinks.forEach((link) => {
        const active = link.dataset.objectiveSourceId === sourceId;
        link.classList.toggle('is-active', active);
        if (active) {
          link.setAttribute('aria-current', 'location');
        } else {
          link.removeAttribute('aria-current');
        }
      });

      groupNavItems.forEach((item) => {
        item.classList.toggle(
          'is-active',
          item.dataset.objectiveGroupKey === groupKey
        );
      });

      const activeGroupNavItem = groupNavItems.find(
        (item) => item.dataset.objectiveGroupKey === groupKey
      );
      if (activeGroupNavItem) {
        activeGroupNavItem.scrollIntoView({
          behavior: reducedMotion.matches ? 'auto' : 'smooth',
          block: 'nearest',
          inline: 'nearest'
        });
      }

      const activeQuestionLink = questionLinks.find(
        (link) => link.dataset.objectiveQuestionId === questionId
      );
      if (activeQuestionLink) {
        activeQuestionLink.scrollIntoView({
          behavior: reducedMotion.matches ? 'auto' : 'smooth',
          block: 'nearest',
          inline: 'center'
        });
      }

      objectiveShell.classList.toggle(
        'is-source-empty',
        sourcePanel?.dataset.objectiveSourceEmpty === 'true'
      );

      if (focusPanel) {
        questionPanel.focus({ preventScroll: true });
        questionPanel.scrollIntoView({
          block: 'start',
          behavior: reducedMotion.matches ? 'auto' : 'smooth'
        });
      }
    }

    function openObjectiveQuestion(questionPanel, focusPanel) {
      setObjectiveQuestion(questionPanel, focusPanel);
      const nextHash = `#${questionPanel.dataset.objectiveQuestionId}`;
      if (window.location.hash !== nextHash) {
        window.history.pushState(null, '', nextHash);
      }
    }

    function activateObjectiveLinkFromKeyboard(event, questionPanel) {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      if (!questionPanel) return;
      event.preventDefault();
      openObjectiveQuestion(questionPanel, true);
    }

    questionLinks.forEach((link) => {
      link.addEventListener('click', (event) => {
        const questionPanel = questionPanels.find(
          (panel) => panel.dataset.objectiveQuestionId
            === link.dataset.objectiveQuestionId
        );
        if (!questionPanel) return;
        event.preventDefault();
        openObjectiveQuestion(questionPanel, true);
      });
      link.addEventListener('keydown', (event) => {
        const questionPanel = questionPanels.find(
          (panel) => panel.dataset.objectiveQuestionId
            === link.dataset.objectiveQuestionId
        );
        activateObjectiveLinkFromKeyboard(event, questionPanel);
      });
    });

    sourceLinks.forEach((link) => {
      link.addEventListener('click', (event) => {
        const questionPanel = questionPanels.find(
          (panel) => panel.dataset.objectiveSourceId
            === link.dataset.objectiveSourceId
        );
        if (!questionPanel) return;
        event.preventDefault();
        openObjectiveQuestion(questionPanel, true);
      });
      link.addEventListener('keydown', (event) => {
        const questionPanel = questionPanels.find(
          (panel) => panel.dataset.objectiveSourceId
            === link.dataset.objectiveSourceId
        );
        activateObjectiveLinkFromKeyboard(event, questionPanel);
      });
    });

    window.addEventListener('hashchange', () => {
      setObjectiveQuestion(
        activeQuestionFromHash() || questionPanels[0],
        true
      );
    });

    const initialQuestion = activeQuestionFromHash() || questionPanels[0];
    setObjectiveQuestion(
      initialQuestion,
      Boolean(window.location.hash)
    );
  }

  function initObjectiveGroupToggle() {
    const toggleBtn = document.getElementById('prGroupToggleBtn');
    const groupList = document.querySelector('.pr-objective-group-list');
    if (!toggleBtn || !groupList) return;

    toggleBtn.addEventListener('click', () => {
      const isCollapsed = groupList.classList.toggle('is-collapsed');
      toggleBtn.setAttribute('data-collapsed', String(isCollapsed));
      const moreText = toggleBtn.querySelector('.pr-toggle-more-text');
      const lessText = toggleBtn.querySelector('.pr-toggle-less-text');
      if (moreText) moreText.style.display = isCollapsed ? 'inline' : 'none';
      if (lessText) lessText.style.display = isCollapsed ? 'none' : 'inline';
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initObjectiveGroupToggle);
  } else {
    initObjectiveGroupToggle();
  }
})();
