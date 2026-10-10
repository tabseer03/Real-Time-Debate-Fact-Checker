// Injected into the YouTube tab while it is being captured. Shows what the backend sends: the
// sentence being spoken, each claim as it arrives (unchecked), and once a minute what the check
// found for it. When the backend hears a voice it has not heard before, a card asks who it is; the
// answer goes back to the backend, which then uses the name in the claims. Built with
// createElement only: YouTube's Trusted Types policy blocks innerHTML.

(() => {
  if (window.__debateCheckerOverlay) return; // injected again on the next capture; this copy keeps working
  window.__debateCheckerOverlay = true;

  const MAX_CLAIMS = 300;
  const COLOURS = { TRUE: '#15803d', FALSE: '#b91c1c', MISLEADING: '#b45309', UNVERIFIABLE: '#4b5563' };
  const PENDING = '#1d4ed8';

  const root = document.createElement('div');
  root.style.cssText = 'position:fixed;top:68px;z-index:2147483647;' +
    'max-height:calc(100vh - 80px);display:flex;flex-direction:column;gap:8px;' +
    'font:14px/1.4 system-ui,sans-serif;';
  // From the video's right edge to the edge of the window. When the video leaves no room there
  // (theater mode, fullscreen, a narrow window) it is a fixed column over the right of the page.
  function layout() {
    const player = document.querySelector('#movie_player') || document.querySelector('video');
    const width = document.documentElement.clientWidth;
    const free = player && !document.fullscreenElement ? width - player.getBoundingClientRect().right : 0;
    const beside = free >= 300 && free < width;
    root.style.left = beside ? `${width - free + 12}px` : 'auto';
    root.style.right = '12px';
    root.style.width = beside ? 'auto' : '380px';
  }
  // In fullscreen only the fullscreen element's subtree is drawn, so move with it.
  const place = () => { (document.fullscreenElement || document.body).appendChild(root); layout(); };
  place();
  document.addEventListener('fullscreenchange', place);
  window.addEventListener('resize', layout);
  setInterval(layout, 1000); // YouTube moves the player without any event (theater mode, the next video)

  const cards = new Map();  // speaker label -> "who is this?" card
  const names = new Map();  // speaker label -> name ('' while unknown), in the order first heard
  const ticked = new Set(); // speaker labels the user marked as moderator
  const claims = new Map(); // claim id -> { card, badge, found, who, speaker, start, done }

  function el(tag, css, text) {
    const e = document.createElement(tag);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    return e;
  }

  const clock = (s) => {
    s = Math.max(0, Math.floor(s));
    const m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, '0');
    return s >= 3600 ? `${Math.floor(s / 3600)}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
  };

  // ---- the panel ----

  const button = 'padding:4px 10px;border-radius:4px;border:0;cursor:pointer;font:inherit;';
  const panel = el('div', 'display:none;flex-direction:column;min-height:0;background:#111827;color:#f9fafb;' +
    'border-radius:8px;box-shadow:0 4px 16px rgba(0,0,0,.4);overflow:hidden;');
  const header = el('div', 'display:flex;align-items:center;gap:8px;padding:8px 12px;background:#1f2937;flex-shrink:0;');
  const heading = el('div', 'margin-right:auto;min-width:0;');
  heading.appendChild(el('div', 'font-weight:600;', 'Podium · debate fact check'));
  const state = el('div', 'font-size:11px;color:#9ca3af;', '');
  heading.appendChild(state);
  const fold = el('button', button + 'background:#374151;color:#f9fafb;', '–');
  fold.title = 'Fold / unfold';
  const hide = el('button', button + 'background:#374151;color:#f9fafb;', '×');
  hide.title = 'Hide until the next start';
  header.append(heading, fold, hide);

  const body = el('div', 'display:flex;flex-direction:column;min-height:0;');
  // flex-shrink:0 on these two: otherwise the growing list of claims squeezes them to nothing.
  const chips = el('div', 'display:flex;flex-wrap:wrap;gap:4px;padding:8px 12px 0;flex-shrink:0;');
  const live = el('div', 'flex-shrink:0;box-sizing:border-box;min-height:5.6em;padding:10px 12px;' +
    'font-size:15px;color:#f9fafb;border-bottom:1px solid #374151;', 'Listening…');
  const feed = el('div', 'display:flex;flex-direction:column;gap:8px;padding:8px;overflow-y:auto;' +
    'overscroll-behavior:contain;min-height:0;');
  body.append(chips, live, feed);
  panel.append(header, body);
  root.appendChild(panel);

  fold.addEventListener('click', () => {
    const folded = body.style.display !== 'none';
    body.style.display = folded ? 'none' : 'flex';
    fold.textContent = folded ? '+' : '–';
  });
  hide.addEventListener('click', () => { panel.style.display = 'none'; });

  function reset() {
    for (const card of cards.values()) card.remove();
    cards.clear();
    names.clear();
    ticked.clear();
    claims.clear();
    chips.replaceChildren();
    feed.replaceChildren();
    live.textContent = 'Listening…';
    state.textContent = 'Listening';
    panel.style.display = 'flex';
  }

  const nameOf = (speaker) => names.get(speaker) || speaker;

  // One chip per voice; a click reopens the name card, so a mistyped name can be corrected.
  function drawChips() {
    chips.replaceChildren();
    for (const [speaker, name] of names) {
      const chip = el('button', 'padding:2px 8px;border-radius:10px;border:1px solid #374151;cursor:pointer;' +
        'background:#1f2937;color:#f9fafb;font:inherit;font-size:12px;', name ? `${speaker} · ${name}` : speaker);
      chip.title = 'Change the name';
      chip.addEventListener('click', () => ask({ speaker, text: '', guess: name }));
      chips.appendChild(chip);
    }
  }

  function heard(speaker) {
    if (names.has(speaker)) return;
    names.set(speaker, '');
    drawChips();
  }

  function setNames(roster) {
    for (const [speaker, name] of Object.entries(roster)) names.set(speaker, name);
    drawChips();
    for (const c of claims.values()) c.who.textContent = nameOf(c.speaker);
  }

  function sentence(msg) {
    heard(msg.speaker);
    live.textContent = `${nameOf(msg.speaker)}: ${msg.text}`;
  }

  // Where in the video the sentence began: the backend's clock counts captured audio, which is
  // not the video's time once the user has paused or jumped.
  function videoTime(msg) {
    const video = document.querySelector('video');
    if (!video || msg.latencyMs == null) return null;
    return Math.max(0, video.currentTime - msg.latencyMs / 1000 - (msg.end - msg.start));
  }

  function setBadge(c, text, colour) {
    c.badge.textContent = text;
    c.badge.style.background = colour;
    c.card.style.borderLeftColor = colour;
  }

  function setText(c, msg) {
    c.text.textContent = msg.claim;
    const differs = msg.rewritten && msg.sentence && msg.sentence !== msg.claim;
    c.said.textContent = differs ? `Said: “${msg.sentence}”` : '';
    c.said.style.display = differs ? '' : 'none';
  }

  // A claim that arrives again under its id has had the speaker's next sentence added to it:
  // the card takes the new wording and is unchecked again.
  function claim(msg) {
    const c = claims.get(msg.id);
    if (!c) return addClaim(msg);
    setText(c, msg);
    c.done = false;
    c.found.replaceChildren();
    c.found.style.cssText = 'font-size:12px;';
    c.card.style.opacity = '';
    c.card.style.fontSize = '';
    c.card.title = '';
    setBadge(c, 'checking…', PENDING);
    return c;
  }

  function addClaim(msg) {
    if (claims.has(msg.id)) return claims.get(msg.id);
    heard(msg.speaker);
    if (msg.speakerName && !names.get(msg.speaker)) { names.set(msg.speaker, msg.speakerName); drawChips(); }

    const card = el('div', 'background:#1f2937;border-radius:6px;padding:8px 10px;border-left:4px solid;');
    const top = el('div', 'display:flex;align-items:center;gap:6px;margin-bottom:4px;');
    const who = el('span', 'font-weight:600;', nameOf(msg.speaker));
    top.appendChild(who);
    const at = videoTime(msg);
    if (at == null) {
      top.appendChild(el('span', 'color:#9ca3af;font-size:12px;', clock(msg.start)));
    } else {
      const jump = el('button', 'padding:0;border:0;background:none;cursor:pointer;color:#93c5fd;' +
        'font:inherit;font-size:12px;', clock(at));
      jump.title = 'Go to this moment in the video';
      jump.addEventListener('click', () => {
        const video = document.querySelector('video');
        if (video) video.currentTime = at;
      });
      top.appendChild(jump);
    }
    const badge = el('span', 'margin-left:auto;padding:1px 8px;border-radius:10px;color:#fff;font-size:11px;' +
      'white-space:nowrap;');
    top.appendChild(badge);
    card.appendChild(top);
    const text = el('div');
    const said = el('div', 'color:#9ca3af;font-size:12px;margin-top:2px;');
    const found = el('div', 'font-size:12px;');
    card.append(text, said, found);

    const c = { card, badge, found, who, text, said, speaker: msg.speaker, start: msg.start, done: false };
    setText(c, msg);
    setBadge(c, 'checking…', PENDING);
    claims.set(msg.id, c);
    feed.prepend(card);
    if (claims.size > MAX_CLAIMS) {
      const [oldest, old] = claims.entries().next().value;
      old.card.remove();
      claims.delete(oldest);
    }
    return c;
  }

  function sourceLink(src) {
    let host;
    try {
      const url = new URL(src.url);
      if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
      host = url.hostname.replace(/^www\./, '');
    } catch (e) {
      return null;
    }
    const a = el('a', 'color:#93c5fd;text-decoration:underline;display:block;margin-top:2px;',
      src.title ? `${src.title} — ${host}` : host);
    a.href = src.url;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    return a;
  }

  // The minute's report for one claim: the verdict, and the words it rests on with their source.
  function settle(st, sp) {
    const c = addClaim({ ...st, speaker: sp.speaker, speakerName: sp.name });
    c.done = true;
    setBadge(c, st.status, COLOURS[st.verdict] || COLOURS.UNVERIFIABLE);
    // The checking service gave no answer for this stretch: nothing was read, so nothing is known.
    c.card.title = st.status === 'check unavailable' ? 'The checking service did not answer for this part of the debate.' : '';
    c.found.replaceChildren();
    // What the check read as an opinion, or as no statement at all, is not a claim after all:
    // it stays in its place, greyed and small, with the reason on hover.
    if (st.kind && st.kind !== 'fact') {
      setBadge(c, st.status, '#374151');
      c.card.style.opacity = '0.5';
      c.card.style.fontSize = '12px';
      c.card.title = st.reason || '';
      return;
    }
    const src = st.sources && st.sources[0];
    const unsettled = st.verdict === 'UNVERIFIABLE';
    let label;
    if (st.rating) label = `Fact-checker's rating: ${st.rating}`;
    else if (st.quote && src) label = unsettled ? 'Closest found:' : 'Automated reading of this source, not a fact-checker\'s rating:';
    else return; // nothing found that bears on it
    c.found.style.cssText += 'margin-top:6px;padding-top:6px;border-top:1px solid #374151;';
    c.found.appendChild(el('div', 'color:#9ca3af;', label));
    if (st.quote) c.found.appendChild(el('div', 'font-style:italic;color:#e5e7eb;', `“${st.quote}”`));
    if (st.reason) c.found.appendChild(el('div', 'color:#d1d5db;margin-top:2px;', st.reason));
    const a = src && sourceLink(src);
    if (a) c.found.appendChild(a);
  }

  function digest(msg) {
    const reported = new Set();
    for (const sp of msg.speakers) {
      for (const st of sp.statements) {
        reported.add(st.id);
        settle(st, sp);
      }
    }
    // Pieces of an argument and repeats are left out of the report on purpose.
    for (const [id, c] of claims) {
      if (!c.done && !reported.has(id) && c.start < msg.to) setBadge(c, 'not checked', COLOURS.UNVERIFIABLE);
    }
  }

  function stopped() {
    for (const card of cards.values()) card.remove();
    cards.clear();
    state.textContent = 'Stopped';
    // The last minute's report does not arrive: the socket is closed by then.
    for (const c of claims.values()) {
      if (!c.done && c.badge.textContent === 'checking…') setBadge(c, 'not checked', COLOURS.UNVERIFIABLE);
    }
  }

  // ---- "who is this?" ----

  function ask({ speaker, text, guess }) {
    if (cards.has(speaker)) return;
    const card = el('div', 'background:#1f2937;color:#f9fafb;border-radius:8px;padding:10px 12px;' +
      'box-shadow:0 4px 16px rgba(0,0,0,.4);');
    cards.set(speaker, card);

    card.appendChild(el('div', 'font-weight:600;margin-bottom:4px;',
      text ? `New speaker (${speaker}) — who is this?` : `Who is ${speaker}?`));
    if (text) {
      const quote = text.length > 140 ? text.slice(0, 140) + '…' : text;
      card.appendChild(el('div', 'font-style:italic;color:#d1d5db;margin-bottom:8px;', `“${quote}”`));
    }

    const name = el('input', 'width:100%;box-sizing:border-box;padding:6px 8px;border-radius:4px;' +
      'border:1px solid #4b5563;background:#111827;color:#f9fafb;font:inherit;');
    name.type = 'text';
    name.placeholder = 'Name';
    name.maxLength = 60;
    const isModerator = /^the moderator$/i.test(guess || '');
    name.value = isModerator ? '' : guess || '';
    card.appendChild(name);

    const row = el('div', 'display:flex;align-items:center;gap:8px;margin-top:8px;');
    const label = el('label', 'display:flex;align-items:center;gap:4px;margin-right:auto;cursor:pointer;');
    const moderator = el('input');
    moderator.type = 'checkbox';
    moderator.checked = isModerator || ticked.has(speaker);
    label.appendChild(moderator);
    label.appendChild(el('span', null, 'Moderator'));
    row.appendChild(label);
    const skip = el('button', button + 'background:#374151;color:#f9fafb;', 'Skip');
    const save = el('button', button + 'background:#2563eb;color:#fff;', 'Save');
    row.appendChild(skip);
    row.appendChild(save);
    card.appendChild(row);

    const close = () => { card.remove(); cards.delete(speaker); };
    const submit = () => {
      const typed = name.value.trim();
      if (!typed && !moderator.checked) { name.focus(); return; } // nothing to send; Skip closes the card
      if (moderator.checked) ticked.add(speaker); else ticked.delete(speaker);
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
    root.insertBefore(card, panel);
  }

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.target !== 'overlay') return;
    const m = msg.payload;
    if (msg.type === 'start') reset();
    else if (msg.type === 'stop') stopped();
    else if (msg.type === 'new-speaker') ask(m);
    else if (msg.type === 'sentence') sentence(m);
    else if (msg.type === 'claim') claim(m);
    else if (msg.type === 'digest') digest(m);
    else if (msg.type === 'speakers') setNames(m.names);
    else if (msg.type === 'debate-date') state.textContent = m.date ? `Evidence from before ${m.date}` : 'Live';
  });
})();
