// Injected into the YouTube tab while it is being captured. When the backend hears a voice it has
// not heard before, a card asks who it is; the answer goes back to the backend, which then uses
// the name in the claims. Built with createElement only: YouTube's Trusted Types policy blocks
// innerHTML.

(() => {
  if (window.__debateCheckerOverlay) return; // injected again on the next capture; this copy keeps working
  window.__debateCheckerOverlay = true;

  const root = document.createElement('div');
  root.style.cssText = 'position:fixed;top:72px;right:16px;z-index:2147483647;width:300px;' +
    'display:flex;flex-direction:column;gap:8px;font:13px/1.4 system-ui,sans-serif;';
  // In fullscreen only the fullscreen element's subtree is drawn, so move with it.
  const place = () => (document.fullscreenElement || document.body).appendChild(root);
  place();
  document.addEventListener('fullscreenchange', place);

  const cards = new Map(); // speaker label -> card element

  function el(tag, css, text) {
    const e = document.createElement(tag);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    return e;
  }

  function ask({ speaker, text, guess }) {
    if (cards.has(speaker)) return;
    const card = el('div', 'background:#1f2937;color:#f9fafb;border-radius:8px;padding:10px 12px;' +
      'box-shadow:0 4px 16px rgba(0,0,0,.4);');
    cards.set(speaker, card);

    card.appendChild(el('div', 'font-weight:600;margin-bottom:4px;', `New speaker (${speaker}) — who is this?`));
    const quote = text.length > 140 ? text.slice(0, 140) + '…' : text;
    card.appendChild(el('div', 'font-style:italic;color:#d1d5db;margin-bottom:8px;', `“${quote}”`));

    const name = el('input', 'width:100%;box-sizing:border-box;padding:6px 8px;border-radius:4px;' +
      'border:1px solid #4b5563;background:#111827;color:#f9fafb;font:inherit;');
    name.type = 'text';
    name.placeholder = 'Name';
    name.maxLength = 60;
    name.value = guess || '';
    card.appendChild(name);

    const row = el('div', 'display:flex;align-items:center;gap:8px;margin-top:8px;');
    const label = el('label', 'display:flex;align-items:center;gap:4px;margin-right:auto;cursor:pointer;');
    const moderator = el('input');
    moderator.type = 'checkbox';
    label.appendChild(moderator);
    label.appendChild(el('span', null, 'Moderator'));
    row.appendChild(label);
    const button = 'padding:4px 10px;border-radius:4px;border:0;cursor:pointer;font:inherit;';
    const skip = el('button', button + 'background:#374151;color:#f9fafb;', 'Skip');
    const save = el('button', button + 'background:#2563eb;color:#fff;', 'Save');
    row.appendChild(skip);
    row.appendChild(save);
    card.appendChild(row);

    const close = () => { card.remove(); cards.delete(speaker); };
    const submit = () => {
      const typed = name.value.trim();
      if (!typed && !moderator.checked) { name.focus(); return; } // nothing to send; Skip closes the card
      chrome.runtime.sendMessage({
        target: 'offscreen', type: 'speaker-name', speaker, name: typed, moderator: moderator.checked
      });
      close();
    };
    save.addEventListener('click', submit);
    skip.addEventListener('click', close);
    // Keep YouTube's keyboard shortcuts (k, f, m, space ...) from firing while typing a name.
    for (const type of ['keydown', 'keyup', 'keypress']) {
      card.addEventListener(type, (e) => {
        e.stopPropagation();
        if (type === 'keydown' && e.key === 'Enter') submit();
      });
    }
    root.appendChild(card);
  }

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.target !== 'overlay') return;
    if (msg.type === 'new-speaker') {
      ask(msg.payload);
    } else if (msg.type === 'stop') {
      root.replaceChildren();
      cards.clear();
    }
  });
})();
