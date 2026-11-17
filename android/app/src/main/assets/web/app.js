'use strict';
// Invitado del intercomunicador: voz PCM 16 kHz por WebSocket hacia el Android anfitrión.

const $ = id => document.getElementById(id);
const RATE = 16000, FRAME = 320; // 20 ms

let ctx = null, mic = null, micSrc = null, cap = null, play = null, ws = null;
let mode = 'ptt', talking = false, remoteTalk = false;
let lastMsg = 0, retryTimer = null, wakeLock = null, started = false;
window.__stats = { sent: 0, recv: 0 };

function status(text, kind) { $('st').textContent = text; $('led').className = 'led ' + (kind || ''); }

// ---------- Procesadores de audio (corren en el hilo de audio) ----------
const WORKLET = `
class Cap extends AudioWorkletProcessor {
  constructor() { super(); this.ratio = sampleRate / ${RATE}; this.ph = 0; this.sum = 0; this.n = 0;
    this.buf = new Int16Array(${FRAME}); this.k = 0; }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    for (let i = 0; i < ch.length; i++) {
      this.sum += ch[i]; this.n++; this.ph += 1;
      if (this.ph >= this.ratio) {
        this.ph -= this.ratio;
        let v = this.sum / this.n; this.sum = 0; this.n = 0;
        v = v < -1 ? -1 : v > 1 ? 1 : v;
        this.buf[this.k++] = v * 32767;
        if (this.k === ${FRAME}) { this.port.postMessage(this.buf.buffer, [this.buf.buffer]); this.buf = new Int16Array(${FRAME}); this.k = 0; }
      }
    }
    return true;
  }
}
class Play extends AudioWorkletProcessor {
  constructor() { super(); this.N = 32768; this.b = new Float32Array(this.N); this.w = 0; this.r = 0; this.on = false;
    this.step = ${RATE} / sampleRate;
    this.port.onmessage = e => {
      const s = new Int16Array(e.data);
      for (let i = 0; i < s.length; i++) { this.b[this.w % this.N] = s[i] / 32768; this.w++; }
      if (this.w - this.r > ${RATE} * 0.35) this.r = this.w - ${RATE} * 0.1; // recorta atraso
    };
  }
  process(_, outputs) {
    const out = outputs[0][0];
    for (let i = 0; i < out.length; i++) {
      const avail = this.w - this.r;
      if (!this.on) { if (avail >= ${RATE} * 0.06) this.on = true; else { out[i] = 0; continue; } }
      if (avail < 2) { this.on = false; out[i] = 0; continue; }
      const j = Math.floor(this.r), f = this.r - j;
      const a = this.b[j % this.N], c = this.b[(j + 1) % this.N];
      out[i] = a + (c - a) * f;
      this.r += this.step;
    }
    for (let c = 1; c < outputs[0].length; c++) outputs[0][c].set(out);
    return true;
  }
}
registerProcessor('cap', Cap);
registerProcessor('play', Play);
`;

async function setupAudio() {
  if (!ctx) {
    try { if (navigator.audioSession) navigator.audioSession.type = 'play-and-record'; } catch (_) {}
    ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
    const url = URL.createObjectURL(new Blob([WORKLET], { type: 'application/javascript' }));
    await ctx.audioWorklet.addModule(url);
    cap = new AudioWorkletNode(ctx, 'cap', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
    cap.port.onmessage = e => {
      if (talking && ws && ws.readyState === 1) { ws.send(e.data); window.__stats.sent++; }
    };
    const mute = ctx.createGain(); mute.gain.value = 0;
    cap.connect(mute).connect(ctx.destination); // el procesador sólo corre si llega al destino
    play = new AudioWorkletNode(ctx, 'play', { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [2] });
    play.connect(ctx.destination);
  }
  await ensureMic();
  if (ctx.state !== 'running') await ctx.resume();
}

async function ensureMic() {
  const live = mic && mic.getAudioTracks().some(t => t.readyState === 'live');
  if (live) return;
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia)
    throw new Error('Safari no permite el micrófono aquí. Instala el certificado y abre la página con https.');
  mic = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 }
  });
  if (micSrc) try { micSrc.disconnect(); } catch (_) {}
  micSrc = ctx.createMediaStreamSource(mic);
  micSrc.connect(cap);
}

