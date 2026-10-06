// Offscreen document: owns the tab MediaStream, the AudioContext and the
// WebSocket to the Java backend.

const BACKEND_URL = 'ws://localhost:8080/audio';
const TARGET_SAMPLE_RATE = 16000;

let stream = null;
let audioCtx = null;
let workletNode = null;
let ws = null;
let tabId = null;

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.target !== 'offscreen') return;
  if (msg.type === 'start-capture') {
    start(msg).catch(reportError);
  } else if (msg.type === 'stop-capture') {
    stop();
  } else if (msg.type === 'speaker-name') {
    // The user's answer from the on-page card.
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'speaker-name', speaker: msg.speaker, name: msg.name, moderator: msg.moderator }));
    }
  }
});

function toPage(type, payload) {
  if (tabId == null) return;
  chrome.runtime.sendMessage({
    target: 'service-worker',
    type: 'to-tab',
    tabId,
    message: { target: 'overlay', type, payload }
  });
}

async function start({ streamId, tabId: id, tabUrl, tabTitle }) {
  if (stream) return; // already running
  tabId = id;

  stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      mandatory: {
        chromeMediaSource: 'tab',
        chromeMediaSourceId: streamId
      }
    },
    video: false
  });

  // Use the device's native rate; the worklet resamples to 16 kHz.
  audioCtx = new AudioContext();
  const source = audioCtx.createMediaStreamSource(stream);

  // Capturing a tab mutes it. Route the audio back out so the user still hears the video.
  source.connect(audioCtx.destination);

  await audioCtx.audioWorklet.addModule(chrome.runtime.getURL('pcm-worklet.js'));
  workletNode = new AudioWorkletNode(audioCtx, 'pcm-worklet', {
    processorOptions: { targetSampleRate: TARGET_SAMPLE_RATE, chunkMs: 100 }
  });
  source.connect(workletNode);
  // The worklet outputs silence; it doesn't need to reach the speakers.

  ws = new WebSocket(BACKEND_URL);
  ws.binaryType = 'arraybuffer';

  ws.onopen = () => {
    ws.send(JSON.stringify({
      type: 'start',
      sampleRate: TARGET_SAMPLE_RATE,
      encoding: 'pcm_s16le',
      channels: 1,
      tabUrl,
      tabTitle
    }));
  };
  // Milestone 2: the backend sends back finished sentences. For now just log them;
  // the on-page overlay comes later.
  ws.onmessage = (e) => {
    if (typeof e.data !== 'string') return;
    const msg = JSON.parse(e.data);
    if (msg.type === 'sentence') {
      console.log(`[${msg.speaker}] (${msg.latencyMs} ms) ${msg.category} ${msg.text}`);
    } else if (msg.type === 'claim') {
      console.log(`[${msg.speaker}] (${msg.latencyMs} ms) ${msg.rewritten ? 'CLAIM' : 'CLAIM (as said)'} ${msg.claim}`);
    } else if (msg.type === 'digest') {
      // Once a minute: each speaker's claims of that minute (same ids as the claim messages) and what the check found.
      const clock = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
      console.log(`DIGEST ${clock(msg.from)}-${clock(msg.to)}`);
      for (const sp of msg.speakers) {
        console.log(`  ${sp.name} said:`);
        for (const st of sp.statements) {
          const quote = st.quote ? `"${st.quote}" — ` : '';
          const source = st.verdict !== 'UNVERIFIABLE' && st.sources.length ? ` — ${quote}${st.sources[0].title} ${st.sources[0].url}` : '';
          console.log(`    [${st.status}] ${st.claim}${source}`);
        }
      }
    } else if (msg.type === 'speakers') {
      console.log('SPEAKERS ' + Object.entries(msg.names).map(([label, name]) => `${label} = ${name}`).join(', '));
    } else if (msg.type === 'new-speaker') {
      console.log(`NEW SPEAKER ${msg.speaker}: ${msg.text}`);
      toPage('new-speaker', msg);
    }
  };
  ws.onclose = (e) => {
    if (stream) reportError(new Error(`WebSocket closed (code ${e.code})`));
  };
  ws.onerror = () => reportError(new Error('WebSocket error — is the backend running on :8080?'));

  // Each message is one ~100 ms chunk of Int16 mono PCM at 16 kHz (3200 bytes).
  workletNode.port.onmessage = (event) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(event.data);
    }
  };
}

function stop() {
  const s = stream;
  stream = null; // mark stopped before closing the socket so onclose doesn't report an error
  toPage('stop');
  tabId = null;
  if (ws) { try { ws.send(JSON.stringify({ type: 'stop' })); } catch (_) {} ws.close(); ws = null; }
  if (workletNode) { workletNode.port.onmessage = null; workletNode.disconnect(); workletNode = null; }
  if (audioCtx) { audioCtx.close(); audioCtx = null; }
  if (s) s.getTracks().forEach((t) => t.stop());
}

function reportError(err) {
  console.error(err);
  stop();
  chrome.runtime.sendMessage({
    target: 'service-worker',
    type: 'capture-error',
    error: String(err && err.message || err)
  });
}
