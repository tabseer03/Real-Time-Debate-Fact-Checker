// Service worker: toggles capture on toolbar-icon click.
// MV3 service workers can't hold a MediaStream, so the actual capture
// happens in an offscreen document. We only fetch a stream ID here.

const OFFSCREEN_URL = 'offscreen.html';

async function offscreenExists() {
  const contexts = await chrome.runtime.getContexts({
    contextTypes: ['OFFSCREEN_DOCUMENT'],
    documentUrls: [chrome.runtime.getURL(OFFSCREEN_URL)]
  });
  return contexts.length > 0;
}

async function startCapture(tab) {
  if (!tab.url || !tab.url.startsWith('https://www.youtube.com/')) {
    console.warn('Open a YouTube video first.');
    await flashBadge('YT?', '#b45309');
    return;
  }

  // Must be called in response to the user gesture (the icon click).
  const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: tab.id });

  if (!(await offscreenExists())) {
    await chrome.offscreen.createDocument({
      url: OFFSCREEN_URL,
      reasons: ['USER_MEDIA'],
      justification: 'Capture tab audio for real-time transcription and fact-checking'
    });
  }

  // The on-page card that asks who each new voice is. activeTab (the icon click) allows this.
  await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['overlay.js'] });

  await chrome.runtime.sendMessage({
    target: 'offscreen',
    type: 'start-capture',
    streamId,
    tabId: tab.id,
    tabUrl: tab.url,
    tabTitle: tab.title
  });

  await chrome.action.setBadgeText({ text: 'ON', tabId: tab.id });
  await chrome.action.setBadgeBackgroundColor({ color: '#15803d', tabId: tab.id });
}

async function stopCapture(tab) {
  try {
    await chrome.runtime.sendMessage({ target: 'offscreen', type: 'stop-capture' });
  } catch (e) {
    // offscreen doc may already be gone
  }
  await chrome.offscreen.closeDocument().catch(() => {});
  await chrome.action.setBadgeText({ text: '', tabId: tab.id });
}

async function flashBadge(text, color) {
  await chrome.action.setBadgeText({ text });
  await chrome.action.setBadgeBackgroundColor({ color });
  setTimeout(() => chrome.action.setBadgeText({ text: '' }), 2000);
}

chrome.action.onClicked.addListener(async (tab) => {
  try {
    if (await offscreenExists()) {
      await stopCapture(tab);
    } else {
      await startCapture(tab);
    }
  } catch (err) {
    console.error('Capture toggle failed:', err);
    await flashBadge('ERR', '#b91c1c');
  }
});

// Status messages from the offscreen document (e.g. WebSocket dropped).
chrome.runtime.onMessage.addListener((msg) => {
  if (msg.target !== 'service-worker') return;
  if (msg.type === 'to-tab') {
    // The offscreen document has no chrome.tabs; it asks us to pass messages to the page.
    chrome.tabs.sendMessage(msg.tabId, msg.message).catch(() => {});
  } else if (msg.type === 'capture-error') {
    console.error('Offscreen error:', msg.error);
    flashBadge('ERR', '#b91c1c');
    chrome.offscreen.closeDocument().catch(() => {});
  }
});