// ---------- Conexión con el anfitrión ----------
function connect() {
  clearTimeout(retryTimer);
  if (ws && (ws.readyState === 0 || ws.readyState === 1)) return;
  status('Conectando…', 'wait');
  $('srv').textContent = location.host;
  try { ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'); }
  catch (e) { scheduleRetry(); return; }
  ws.binaryType = 'arraybuffer';
  ws.onopen = () => {
    lastMsg = Date.now();
    status('Conectado', 'ok');
    sendText({ t: 'talk', on: talking });
  };
  ws.onmessage = e => {
    lastMsg = Date.now();
    if (typeof e.data === 'string') {
      try { const m = JSON.parse(e.data); if (m.t === 'talk') setRx(!!m.on); } catch (_) {}
    } else if (play) {
      play.port.postMessage(e.data, [e.data]); window.__stats.recv++;
    }
  };
  ws.onclose = () => { setRx(false); scheduleRetry(); };
  ws.onerror = () => {};
}
function scheduleRetry() {
  status('Sin conexión · reintentando', 'bad');
  clearTimeout(retryTimer);
  retryTimer = setTimeout(connect, 1500);
}
function sendText(o) { try { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); } catch (_) {} }

// latido: detecta conexiones muertas (p. ej. tras volver de otra app)
setInterval(() => {
  if (!started) return;
  sendText({ t: 'p' });
  if (ws && ws.readyState === 1 && Date.now() - lastMsg > 8000) { try { ws.close(); } catch (_) {} }
}, 3000);

// ---------- Hablar ----------
function setTalk(on) {
  talking = on;
  $('ptt').classList.toggle('on', on);
  sendText({ t: 'talk', on });
}
function setRx(on) {
  remoteTalk = on;
  $('rx').textContent = on ? '◉ El otro está hablando' : (ws && ws.readyState === 1 ? 'Escuchando' : '—');
  $('rx').classList.toggle('on', on);
}
function setMode(m) {
  mode = m;
  $('mPtt').setAttribute('aria-pressed', m === 'ptt');
  $('mOpen').setAttribute('aria-pressed', m === 'open');
  $('ptt').innerHTML = m === 'open' ? 'Micrófono<br>abierto' : 'Mantén<br>para hablar';
  setTalk(m === 'open');
  try { localStorage.setItem('mode', m); } catch (_) {}
}
$('mPtt').onclick = () => setMode('ptt');
$('mOpen').onclick = () => setMode('open');

const ptt = $('ptt');
ptt.addEventListener('pointerdown', e => {
  e.preventDefault();
  if (ctx && ctx.state !== 'running') ctx.resume().catch(() => {});
  if (mode === 'ptt') { try { ptt.setPointerCapture(e.pointerId); } catch (_) {} setTalk(true); }
  else { setTalk(!talking); ptt.innerHTML = talking ? 'Micrófono<br>abierto' : 'Silenciado'; }
});
['pointerup', 'pointercancel', 'lostpointercapture'].forEach(ev =>
  ptt.addEventListener(ev, () => { if (mode === 'ptt' && talking) setTalk(false); }));
ptt.addEventListener('contextmenu', e => e.preventDefault());

// ---------- Inicio y reanudación ----------
async function start() {
  $('coverMsg').className = '';
  try {
    await setupAudio();
    started = true;
    $('cover').hidden = true;
    connect();
    keepAwake();
  } catch (e) {
    $('coverMsg').className = 'err';
    $('coverMsg').textContent = e && e.name === 'NotAllowedError'
      ? 'Permite el micrófono: botón “aA” de la barra de Safari → Configuración del sitio web → Micrófono → Permitir.'
      : (e.message || String(e));
  }
}
$('bStart').onclick = start;

async function resume() {
  if (!started) return;
  try {
    await ensureMic();
    if (ctx.state !== 'running') await ctx.resume();
  } catch (_) {}
  if (!ctx || ctx.state !== 'running') {
    $('bStart').textContent = 'Toca para reanudar';
    $('coverMsg').textContent = 'Safari pausó el audio mientras estabas fuera.';
    $('cover').hidden = false;
  }
  if (!ws || ws.readyState > 1) connect();
  keepAwake();
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') resume(); });
window.addEventListener('pageshow', resume);

async function keepAwake() {
  try {
    if ('wakeLock' in navigator && !wakeLock && document.visibilityState === 'visible') {
      wakeLock = await navigator.wakeLock.request('screen');
      $('wl').textContent = 'Pantalla fija';
      wakeLock.addEventListener('release', () => { wakeLock = null; $('wl').textContent = ''; });
    }
  } catch (_) {}
}

try { if (localStorage.getItem('mode') === 'open') mode = 'open'; } catch (_) {}
setMode(mode);
$('srv').textContent = location.host;
