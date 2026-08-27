(function () {
  'use strict';

  if (location.pathname.startsWith('/practice') || document.querySelector('[data-vocab-drawer]')) return;

  const CREATE_DECK_VALUE = '__create_new_deck__';
  const hangul = /[\u1100-\u11ff\u3130-\u318f\uac00-\ud7af]/;
  let selected = '';
  let pronunciation = '';
  let partOfSpeech = '';
  let dictionaryUrl = '';
  let decksLoaded = false;

  const action = document.createElement('button');
  action.type = 'button';
  action.className = 'kdict-action';
  action.hidden = true;
  action.textContent = '가 Tra Hàn–Việt';
  document.body.appendChild(action);

  const panel = document.createElement('aside');
  panel.className = 'kdict-panel';
  panel.hidden = true;
  panel.innerHTML = `
    <header>
      <div><small>TỪ ĐIỂN DÙNG CHUNG</small><h2>Tra từ Hàn–Việt</h2></div>
      <button type="button" aria-label="Đóng">×</button>
    </header>
    <div class="kdict-word" lang="ko">단어</div>
    <p class="kdict-meta"></p>
    <p class="kdict-status">Bôi đen một từ Hàn trên trang để bắt đầu.</p>
    <label>Nghĩa tiếng Việt<textarea rows="3" maxlength="1000"></textarea></label>
    <label>Lưu vào bộ thẻ
      <select data-ksh-select data-ksh-select-menu-class="kdict-select-menu">
        <option value="">Đang tải bộ thẻ…</option>
      </select>
    </label>
    <div class="kdict-create-deck" hidden>
      <label for="kdictNewDeckTitle">Tên bộ thẻ mới</label>
      <input id="kdictNewDeckTitle" type="text" maxlength="300"
             placeholder="Ví dụ: Từ vựng TOPIK tuần này" autocomplete="off">
      <small>Bộ thẻ cá nhân sẽ được tạo và từ hiện tại được lưu ngay.</small>
    </div>
    <button type="button" class="kdict-save" disabled>Lưu flashcard</button>`;
  document.body.appendChild(panel);

  const close = panel.querySelector('header button');
  const word = panel.querySelector('.kdict-word');
  const meta = panel.querySelector('.kdict-meta');
  const status = panel.querySelector('.kdict-status');
  const meaning = panel.querySelector('textarea');
  const deck = panel.querySelector('select');
  const createDeckBox = panel.querySelector('.kdict-create-deck');
  const newDeckTitle = panel.querySelector('#kdictNewDeckTitle');
  const save = panel.querySelector('.kdict-save');

  function csrf() {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    return token && header && token.content && header.content
      ? { [header.content]: token.content }
      : {};
  }

  async function envelope(response) {
    const payload = await response.json().catch(function () {
      return { ok: false, message: 'Phản hồi máy chủ không hợp lệ.' };
    });
    if (!response.ok || !payload.ok) {
      throw new Error(payload.message || 'Không thể hoàn tất thao tác.');
    }
    return payload.data;
  }

  function creatingDeck() {
    return deck.value === CREATE_DECK_VALUE;
  }

  function refresh() {
    const creating = creatingDeck();
    createDeckBox.hidden = !creating;
    save.textContent = creating ? 'Tạo bộ và lưu flashcard' : 'Lưu flashcard';
    const hasTarget = creating ? newDeckTitle.value.trim() : deck.value;
    save.disabled = !selected || !meaning.value.trim() || !hasTarget;
  }

  function show(message, kind) {
    status.textContent = message;
    status.className = 'kdict-status' + (kind ? ' is-' + kind : '');
  }

  function appendDeckOption(item) {
    const option = document.createElement('option');
    option.value = item.id;
    option.textContent = item.title + ' · ' + (item.cardCount || 0) + ' thẻ';
    const createOption = Array.from(deck.options).find(function (candidate) {
      return candidate.value === CREATE_DECK_VALUE;
    });
    deck.insertBefore(option, createOption || null);
  }

  async function loadDecks() {
    if (decksLoaded) return;
    const data = await envelope(await fetch('/api/korean-dictionary/decks', {
      credentials: 'same-origin'
    }));
    deck.replaceChildren();

    const prompt = document.createElement('option');
    prompt.value = '';
    prompt.textContent = data.decks.length ? 'Chọn bộ thẻ' : 'Chọn hoặc tạo bộ thẻ mới';
    deck.appendChild(prompt);
    data.decks.forEach(appendDeckOption);

    const createOption = document.createElement('option');
    createOption.value = CREATE_DECK_VALUE;
    createOption.textContent = '+ Tạo bộ thẻ mới…';
    deck.appendChild(createOption);
    decksLoaded = true;
  }

  async function lookup() {
    panel.hidden = false;
    word.textContent = selected;
    meaning.value = '';
    meta.textContent = '';
    show('Đang tra Korean Basic Dictionary…');
    refresh();
    try {
      await loadDecks();
      const data = await envelope(await fetch(
        '/api/korean-dictionary/lookup?word=' + encodeURIComponent(selected),
        { credentials: 'same-origin' }
      ));
      if (data.found) {
        selected = data.word;
        word.textContent = data.word;
        meaning.value = data.meaningVi || '';
        pronunciation = data.pronunciation || '';
        partOfSpeech = data.partOfSpeech || '';
        dictionaryUrl = data.dictionaryUrl || '';
        meta.textContent = [pronunciation, partOfSpeech].filter(Boolean).join(' · ');
        show('Đã lấy nghĩa Việt từ KRDICT.', 'success');
      } else {
        show(data.configured
          ? 'Không có kết quả chính xác; bạn có thể nhập nghĩa thủ công.'
          : 'KRDICT chưa được admin cấu hình.', 'error');
      }
      refresh();
    } catch (error) {
      show(error.message, 'error');
    }
  }

  document.addEventListener('mouseup', function () {
    window.setTimeout(function () {
      const selection = window.getSelection();
      const text = (selection ? selection.toString() : '').trim()
        .replace(/^[\s\p{P}\p{S}]+|[\s\p{P}\p{S}]+$/gu, '');
      const activeTag = document.activeElement && document.activeElement.tagName;
      if (!text || text.length > 120 || !hangul.test(text)
          || ['INPUT', 'TEXTAREA', 'SELECT'].includes(activeTag)) {
        action.hidden = true;
        return;
      }
      const range = selection.rangeCount ? selection.getRangeAt(0) : null;
      if (!range) return;
      const rect = range.getBoundingClientRect();
      selected = text;
      action.style.left = Math.max(10, Math.min(innerWidth - 145, rect.left)) + 'px';
      action.style.top = Math.max(64, rect.top - 45) + 'px';
      action.hidden = false;
    }, 0);
  });

  action.addEventListener('click', function () {
    action.hidden = true;
    lookup();
  });
  close.addEventListener('click', function () { panel.hidden = true; });
  meaning.addEventListener('input', refresh);
  newDeckTitle.addEventListener('input', refresh);
  deck.addEventListener('change', function () {
    refresh();
    if (creatingDeck()) window.setTimeout(function () { newDeckTitle.focus(); }, 0);
  });

  save.addEventListener('click', async function () {
    refresh();
    if (save.disabled) return;
    const creating = creatingDeck();
    save.disabled = true;
    show(creating ? 'Đang tạo bộ thẻ và lưu từ…' : 'Đang lưu vào bộ đã chọn…');
    try {
      const data = await envelope(await fetch('/api/korean-dictionary/flashcards', {
        method: 'POST',
        credentials: 'same-origin',
        headers: Object.assign({ 'Content-Type': 'application/json' }, csrf()),
        body: JSON.stringify({
          deckId: creating ? null : Number(deck.value),
          newDeckTitle: creating ? newDeckTitle.value.trim() : null,
          word: selected,
          meaningVi: meaning.value,
          pronunciation: pronunciation,
          partOfSpeech: partOfSpeech,
          dictionaryUrl: dictionaryUrl
        })
      }));

      if (data.deckCreated) {
        appendDeckOption({ id: data.deckId, title: data.deckTitle, cardCount: 1 });
        deck.value = String(data.deckId);
        deck.dispatchEvent(new Event('change', { bubbles: true }));
        newDeckTitle.value = '';
        show('Đã tạo bộ “' + data.deckTitle + '” và lưu flashcard.', 'success');
      } else {
        show(data.alreadySaved
          ? 'Từ đã có trong bộ “' + data.deckTitle + '”.'
          : 'Đã lưu vào bộ “' + data.deckTitle + '”.', 'success');
      }
    } catch (error) {
      show(error.message, 'error');
    } finally {
      refresh();
    }
  });
})();
