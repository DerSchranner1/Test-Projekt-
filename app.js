/* Narrhalla Player
 * Reine Browser-App ohne Build-Schritt; läuft auch in der Android-Hülle (Capacitor).
 * Audiodateien und Wellenformen liegen in IndexedDB, Setliste und Einstellungen in localStorage.
 */
'use strict';

const $ = (id) => document.getElementById(id);
const STATE_KEY = 'narrhalla.state.v1';
const APP_VERSION = '0.9.4';
const PEAK_COUNT = 600;   // Punkte der Übersichts-Wellenform
const HI_RATE = 100;      // Punkte pro Sekunde der Detail-Wellenform
const PALETTE = [
  '#e5484d', '#ff7a1a', '#ffd23f', '#a3e635', '#3ddc97', '#14b8a6', '#4cc9f0', '#3b82f6',
  '#6366f1', '#b388ff', '#f72585', '#ec4899', '#a16207', '#9ca3af', '#f5f5f5', '#64748b',
];
const MARKER_COLORS = ['#ffd23f', '#3ddc97', '#4cc9f0', '#f72585', '#ff7a1a', '#b388ff'];
const ZOOMS = [2, 4, 8, 16, 32];

const DEFAULT_GROUPS = [
  { id: 'g-show', name: 'Show', color: '#ff7a1a' },
  { id: 'g-garde', name: 'Garde', color: '#4cc9f0' },
  { id: 'g-elfer', name: 'Elfer', color: '#e5484d' },
];
const OLD_EXAMPLE_GROUPS = ['g-einmarsch', 'g-garde', 'g-tusch', 'g-buett', 'g-pause'];
const CURRENT_GROUPS_VERSION = 3;
// Farben für die einzelnen Lieder in einer Datei
const SEG_COLORS = ['#ff7a1a', '#4cc9f0', '#3ddc97', '#f72585', '#ffd23f', '#b388ff', '#e5484d', '#14b8a6', '#a3e635', '#3b82f6'];
const VOICES = [
  { id: 'de-frau', name: 'Deutsch – Frau' },
  { id: 'de-frau2', name: 'Deutsch – Frau 2' },
  { id: 'de-mann', name: 'Deutsch – Mann' },
  { id: 'en-frau', name: 'Englisch – Frau' },
  { id: 'en-mann', name: 'Englisch – Mann' },
  { id: 'own', name: 'Eigene Aufnahme' },
];
const DEFAULT_SETTINGS = {
  countMode: 'off',     // off | voice | click
  voice: 'de-frau',
  countVolume: 0.8,
  countOffsetMs: 0,
  snap: 'beat',         // off | beat | eight
  scrubAudio: true,
  markerPlay: true,
  jumpEight: true,
  zoom: 8,
  keepAlive: true,      // Bluetooth wach halten
  keepLevel: 'low',
  screenOn: false,
};

const state = {
  groups: DEFAULT_GROUPS.slice(),
  // Titel: {id, name, artist, fileName, duration, groupId, markers[], cueIn, cueOut, peaks[], segments[]}
  // segments: die Lieder in der Datei: {id, start, name, color, bpm, beatOffset}
  tracks: [],
  groupsVersion: 3,
  colorLabels: [],   // eigene Farben mit Bezeichnung: {id, name, color}
  volume: 1,
  autoNext: false,
  settings: { ...DEFAULT_SETTINGS },
};

let currentId = null;
let selectedId = null;
let lastPlayedId = null;
let filterGroup = null;   // null = alle, '' = ohne Gruppe
let loop = false;
let fading = null;
const urlCache = new Map();
const hiCache = new Map();   // id -> Uint8Array
const audio = new Audio();
audio.preload = 'auto';

/* ---------- Helpers ---------- */
const uid = () => Math.random().toString(36).slice(2, 10);
const track = (id) => state.tracks.find((t) => t.id === id);
const group = (id) => state.groups.find((g) => g.id === id);
const current = () => track(currentId);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const mod = (a, n) => ((a % n) + n) % n;

function fmt(sec, tenths = false) {
  if (!isFinite(sec)) sec = 0;
  const neg = sec < 0;
  sec = Math.abs(sec);
  const m = Math.floor(sec / 60);
  const s = sec - m * 60;
  const out = tenths
    ? `${m}:${s.toFixed(1).padStart(4, '0')}`
    : `${m}:${String(Math.floor(s)).padStart(2, '0')}`;
  return neg ? '-' + out : out;
}

let toastTimer;
function toast(msg, ms = 2200, action = null) {
  const box = $('toast');
  $('toastText').textContent = msg;
  const btn = $('toastAction');
  if (action) {
    btn.hidden = false;
    btn.textContent = action.label;
    btn.onclick = () => { box.classList.remove('show'); action.fn(); };
    ms = Math.max(ms, 6000);
  } else {
    btn.hidden = true;
  }
  box.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.remove('show'), ms);
}

// Rückfrage im App-Stil statt confirm(); Ergebnis true/false
function ask(title, html, okLabel = 'OK', danger = false) {
  return new Promise((resolve) => {
    const dlg = $('confirmDlg');
    $('confirmTitle').textContent = title;
    $('confirmText').innerHTML = html;
    const yes = $('confirmYes');
    yes.textContent = okLabel;
    yes.classList.toggle('danger-solid', danger);
    yes.style.background = danger ? 'var(--danger)' : '';
    yes.style.color = danger ? '#fff' : '';
    dlg.returnValue = '';
    dlg.onclose = () => resolve(dlg.returnValue === 'ok');
    dlg.showModal();
  });
}
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/* Rückgängig: vor jeder Änderung an einem Titel eine Kopie merken */
const undoStack = [];
const EDIT_KEYS = ['beats', 'ones', 'segments', 'markers', 'cueIn', 'cueOut', 'name', 'artist', 'groupId', 'color', 'labelId'];
function snapshot(t, label) {
  if (!t) return;
  const data = {};
  for (const k of EDIT_KEYS) data[k] = t[k] === undefined ? undefined : JSON.parse(JSON.stringify(t[k]));
  undoStack.push({ id: t.id, data, label });
  if (undoStack.length > 20) undoStack.shift();
}
function undo() {
  const u = undoStack.pop();
  if (!u) return toast('Nichts zum Rückgängigmachen');
  const t = track(u.id);
  if (!t) return toast('Titel gibt es nicht mehr');
  for (const k of EDIT_KEYS) { if (u.data[k] === undefined) delete t[k]; else t[k] = u.data[k]; }
  save();
  resetCountSchedule();
  renderAll();
  toast(`Rückgängig: ${u.label}`);
}
// Änderung ausführen und „Rückgängig“ anbieten
function edit(label, fn, t = current()) {
  if (!t) return;
  snapshot(t, label);
  const r = fn(t);
  if (r === false) { undoStack.pop(); return; }
  toast(label, 3000, { label: 'Rückgängig', fn: undo });
}

let saveTimer;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveNow, 150);
}
function saveNow() {
  clearTimeout(saveTimer);
  try {
    state.lastChange = Date.now();
    localStorage.setItem(STATE_KEY, JSON.stringify(state));
  } catch (e) {
    console.warn('Speichern fehlgeschlagen', e);
    showBanner('save', 'Speichern fehlgeschlagen – der Speicher ist voll. Bitte jetzt sichern und unbenutzte Titel löschen.', 'Jetzt sichern', exportSet, 'error');
  }
}

function el(tag, props = {}, ...children) {
  const n = Object.assign(document.createElement(tag), props);
  n.append(...children.filter((c) => c != null));
  return n;
}

/* ---------- IndexedDB (Audiodateien, Wellenformen) ---------- */
let dbPromise;
function db() {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('narrhalla', 2);
    req.onupgradeneeded = () => {
      const d = req.result;
      if (!d.objectStoreNames.contains('files')) d.createObjectStore('files');
      if (!d.objectStoreNames.contains('peaks')) d.createObjectStore('peaks');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}
async function idb(store, mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction(store, mode);
    const req = fn(tx.objectStore(store));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = () => reject(tx.error);
  });
}
const putFile = (id, blob) => idb('files', 'readwrite', (s) => s.put(blob, id));
const getFile = (id) => idb('files', 'readonly', (s) => s.get(id));
const delFile = (id) => Promise.all([
  idb('files', 'readwrite', (s) => s.delete(id)),
  idb('peaks', 'readwrite', (s) => s.delete(id)),
]);
const putHi = (id, arr) => idb('peaks', 'readwrite', (s) => s.put(arr, id));
const getHi = (id) => idb('peaks', 'readonly', (s) => s.get(id));

async function urlFor(id) {
  if (!urlCache.has(id)) {
    const blob = await getFile(id);
    if (!blob) return null;
    urlCache.set(id, URL.createObjectURL(blob));
  }
  return urlCache.get(id);
}

/* ---------- Audio-Grundgerüst ---------- */
let audioCtx;
let gainNode;
function ctx() {
  audioCtx ??= new (window.AudioContext || window.webkitAudioContext)();
  if (audioCtx.state === 'suspended') audioCtx.resume();
  return audioCtx;
}
// Lautstärke über einen GainNode, weil iOS audio.volume ignoriert.
// iPhone/iPad: Web Audio pausiert beim Sperren – dann die Musik direkt ausgeben
const isTouch = matchMedia('(pointer: coarse)').matches;
const svg = (d) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${d}"/></svg>`;
const ICON_PLAY = svg('M8 5v14l11-7z').replace('<svg', '<svg class="play"');
const ICON_PAUSE = svg('M6 19h4V5H6v14zm8-14v14h4V5h-4z');
const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
function ensureGraph() {
  try {
    ctx();
    if (!gainNode && !isIOS) {
      gainNode = audioCtx.createGain();
      audioCtx.createMediaElementSource(audio).connect(gainNode).connect(audioCtx.destination);
      audio.volume = 1;
      setVol(fading ? 0 : state.volume);
    }
  } catch (e) {
    console.warn('Web Audio nicht verfügbar', e);
  }
}
function setVol(v) {
  if (gainNode) gainNode.gain.value = v;
  else audio.volume = v;
}

/* ---------- Bluetooth / Hintergrund wach halten ----------
 * 1. Ein unhörbar leises Rauschen läuft in Schleife über ein eigenes <audio>-Element: Bluetooth-Boxen
 *    schalten bei Stille ab, und Android/iOS beenden den Audiokanal, wenn nichts mehr kommt.
 * 2. Android-App: Vordergrund-Dienst mit Wake-Lock (Plugin „KeepAlive“), damit die App gesperrt weiterläuft.
 * 3. Optional: Bildschirm anlassen.
 */
let keepEl = null, keepLevelLoaded = null, screenLock = null, keepNativeOn = false;
let keepUntil = 0;            // bis wann wach halten (ms seit 1970)
const KEEP_AFTER_MS = 30 * 60 * 1000;
let nativeHealth = null;      // {unrestricted, notifications}

function noiseWav(level) {
  const sr = 22050, n = sr * 2;
  const amp = level === 'mid' ? 0.003 : 0.0006; // etwa −50 dB bzw. −64 dB
  const buf = new ArrayBuffer(44 + n * 2);
  const v = new DataView(buf);
  const w = (o, str) => [...str].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVE'); w(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  w(36, 'data'); v.setUint32(40, n * 2, true);
  let lp = 0;
  for (let i = 0; i < n; i++) {
    lp += 0.2 * ((Math.random() * 2 - 1) - lp);
    v.setInt16(44 + i * 2, Math.round(lp * amp * 3 * 32767), true);
  }
  return new Blob([buf], { type: 'audio/wav' });
}

const native = (method, opts = {}) => window.Capacitor.nativePromise('KeepAlive', method, opts);

// Wiedergabe-Sitzung verlängern: beim Abspielen und beim Anhalten (dann 30 min Nachlauf)
function keepAliveTouch() {
  keepUntil = Date.now() + KEEP_AFTER_MS;
  updateKeepAlive();
}

function keepActive() {
  return state.settings.keepAlive && current() && (!audio.paused || Date.now() < keepUntil);
}

async function updateKeepAlive() {
  const on = !!keepActive();
  if (on) {
    if (!keepEl || keepLevelLoaded !== state.settings.keepLevel) {
      keepEl?.pause();
      keepEl = new Audio(URL.createObjectURL(noiseWav(state.settings.keepLevel)));
      keepEl.loop = true;
      keepEl.setAttribute('playsinline', '');
      keepLevelLoaded = state.settings.keepLevel;
    }
    if (keepEl.paused) keepEl.play().catch(() => {});
  } else {
    keepEl?.pause();
  }
  await keepAliveNative(on);
  updateScreenOn();
  $('kaInd').hidden = !on;
}
setInterval(() => { if (keepEl && !keepEl.paused && !keepActive()) updateKeepAlive(); }, 30000);

async function keepAliveNative(on) {
  if (!isNative()) return;
  const text = audio.paused ? 'Pausiert – Bluetooth bleibt verbunden' : `Spielt: ${current()?.name || ''}`;
  try {
    if (on) {
      // vor der ersten Android-Rückfrage erklären, wofür die Benachrichtigung ist
      if (!state.notifExplained) {
        state.notifExplained = true;
        save();
        await ask('Bluetooth im Training', '<p>Damit die Musik und die Bluetooth-Box auch bei gesperrtem Handy weiterlaufen, zeigt die App während der Wiedergabe eine Benachrichtigung „Narrhalla Player“.</p><p>Android fragt gleich, ob die App Benachrichtigungen zeigen darf – bitte <b>Zulassen</b> tippen.</p>', 'Verstanden');
      }
      await native('start', { text, playing: !audio.paused, title: current()?.name || 'Narrhalla Player' });
    } else if (keepNativeOn) {
      await native('stop');
    }
    keepNativeOn = on;
  } catch (e) {
    console.warn('KeepAlive', e);
  }
}

async function updateScreenOn() {
  const on = state.settings.screenOn;
  if (isNative()) {
    try { await native('screenOn', { on }); } catch (e) { /* ältere App */ }
    return;
  }
  try {
    if (on && !screenLock && navigator.wakeLock && document.visibilityState === 'visible') {
      screenLock = await navigator.wakeLock.request('screen');
      screenLock.addEventListener('release', () => { screenLock = null; });
    } else if (!on && screenLock) {
      await screenLock.release();
      screenLock = null;
    }
  } catch (e) {
    console.warn('Wake Lock', e);
  }
}

// Android: Akku-Optimierung und Benachrichtigungen prüfen
async function checkNativeHealth() {
  if (!isNative()) return null;
  try {
    nativeHealth = await native('batteryStatus');
  } catch (e) {
    nativeHealth = null;
  }
  return nativeHealth;
}

async function updateBatteryRow() {
  if (!isNative()) return;
  const h = await checkNativeHealth();
  if (!h) return;
  $('batteryRow').hidden = false;
  $('batteryState').textContent = h.unrestricted ? '✓ ist aus' : '⚠ ist noch an – kann die Verbindung trennen';
  $('batteryBtn').hidden = h.unrestricted;
}

// Systemsteuerung (Sperrbildschirm, Box-Tasten), wo der Browser es kann
function updateMediaSession() {
  if (!('mediaSession' in navigator)) return;
  const t = current();
  try {
    navigator.mediaSession.metadata = t ? new MediaMetadata({ title: t.name, artist: t.artist || 'Narrhalla Player' }) : null;
    navigator.mediaSession.playbackState = audio.paused ? 'paused' : 'playing';
  } catch (e) { /* nicht unterstützt */ }
}

/* ---------- Hinweise ----------
 * Unaufdringlich: oben nur ein kleines „⚠ n“. Antippen zeigt die Hinweise mit „Beheben“,
 * „Später“ (3 Tage Ruhe) und „Nicht mehr erinnern“ – beides wird gespeichert.
 * Nur echte Notfälle (z. B. Speicher voll) erscheinen als Leiste. */
const banners = new Map();
function showBanner(id, msg, actionLabel = null, action = null, kind = 'warn') {
  banners.set(id, { msg, actionLabel, action, kind });
  renderBanners();
}
function hideBanner(id) {
  if (banners.delete(id)) renderBanners();
}
function snoozed(id) {
  const u = state.snoozed?.[id];
  return typeof u === 'number' && Date.now() < u;
}
function snooze(id, until) {
  state.snoozed = { ...(state.snoozed || {}), [id]: until };
  save();
  renderBanners();
}
function activeNotices() {
  return [...banners].filter(([id, b]) => b.kind === 'error' || !snoozed(id));
}
function renderBanners() {
  const box = $('banners');
  box.replaceChildren();
  const active = activeNotices();
  // Notfälle als volle Leiste
  for (const [, b] of active.filter(([, b]) => b.kind === 'error')) {
    const row = el('div', { className: 'banner' }, el('span', { className: 'msg', textContent: '⚠ ' + b.msg }));
    if (b.action) {
      const btn = el('button', { className: 'btn small', textContent: b.actionLabel });
      btn.onclick = b.action;
      row.append(btn);
    }
    box.append(row);
  }
  // sonst: je Hinweis eine dünne Zeile – die App bleibt ganz normal nutzbar
  const rest = active.filter(([, b]) => b.kind !== 'error').sort((x, y) => (x[1].kind === 'info') - (y[1].kind === 'info'));
  for (const [id, top] of rest) {
    const line = el('div', { className: 'notice-line' + (top.kind === 'info' ? ' info' : '') });
    const msg = el('button', { className: 'nl-msg', textContent: (top.kind === 'info' ? 'ℹ ' : '⚠ ') + top.msg, title: top.msg });
    msg.onclick = openNotices;
    line.append(msg);
    if (top.action) {
      const fix = el('button', { className: 'nl-btn fix', textContent: top.actionLabel });
      fix.onclick = top.action;
      line.append(fix);
    }
    const later = el('button', { className: 'nl-btn', textContent: 'Später', title: '3 Tage nicht mehr anzeigen' });
    later.onclick = () => snooze(id, Date.now() + 3 * 864e5);
    line.append(later);
    box.append(line);
  }
  if ($('noticeDlg').open) renderNoticeList();
}
function renderNoticeList() {
  const ul = $('noticeList');
  ul.replaceChildren();
  const rest = activeNotices().filter(([, b]) => b.kind !== 'error');
  if (!rest.length) {
    ul.append(el('li', { className: 'empty', textContent: 'Alles in Ordnung.' }));
    return;
  }
  for (const [id, b] of rest) {
    const btns = el('div', { className: 'row-inline' });
    if (b.action) {
      const fix = el('button', { type: 'button', className: 'btn small', textContent: b.actionLabel });
      fix.onclick = () => { $('noticeDlg').close(); b.action(); };
      btns.append(fix);
    }
    const later = el('button', { type: 'button', className: 'btn ghost small', textContent: 'Später' });
    later.onclick = () => snooze(id, Date.now() + 3 * 864e5);
    btns.append(later);
    ul.append(el('li', { className: 'notice-item' + (b.kind === 'info' ? ' info' : '') }, el('p', { textContent: b.msg }), btns));
  }
}
function openNotices() {
  renderNoticeList();
  $('noticeDlg').showModal();
}

async function checkWarnings() {
  const s = state.settings;
  // Android: Akku-Optimierung / Benachrichtigungen
  if (isNative() && s.keepAlive) {
    const h = await checkNativeHealth();
    if (h && !h.unrestricted ) {
      showBanner('battery', 'Akku-Optimierung ist an: Android kann die App im Training beenden und Bluetooth trennen.', 'Beheben', async () => {
        try { await native('openBatterySettings'); } catch (e) { toast('Einstellungen → Apps → Narrhalla Player → Akku → „Nicht eingeschränkt“', 6000); }
      });
    } else hideBanner('battery');
    if (h && h.notifications === false ) {
      showBanner('notif', 'Benachrichtigungen sind aus: Android kann die Wiedergabe bei gesperrtem Handy beenden.', 'Beheben', async () => {
        try { await native('openNotificationSettings'); } catch (e) { toast('Einstellungen → Apps → Narrhalla Player → Benachrichtigungen → an', 6000); }
      });
    } else hideBanner('notif');
  } else {
    hideBanner('battery');
    hideBanner('notif');
  }
  // Bluetooth-Wachhalten ausgeschaltet
  if (!s.keepAlive) {
    showBanner('keep', '„Bluetooth wach halten“ ist aus – im Training kann die Box abschalten.', 'Einschalten', () => {
      s.keepAlive = true; save(); hideBanner('keep'); keepAliveTouch(); checkWarnings();
    }, 'info');
  } else hideBanner('keep');
  // iPhone im Safari-Tab: Daten werden nach 7 Tagen gelöscht
  const standalone = window.matchMedia?.('(display-mode: standalone)').matches || navigator.standalone;
  if (isIOS && !standalone && !isNative()) {
    showBanner('ios', 'iPhone: Bitte über Teilen → „Zum Home-Bildschirm“ installieren. Im Safari-Tab löscht iOS die Titel nach 7 Tagen ohne Nutzung.');
  }
  // Speicher kann geräumt werden
  if (!isNative() && navigator.storage?.persisted) {
    const ok = await navigator.storage.persisted().catch(() => true);
    if (!ok && state.tracks.length) {
      showBanner('persist', 'Der Browser darf die Titel bei Platzmangel löschen. Bitte regelmäßig sichern.', 'Jetzt sichern', exportSet, 'info');
    }
  }
  // Erinnerung ans Sichern
  state.firstUse ??= Date.now();
  const last = state.lastBackup || 0;
  const changedSince = (state.lastChange || 0) > last;
  const due = last ? Date.now() - last > 14 * 864e5 : Date.now() - state.firstUse > 3 * 864e5;
  if (state.tracks.length && changedSince && due) {
    showBanner('backup', last ? `Letzte Sicherung vor ${Math.round((Date.now() - last) / 864e5)} Tagen.` : 'Noch nie gesichert – Marker und Korrekturen gehen sonst bei einem Handywechsel verloren.', 'Jetzt sichern', exportSet, 'info');
  } else hideBanner('backup');
}

/* ---------- Analyse: Wellenform, Tempo, Zählzeit ---------- */
function mixMono(buf) {
  const n = buf.length;
  const out = new Float32Array(n);
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const ch = buf.getChannelData(c);
    for (let i = 0; i < n; i++) out[i] += ch[i];
  }
  const k = 1 / buf.numberOfChannels;
  for (let i = 0; i < n; i++) out[i] *= k;
  return out;
}

/* Wellenform-Daten (Version 2): Lautstärke in dB je 10 ms statt Spitzenwert.
 * Fertig gemischte Musik erreicht fast überall die Spitze – die mittlere Lautstärke dagegen
 * unterscheidet Lieder und Abschnitte. Gezeichnet wird relativ zu den lautesten Stellen des Titels. */
const WAVE_V = 2;
const DB_FLOOR = -90;
const LEVEL_RANGE = 30;   // so viele dB unter „laut“ sind noch sichtbar
const POW_OF = new Float32Array(256).map((_, v) => 10 ** ((v / 255 * -DB_FLOOR + DB_FLOOR) / 10));
const levelOf = (db, ref) => Math.pow(clamp((db - (ref - LEVEL_RANGE)) / LEVEL_RANGE, 0, 1), 1.6);
// mittlere Lautstärke der 10-ms-Stücke a..b als Höhe 0–1
function levelRange(hi, a, b, ref) {
  let pw = 0, n = 0;
  for (let j = Math.max(0, a); j < b && j < hi.length; j++) { pw += POW_OF[hi[j]]; n++; }
  return n ? levelOf(10 * Math.log10(pw / n + 1e-12), ref) : 0;
}

function makePeaks(mono, sr) {
  const hop = sr / HI_RATE;
  const n = Math.floor(mono.length / hop);
  const hi = new Uint8Array(n);
  const dbs = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let e = 0;
    const a = Math.floor(i * hop), b = Math.floor((i + 1) * hop);
    for (let j = a; j < b; j++) e += mono[j] * mono[j];
    const db = 10 * Math.log10(e / (b - a) + 1e-12);
    dbs[i] = db;
    hi[i] = Math.round(clamp((db - DB_FLOOR) / -DB_FLOOR, 0, 1) * 255);
  }
  // Bezug: die lautesten 0,5 % des Titels
  const loud = Array.from(dbs).filter((d) => d > -80).sort((x, y) => x - y);
  const ref = loud.length ? Math.round(loud[Math.floor(loud.length * 0.995)] * 10) / 10 : -10;
  const peaks = [];
  for (let k = 0; k < PEAK_COUNT; k++) {
    peaks.push(Math.round(levelRange(hi, Math.floor((k / PEAK_COUNT) * n), Math.floor(((k + 1) / PEAK_COUNT) * n), ref) * 100) / 100);
  }
  return { peaks, hi, ref };
}

// ältere Titel: Wellenform einmal im Hintergrund neu berechnen
const waveRefreshing = new Set();
async function refreshWave(t) {
  if (waveRefreshing.has(t.id)) return;
  waveRefreshing.add(t.id);
  try {
    const buf = await decodeLow(await getFile(t.id));
    const w = makePeaks(mixMono(buf), buf.sampleRate);
    await putHi(t.id, w.hi);
    hiCache.set(t.id, w.hi);
    Object.assign(t, { peaks: w.peaks, waveRef: w.ref, waveV: WAVE_V });
    overviewCache = null;
    save();
    draw();
  } catch (e) {
    console.warn('Wellenform', e);
  } finally {
    waveRefreshing.delete(t.id);
  }
}

// Für die Analyse in 22 kHz dekodieren: halber Speicher, reicht für Takt und Wellenform
async function decodeLow(blob) {
  try {
    const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    return await new OAC(1, 1, 22050).decodeAudioData(await blob.arrayBuffer());
  } catch (e) {
    return ctx().decodeAudioData(await blob.arrayBuffer());
  }
}

let featCache = { id: null, F: null };

// Analyse im Hintergrund-Thread, damit die App nicht einfriert (Rückfall: im Hauptthread)
let worker = null, workerFailed = false, jobId = 0;
const jobs = new Map();
function getWorker() {
  if (worker || workerFailed) return worker;
  try {
    const url = URL.createObjectURL(new Blob([Analysis.workerSource()], { type: 'text/javascript' }));
    worker = new Worker(url);
    worker.onmessage = (e) => {
      const job = jobs.get(e.data.id);
      if (!job) return;
      if (e.data.progress != null) return job.onProgress?.(e.data.progress);
      jobs.delete(e.data.id);
      if (e.data.error) job.reject(new Error(e.data.error));
      else job.resolve(e.data.result);
    };
    worker.onerror = (e) => {
      console.warn('Worker-Fehler, rechne im Hauptthread', e);
      workerFailed = true;
      worker = null;
      for (const job of jobs.values()) job.fallback();
      jobs.clear();
    };
  } catch (e) {
    workerFailed = true;
  }
  return worker;
}

const fBuffers = (F) => [F.env.buffer, F.flt.buffer, F.onL.buffer, F.rms.buffer, F.chroma.buffer, F.bands.buffer];
function runJob(msg, transfer, direct, onProgress) {
  const w = getWorker();
  if (!w) return Promise.resolve().then(direct);
  return new Promise((resolve, reject) => {
    const id = ++jobId;
    jobs.set(id, { resolve, reject, onProgress, fallback: () => { try { resolve(direct()); } catch (e) { reject(e); } } });
    w.postMessage({ id, ...msg }, transfer);
  });
}
function runAnalysis(mono, sr, onProgress) {
  const copy = new Float32Array(mono);
  return runJob({ op: 'analyse', mono: copy, sr }, [copy.buffer], () => Analysis.analyse(mono, sr, { onProgress }), onProgress);
}
// Rechnung auf den Merkmalen eines Titels im Hintergrund (songs / range)
async function withFeatures(t, msg, direct) {
  const F = await featuresFor(t);
  featCache = { id: t.id, F: null }; // wird übertragen – kommt mit dem Ergebnis zurück
  try {
    const r = await runJob({ ...msg, F }, getWorker() ? fBuffers(F) : [], () => ({ ...direct(F), F }));
    featCache = { id: t.id, F: r.F };
    return r;
  } catch (e) {
    featCache = { id: null, F: null };
    throw e;
  }
}

async function analyseBlob(blob, id = null, label = '') {
  const buf = await decodeLow(blob);
  const mono = mixMono(buf);
  const { peaks, hi, ref } = makePeaks(mono, buf.sampleRate);
  const r = await runAnalysis(mono, buf.sampleRate, (p) => toast(`Analysiere ${label}… ${Math.round(p * 100)} %`, 30000));
  if (id) featCache = { id, F: r.F };
  return { duration: buf.duration, peaks, hi, ref, segments: r.segments, beats: r.beats, ones: r.ones };
}

async function featuresFor(t) {
  if (featCache.id === t.id && featCache.F) return featCache.F;
  toast('Analysiere …', 30000);
  const buf = await decodeLow(await getFile(t.id));
  const mono = mixMono(buf);
  const r = await runJob({ op: 'features', mono, sr: buf.sampleRate }, [mono.buffer],
    () => ({ F: Analysis.features(mono, buf.sampleRate) }),
    (p) => toast(`Analysiere … ${Math.round(p * 100)} %`, 30000));
  featCache = { id: t.id, F: r.F };
  return r.F;
}

function toSegments(list, old = [], duration = Infinity) {
  const endOf = (arr, i) => (i + 1 < arr.length ? arr[i + 1].start : duration);
  const used = new Set();
  const segs = list.map((x, i) => {
    // altes Lied, das sich am meisten mit dem neuen überschneidet
    let best = null, bo = 0;
    old.forEach((o, j) => {
      const ov = Math.min(endOf(list, i), endOf(old, j)) - Math.max(x.start, o.start);
      if (ov > bo && !used.has(j)) { bo = ov; best = j; }
    });
    const len = endOf(list, i) - x.start;
    const o = best != null && bo > len * 0.5 ? old[best] : null;
    if (o) used.add(best);
    return { id: uid(), start: x.start, name: o?.name || '', labelId: o?.labelId || null, color: o?.color || null };
  });
  if (segs.length > 1) {
    const taken = new Set(segs.map((x) => x.color).filter(Boolean));
    for (const sg of segs) {
      if (sg.color) continue;
      sg.color = SEG_COLORS.find((c) => !taken.has(c)) || SEG_COLORS[segs.indexOf(sg) % SEG_COLORS.length];
      taken.add(sg.color);
    }
  }
  return segs;
}

/* ---------- Titelinfos aus der Datei lesen (ID3, MP4, Vorbis) ---------- */
function decodeText(bytes, enc) {
  try {
    if (enc === 0) return new TextDecoder('latin1').decode(bytes);
    if (enc === 1) return new TextDecoder(bytes[0] === 0xfe ? 'utf-16be' : 'utf-16le').decode(bytes.subarray(2));
    if (enc === 2) return new TextDecoder('utf-16be').decode(bytes);
    return new TextDecoder('utf-8').decode(bytes);
  } catch { return ''; }
}
const clean = (s) => (s || '').replace(/\0/g, '').trim();

async function readTags(file) {
  const tags = {};
  try {
    const head = new Uint8Array(await file.slice(0, 512 * 1024).arrayBuffer());
    const str = (b, o, l) => String.fromCharCode(...b.subarray(o, o + l));
    if (str(head, 0, 3) === 'ID3') {
      const ver = head[3];
      const size = (head[6] << 21) | (head[7] << 14) | (head[8] << 7) | head[9];
      let p = 10;
      const end = Math.min(head.length, 10 + size);
      const idLen = ver === 2 ? 3 : 4;
      while (p + idLen * 2 < end) {
        const id = str(head, p, idLen);
        if (!/^[A-Z0-9]+$/.test(id)) break;
        let len;
        if (ver === 2) len = (head[p + 3] << 16) | (head[p + 4] << 8) | head[p + 5];
        else if (ver === 4) len = (head[p + 4] << 21) | (head[p + 5] << 14) | (head[p + 6] << 7) | head[p + 7];
        else len = (head[p + 4] << 24) | (head[p + 5] << 16) | (head[p + 6] << 8) | head[p + 7];
        const hdr = ver === 2 ? 6 : 10;
        const body = head.subarray(p + hdr, p + hdr + len);
        if (['TIT2', 'TT2'].includes(id)) tags.title = clean(decodeText(body.subarray(1), body[0]));
        if (['TPE1', 'TP1'].includes(id)) tags.artist = clean(decodeText(body.subarray(1), body[0]));
        p += hdr + len;
      }
    }
    if (!tags.title) {
      // MP4/M4A: Atome „©nam“ / „©ART“
      const all = file.size < 40e6 ? new Uint8Array(await file.arrayBuffer()) : head;
      const find = (name) => {
        const pat = [0xa9, ...[...name].map((c) => c.charCodeAt(0))];
        outer: for (let i = 0; i < all.length - 24; i++) {
          for (let k = 0; k < 4; k++) if (all[i + k] !== pat[k]) continue outer;
          if (str(all, i + 8, 4) !== 'data') continue;
          const len = ((all[i + 4] << 24) | (all[i + 5] << 16) | (all[i + 6] << 8) | all[i + 7]) - 16;
          if (len > 0 && len < 500) return clean(new TextDecoder().decode(all.subarray(i + 20, i + 20 + len)));
        }
        return '';
      };
      if (str(head, 4, 4) === 'ftyp') {
        tags.title = find('nam');
        tags.artist = find('ART');
      }
    }
    if (!tags.title) {
      // OGG/FLAC: Vorbis-Kommentare „TITLE=…“
      const text = new TextDecoder('utf-8', { fatal: false }).decode(head);
      const m = /(?:^|[\x00-\x1f])TITLE=([^\x00-\x1f]{1,200})/i.exec(text);
      const a = /(?:^|[\x00-\x1f])ARTIST=([^\x00-\x1f]{1,200})/i.exec(text);
      if (m) tags.title = clean(m[1]);
      if (a) tags.artist = clean(a[1]);
    }
  } catch (e) {
    console.warn('Tags nicht lesbar', e);
  }
  return tags;
}

function nameFromFile(name) {
  const base = (name || '').replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ').trim();
  // Android liefert manchmal nur Nummern („1000034567“, „audio:123“)
  if (!base || /^(audio|msf|primary)?[:\s-]*\d+$/i.test(base)) return '';
  return base;
}

/* ---------- Import von Audiodateien ---------- */
async function addFiles(files) {
  const list = [...files];
  if (!list.length) return;
  let ok = 0;
  for (const file of list) {
    const dup = state.tracks.find((t) => t.fileName && t.fileName === file.name && t.fileSize === file.size);
    if (dup && !(await ask('Schon vorhanden', `<p><b>${esc(file.name)}</b> ist schon als „${esc(dup.name)}“ in der App.</p><p>Trotzdem ein zweites Mal laden?</p>`, 'Nochmal laden'))) continue;
    if (file.size > 60e6 && !(await ask('Sehr große Datei', `<p><b>${esc(file.name)}</b> ist ${Math.round(file.size / 1e6)} MB groß. Auf älteren Handys kann das Laden lange dauern oder abbrechen.</p>`, 'Trotzdem laden'))) continue;
    toast(`Lade „${file.name || 'Audio'}“ …`, 60000);
    try {
      const id = uid();
      const [info, tags] = await Promise.all([analyseBlob(file, id, `„${file.name || 'Audio'}“ `), readTags(file)]);
      await putFile(id, file);
      await putHi(id, info.hi);
      hiCache.set(id, info.hi);
      state.tracks.push({
        id,
        name: tags.title || nameFromFile(file.name) || `Titel ${state.tracks.length + 1}`,
        artist: tags.artist || '',
        fileName: file.name || '',
        fileSize: file.size,
        duration: info.duration,
        peaks: info.peaks,
        waveRef: info.ref,
        waveV: WAVE_V,
        groupId: filterGroup || '',
        markers: [],
        cueIn: 0,
        cueOut: null,
        segments: toSegments(info.segments, [], info.duration),
        beats: info.beats,
        ones: info.ones,
      });
      ok++;
      save();
      renderAll();
    } catch (e) {
      console.error(e);
      toast(`„${file.name}“ ist keine lesbare Audiodatei`, 4000);
      await new Promise((r) => setTimeout(r, 1500));
    }
  }
  if (ok) {
    const last = state.tracks[state.tracks.length - 1];
    const n = last.segments.length;
    const g = filterGroup ? group(filterGroup) : null;
    toast((ok === 1 && n > 1 ? `Geladen – ${n} Lieder erkannt` : `${ok} Titel geladen`) + (g ? ` (in Gruppe ${g.name})` : ''), 3500);
    if (!currentId || audio.paused) load(last.id, false);
    checkWarnings();
  }
}

/* ---------- Lieder in der Datei und Zählzeiten ----------
 * t.beats: Zeit jedes Schlags (s), t.ones: Indizes der Schläge, an denen die Zählung mit 1 beginnt
 * (Liedanfänge, Schnitte, eigene Korrekturen). Dazwischen wird 1–8 weitergezählt.
 */
function segIndexAt(t, time) {
  const list = t?.segments || [];
  let i = 0;
  for (let k = 1; k < list.length; k++) if (list[k].start <= time + 1e-6) i = k;
  return i;
}
const segAt = (t, time) => t?.segments?.[segIndexAt(t, time)];
const segEnd = (t, i) => (i + 1 < t.segments.length ? t.segments[i + 1].start : t.duration);
function segName(t, i) {
  const sg = t.segments[i];
  return sg?.name || colorLabel(sg?.labelId)?.name || `Lied ${i + 1}`;
}
function segColor(t, sg) {
  return sg?.color || t?.color || group(t?.groupId)?.color || getComputedStyle(document.documentElement).getPropertyValue('--played').trim();
}
const colorLabel = (id) => (id ? state.colorLabels.find((l) => l.id === id) : null);

// Index des letzten Elements <= x (−1, wenn keins)
function bsearch(arr, x) {
  let lo = 0, hi = arr.length - 1, r = -1;
  while (lo <= hi) {
    const m = (lo + hi) >> 1;
    if (arr[m] <= x + 1e-6) { r = m; lo = m + 1; } else hi = m - 1;
  }
  return r;
}
const hasBeats = (t) => !!t?.beats?.length;
function anchorOf(t, k) {
  const ones = t.ones || [];
  const j = bsearch(ones, k);
  return j >= 0 ? ones[j] : ones[0] ?? 0;
}
const countOfBeat = (t, k) => mod(k - anchorOf(t, k), 8) + 1;
// Schläge eines Lieds: [erster, hinter letztem)
function segBeatRange(t, i) {
  const b = t.beats || [];
  return [bsearch(b, t.segments[i].start - 0.03) + 1, bsearch(b, segEnd(t, i) - 0.03) + 1];
}
function segBpm(t, i) {
  if (!hasBeats(t)) return null;
  const [a, e] = segBeatRange(t, i);
  return Analysis.bpmOfBeats(t.beats.slice(a, e));
}

// Zählzeit an einer Stelle: welches Lied, welche Zahl (1–8), welcher Achter
function countAt(t, time) {
  if (!t?.segments?.length) return null;
  const i = segIndexAt(t, time);
  if (!hasBeats(t)) return { seg: i, count: null };
  const k = bsearch(t.beats, time + 0.005);
  const [a, e] = segBeatRange(t, i);
  if (k < a || k >= e || time - t.beats[k] > 2.5) return { seg: i, count: null };
  let eight = 0;
  for (let j = a; j <= k; j++) if (countOfBeat(t, j) === 1) eight++;
  return { seg: i, k, count: countOfBeat(t, k), eight };
}

function snap(time, t = current(), mode = state.settings.snap) {
  if (!hasBeats(t) || mode === 'off') return time;
  const b = t.beats;
  const k = bsearch(b, time);
  let best = null, bd = Infinity;
  const consider = (j) => {
    if (j < 0 || j >= b.length) return;
    if (mode === 'eight' && countOfBeat(t, j) !== 1) return;
    const d = Math.abs(b[j] - time);
    if (d < bd) { bd = d; best = b[j]; }
  };
  const range = mode === 'eight' ? 8 : 1;
  for (let j = k - range + 1; j <= k + range; j++) consider(j);
  // nah an einer Liedgrenze: genau auf die Grenze
  const i = segIndexAt(t, time);
  if (i > 0 && Math.abs(time - t.segments[i].start) < bd) best = t.segments[i].start;
  return best == null ? time : clamp(best, 0, t.duration);
}

// nächster Schlag nach „after“
function nextBeat(t, after) {
  if (!hasBeats(t)) return null;
  const k = bsearch(t.beats, after) + 1;
  return k < t.beats.length ? { time: t.beats[k], count: countOfBeat(t, k) } : null;
}

// Schläge in [a, e) ersetzen; localOnes zählen ab a
function replaceBeats(t, a, e, nb, localOnes) {
  const delta = nb.length - (e - a);
  t.beats.splice(a, e - a, ...nb.map((x) => Math.round(x * 1000) / 1000));
  const ones = [
    ...t.ones.filter((k) => k < a),
    ...localOnes.filter((j) => j >= 0 && j < nb.length).map((j) => a + j),
    ...t.ones.filter((k) => k >= e).map((k) => k + delta),
  ];
  t.ones = [...new Set(ones)].sort((x, y) => x - y);
}

// aus einem alten Raster (Tempo + Lage der 1) Schläge erzeugen
function gridBeats(from, to, bpm, oneAt) {
  const bl = 60 / bpm;
  const beats = [];
  let k0 = Math.ceil((from - 0.02 - oneAt) / bl);
  for (let k = k0; oneAt + k * bl < to - 0.5 * bl; k++) beats.push(oneAt + k * bl);
  const firstOne = beats.findIndex((x) => Math.abs(mod(Math.round((x - oneAt) / bl), 8)) === 0);
  return { beats, ones: firstOne >= 0 ? [firstOne] : [0] };
}

/* ---------- Playback ---------- */
const cueIn = (t) => t?.cueIn || 0;
const cueOut = (t) => (t && t.cueOut != null ? t.cueOut : t?.duration ?? 0);

let loadToken = 0;
async function load(id, autoplay = true, at = null) {
  const t = track(id);
  if (!t) return;
  const my = ++loadToken;
  cancelFade();
  if (id !== currentId) {
    const url = await urlFor(id);
    if (my !== loadToken) return;
    if (!url) {
      toast('Die Audiodatei fehlt. ✎ beim Titel → „Audiodatei ersetzen“ – Marker und Takt bleiben erhalten.', 6000);
      return;
    }
    if (currentId && playedSinceLoad) lastPlayedId = currentId;
    playedSinceLoad = false;
    currentId = id;
    selectedId = id;
    scrubBuf = null;
    scrubBufFor = null;
    audio.src = url;
    await new Promise((res) => {
      audio.addEventListener('loadedmetadata', res, { once: true });
      audio.addEventListener('error', res, { once: true });
    });
    if (my !== loadToken) return;
    ensureDetail(t);
  }
  audio.currentTime = at ?? cueIn(t);
  lastPos = audio.currentTime;
  resetCountSchedule();
  renderAll();
  if (autoplay) play();
}
let playedSinceLoad = false;

// Detail-Wellenform (und bei alten Titeln das Tempo) bei Bedarf nachberechnen
async function ensureDetail(t) {
  if (hiCache.has(t.id)) return;
  let hi = await getHi(t.id).catch(() => null);
  const needTempo = !t.segments || !t.beats;
  if (!hi || needTempo) {
    try {
      const info = await analyseBlob(await getFile(t.id), t.id);
      hi = info.hi;
      await putHi(t.id, hi);
      Object.assign(t, { peaks: info.peaks, waveRef: info.ref, waveV: WAVE_V });
      if (needTempo) {
        t.segments = toSegments(info.segments, t.segments || [], t.duration);
        t.beats = info.beats;
        t.ones = info.ones;
      }
      save();
      toast('Analyse fertig');
    } catch (e) {
      console.warn(e);
      toast('Analyse fehlgeschlagen');
      return;
    }
  }
  hiCache.set(t.id, hi);
  if (t.waveV !== WAVE_V) refreshWave(t);
  renderAll();
}

function play() {
  const t = current();
  if (!t) {
    const list = visibleTracks();
    const first = track(selectedId) || list[0] || state.tracks[0];
    if (first) load(first.id);
    else toast('Erst einen Titel laden: oben „+ Titel“');
    return;
  }
  cancelFade();
  if (audio.currentTime >= cueOut(t) - 0.05) audio.currentTime = cueIn(t);
  lastPos = audio.currentTime;
  ensureGraph();
  setVol(state.volume);
  resetCountSchedule();
  playedSinceLoad = true;
  audio.play().catch((e) => toast('Wiedergabe blockiert: ' + e.message));
  keepAliveTouch();
}
function pause() { audio.pause(); }
function toggle() { audio.paused ? play() : pause(); }

function stop() {
  cancelFade();
  audio.pause();
  const t = current();
  if (t) audio.currentTime = cueIn(t);
  draw();
}

function seek(sec) {
  const t = current();
  if (!t) return;
  audio.currentTime = clamp(sec, 0, t.duration - 0.01);
  lastPos = audio.currentTime;
  resetCountSchedule();
  draw();
}

function jump(dir) {
  const t = current();
  if (!t) return;
  const now = audio.currentTime;
  if (!state.settings.jumpEight || !hasBeats(t)) return seek(now + dir * 5);
  const b = t.beats;
  let k = bsearch(b, now + 0.05);
  if (k < 0) return seek(dir < 0 ? 0 : b[0]);
  // Anfang des aktuellen Achters
  let s1 = k;
  while (s1 > 0 && countOfBeat(t, s1) !== 1 && k - s1 < 8) s1--;
  let target;
  if (dir < 0) {
    if (k > s1 || now - b[k] > 0.3) target = s1;
    else { target = s1 - 1; while (target > 0 && countOfBeat(t, target) !== 1 && s1 - target < 8) target--; }
  } else {
    target = k + 1;
    while (target < b.length - 1 && countOfBeat(t, target) !== 1 && target - k < 8) target++;
  }
  seek(b[clamp(target, 0, b.length - 1)]);
}

function replay() {
  const t = current();
  if (!t) return;
  audio.currentTime = cueIn(t);
  play();
}

function replayFromMarker() {
  const t = current();
  if (!t) return;
  const before = t.markers.filter((m) => m.time <= audio.currentTime - 0.3);
  const m = before[before.length - 1];
  audio.currentTime = m ? m.time : cueIn(t);
  play();
}

function replayLast() {
  const id = lastPlayedId && track(lastPlayedId) ? lastPlayedId : null;
  if (!id) return toast('Noch kein vorheriger Titel');
  load(id, true);
}

function step(dir) {
  const list = visibleTracks();
  const i = list.findIndex((t) => t.id === currentId);
  const n = list[i + dir];
  if (n) load(n.id, !audio.paused || state.autoNext);
  else toast(dir > 0 ? 'Das ist der letzte Titel der Liste' : 'Das ist der erste Titel der Liste');
}

function onEnded() {
  const t = current();
  if (loop && t) {
    audio.currentTime = cueIn(t);
    resetCountSchedule();
    audio.play();
    return;
  }
  audio.pause();
  if (state.autoNext) {
    const list = visibleTracks();
    const n = list[list.findIndex((x) => x.id === currentId) + 1];
    if (n) return load(n.id, true);
  }
  if (t) audio.currentTime = cueIn(t);
}

let fadeTimer = null;
function fadeOut(ms = 3000) {
  if (audio.paused || fading) return;
  if (!gainNode) { toast(isIOS ? 'Ausblenden geht am iPhone nicht – gestoppt' : 'Gestoppt'); return pause(); }
  const c = audioCtx;
  const g = gainNode.gain;
  g.cancelScheduledValues(c.currentTime);
  g.setValueAtTime(g.value, c.currentTime);
  g.linearRampToValueAtTime(0.0001, c.currentTime + ms / 1000);
  fading = true;
  $('fadeBtn').classList.add('on');
  fadeTimer = setTimeout(() => {
    fading = null;
    audio.pause();
    setVol(state.volume);
    $('fadeBtn').classList.remove('on');
  }, ms + 50);
}
function cancelFade() {
  clearTimeout(fadeTimer);
  fading = null;
  $('fadeBtn')?.classList.remove('on');
  if (gainNode) { gainNode.gain.cancelScheduledValues(audioCtx.currentTime); }
  setVol(state.volume);
}

audio.addEventListener('ended', onEnded);
audio.addEventListener('play', syncPlayBtn);
audio.addEventListener('pause', syncPlayBtn);
audio.addEventListener('seeked', resetCountSchedule);

function syncPlayBtn() {
  const icon = audio.paused ? ICON_PLAY : ICON_PAUSE;
  $('playBtn').innerHTML = icon;
  $('miniPlay').innerHTML = icon;
  if (audio.paused) keepAliveTouch();
  updateMediaSession();
  if (!audio.paused && state.settings.keepAlive && keepEl?.paused) keepEl.play().catch(() => {});
  renderTracks();
  draw();
}

/* ---------- Mitzählen (Stimme / Klick) ---------- */
const voiceCache = new Map(); // id -> AudioBuffer[]
let lastCountTime = null;
let countBuffers = null;

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const sc = document.createElement('script');
    sc.src = src;
    sc.onload = resolve;
    sc.onerror = () => reject(new Error('Stimme nicht gefunden'));
    document.head.append(sc);
  });
}

async function loadCountSounds(id = state.settings.voice) {
  if (voiceCache.has(id)) return (countBuffers = voiceCache.get(id));
  let buffers;
  if (id === 'own') {
    const rec = await getHi('voice:own').catch(() => null);
    if (!rec) throw new Error('Noch keine eigene Stimme aufgenommen');
    buffers = rec.parts.map((data) => {
      const b = ctx().createBuffer(1, data.length, rec.sr);
      b.copyToChannel(data, 0);
      return b;
    });
  } else {
    if (!window.COUNT_VOICES?.[id]) await loadScript(`voices/${id}.js`);
    buffers = await Promise.all(window.COUNT_VOICES[id].map(async (uri) => {
      const bin = atob(uri.split(',')[1]);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return ctx().decodeAudioData(bytes.buffer);
    }));
  }
  voiceCache.set(id, buffers);
  if (id === state.settings.voice) countBuffers = buffers;
  return buffers;
}

async function setVoice(id) {
  if (id === 'own' && !state.ownVoice) {
    renderVoiceSelects();
    $('recordStatus').textContent = 'Bereit';
    $('recordDlg').showModal();
    return;
  }
  state.settings.voice = id;
  save();
  renderVoiceSelects();
  try {
    await loadCountSounds(id);
  } catch (e) {
    toast(e.message, 3000);
  }
}

function renderVoiceSelects() {
  const sel = $('setVoice');
  sel.replaceChildren(...VOICES.map((v) => new Option(v.name + (v.id === 'own' && !state.ownVoice ? ' (noch nicht aufgenommen)' : ''), v.id)));
  sel.value = state.settings.voice;
  $('countVol').value = state.settings.countVolume;
}

async function testVoice() {
  ensureGraph();
  try {
    if (state.settings.countMode !== 'click') await loadCountSounds();
  } catch (e) { return toast(e.message, 3000); }
  const c = ctx();
  const mode = state.settings.countMode;
  if (mode === 'off') state.settings.countMode = 'voice';
  for (let i = 0; i < 8; i++) playCount(i + 1, c.currentTime + 0.1 + i * 0.5);
  state.settings.countMode = mode;
}

/* Eigene Stimme: 10 s aufnehmen, an den Pausen in 8 Wörter schneiden */
let recording = false;
async function recordVoice() {
  if (recording) return;
  const status = $('recordStatus');
  const startBtn = $('recordStart');
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: true, autoGainControl: true } });
  } catch (e) {
    status.textContent = 'Kein Zugriff aufs Mikrofon – bitte in den Einstellungen des Handys erlauben';
    return;
  }
  recording = true;
  startBtn.disabled = true;
  if (!audio.paused) pause();
  const c = ctx();
  const src = c.createMediaStreamSource(stream);
  const proc = c.createScriptProcessor(4096, 1, 1);
  const chunks = [];
  proc.onaudioprocess = (e) => chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
  const mute = c.createGain();
  mute.gain.value = 0;
  src.connect(proc).connect(mute).connect(c.destination);
  const dlg = $('recordDlg');
  let aborted = false;
  const onClose = () => { aborted = true; };
  dlg.addEventListener('close', onClose, { once: true });
  status.classList.add('rec');
  for (let n = 10; n > 0 && !aborted; n--) {
    status.textContent = `● Aufnahme … ${n}`;
    await new Promise((r) => setTimeout(r, 1000));
  }
  dlg.removeEventListener('close', onClose);
  status.classList.remove('rec');
  proc.disconnect();
  src.disconnect();
  stream.getTracks().forEach((t) => t.stop());
  recording = false;
  startBtn.disabled = false;
  if (aborted) return toast('Aufnahme abgebrochen');
  const len = chunks.reduce((n, x) => n + x.length, 0);
  const all = new Float32Array(len);
  let o = 0;
  for (const x of chunks) { all.set(x, o); o += x.length; }
  const parts = splitWords(all, c.sampleRate);
  if (parts.length !== 8) {
    status.textContent = `${parts.length} Wörter erkannt statt 8 – bitte nochmal, deutlich und mit kurzen Pausen dazwischen.`;
    return;
  }
  await putHi('voice:own', { sr: c.sampleRate, parts });
  voiceCache.delete('own');
  state.ownVoice = true;
  await setVoice('own');
  status.textContent = 'Gespeichert ✓ – deine Stimme zählt jetzt mit.';
  testVoice();
}

function splitWords(x, sr) {
  const hop = Math.round(sr / 100);
  const n = Math.floor(x.length / hop);
  const db = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let e = 0;
    for (let j = i * hop; j < (i + 1) * hop; j++) e += x[j] * x[j];
    db[i] = 10 * Math.log10(e / hop + 1e-12);
  }
  const sorted = Array.from(db).sort((a, b) => a - b);
  const noise = sorted[Math.floor(n * 0.2)], peak = sorted[Math.floor(n * 0.995)];
  const thr = Math.max(noise + 10, peak - 32);
  let segs = [];
  for (let i = 0; i < n; i++) {
    if (db[i] < thr) continue;
    let j = i;
    while (j < n && db[j] >= thr - 6) j++;
    segs.push([i, j]);
    i = j;
  }
  // kleine Lücken (z. B. in „sieben“) schließen, Kurzes verwerfen
  const merged = [];
  for (const sg of segs) {
    const last = merged[merged.length - 1];
    if (last && sg[0] - last[1] < 15) last[1] = sg[1];
    else merged.push([...sg]);
  }
  segs = merged.filter(([a, b]) => b - a >= 8);
  while (segs.length > 8) {
    // die zwei am engsten beieinander liegenden zusammenfassen
    let bi = 0, bg = Infinity;
    for (let i = 0; i + 1 < segs.length; i++) { const g = segs[i + 1][0] - segs[i][1]; if (g < bg) { bg = g; bi = i; } }
    segs.splice(bi, 2, [segs[bi][0], segs[bi + 1][1]]);
  }
  return segs.map(([a, b]) => {
    const s0 = Math.max(0, (a - 2) * hop), s1 = Math.min(x.length, (b + 6) * hop);
    const part = x.slice(s0, s1);
    let m = 0;
    for (const v of part) m = Math.max(m, Math.abs(v));
    const g = m ? 0.9 / m : 1;
    const fade = Math.round(sr * 0.005);
    for (let i = 0; i < part.length; i++) {
      const f = Math.min(1, i / fade, (part.length - 1 - i) / fade);
      part[i] *= g * f;
    }
    return part;
  });
}

function resetCountSchedule() {
  // ein Schlag, der gerade erst war (< 30 ms), wird noch gezählt
  lastCountTime = audio.currentTime - 0.03;
}

function playCount(count, when) {
  const c = ctx();
  const g = c.createGain();
  g.gain.value = state.settings.countVolume;
  g.connect(c.destination);
  if (state.settings.countMode === 'voice' && countBuffers?.[count - 1]) {
    const src = c.createBufferSource();
    src.buffer = countBuffers[count - 1];
    src.connect(g);
    src.start(when);
  } else {
    const o = c.createOscillator();
    o.frequency.value = count === 1 ? 1600 : count === 5 ? 1250 : 1000;
    const e = c.createGain();
    e.gain.setValueAtTime(0.0001, when);
    e.gain.exponentialRampToValueAtTime(count === 1 ? 1 : 0.6, when + 0.003);
    e.gain.exponentialRampToValueAtTime(0.0001, when + 0.06);
    o.connect(e).connect(g);
    o.start(when);
    o.stop(when + 0.07);
  }
}

let lastPos = 0;
function scheduler() {
  const t = current();
  if (!t || audio.paused) return;
  const now = audio.currentTime;
  if (t.cueOut != null && lastPos < t.cueOut && now >= t.cueOut) { lastPos = now; onEnded(); return; }
  lastPos = now;
  if (state.settings.countMode === 'off' || !hasBeats(t) || !audioCtx || scrub) return;
  const rate = audio.playbackRate || 1;
  const nb = nextBeat(t, lastCountTime ?? now - 0.03);
  if (!nb) return;
  const ahead = (nb.time - now) / rate;
  if (ahead < 0.15) {
    lastCountTime = nb.time;
    if (ahead > -0.03 && nb.time < cueOut(t)) {
      playCount(nb.count, audioCtx.currentTime + Math.max(0, ahead + state.settings.countOffsetMs / 1000));
    }
  }
}
setInterval(scheduler, 20);

/* ---------- Schieben in der Musik (Scrubbing) ---------- */
let scrub = null;      // {pos, wasPlaying}
let scrubBuf = null;   // dekodierter Titel für Ton beim Schieben
let scrubBufFor = null;
let lastGrain = 0;

async function ensureScrubBuffer() {
  const t = current();
  if (!t || scrubBufFor === t.id) return;
  scrubBufFor = t.id;
  try {
    // nur ein Kanal behalten: halber Speicher
    const buf = await decodeLow(await getFile(t.id));
    const mono = mixMono(buf);
    const b = ctx().createBuffer(1, mono.length, buf.sampleRate);
    b.copyToChannel(mono, 0);
    scrubBuf = b;
  } catch (e) {
    console.warn(e);
    scrubBufFor = null;
  }
}

/* Schieben mit Ton wie bei einer Platte: die Musik läuft so schnell und in die Richtung,
 * in die der Finger zieht – vorwärts wie rückwärts. Hält der Finger still, ist Ruhe. */
let scrubVoice = null;   // {src, gain, dir, rate, pos0, t0}
let scrubSlice = null;   // {start, end, fwd, rev}
let scrubTick = null;

function scrubStart() {
  const t = current();
  if (!t) return false;
  scrub = { pos: audio.currentTime, wasPlaying: !audio.paused, lastPos: audio.currentTime, lastAt: performance.now(), vel: 0 };
  if (scrub.wasPlaying) audio.pause();
  if (state.settings.scrubAudio) { ensureGraph(); ensureScrubBuffer(); }
  clearInterval(scrubTick);
  // Finger steht still → langsam ausklingen lassen
  scrubTick = setInterval(() => {
    if (!scrub) return;
    if (performance.now() - scrub.lastAt > 50) { scrub.vel *= 0.5; scrubSound(); }
  }, 30);
  return true;
}

function scrubTo(pos) {
  const t = current();
  if (!scrub || !t) return;
  const now = performance.now();
  const p = clamp(pos, 0, t.duration - 0.01);
  const dt = Math.max(0.004, (now - scrub.lastAt) / 1000);
  const v = (p - scrub.lastPos) / dt;
  scrub.vel = 0.55 * scrub.vel + 0.45 * v;
  scrub.pos = p;
  scrub.lastPos = p;
  scrub.lastAt = now;
  scrubSound();
  draw();
}

function scrubSlices(pos) {
  if (scrubSlice && pos > scrubSlice.start + 1 && pos < scrubSlice.end - 1) return scrubSlice;
  const sr = scrubBuf.sampleRate;
  const start = Math.max(0, pos - 8), end = Math.min(scrubBuf.duration, pos + 8);
  const a = Math.floor(start * sr), b = Math.floor(end * sr);
  const data = scrubBuf.getChannelData(0).subarray(a, b);
  const fwd = ctx().createBuffer(1, data.length, sr);
  fwd.copyToChannel(data, 0);
  const revData = new Float32Array(data.length);
  for (let i = 0; i < data.length; i++) revData[i] = data[data.length - 1 - i];
  const rev = ctx().createBuffer(1, data.length, sr);
  rev.copyToChannel(revData, 0);
  scrubSlice = { start, end, fwd, rev, buf: scrubBuf };
  return scrubSlice;
}

function scrubVoiceStop(fade = 0.03) {
  if (!scrubVoice) return;
  const c = audioCtx, v = scrubVoice;
  scrubVoice = null;
  v.gain.gain.cancelScheduledValues(c.currentTime);
  v.gain.gain.setValueAtTime(v.gain.gain.value, c.currentTime);
  v.gain.gain.linearRampToValueAtTime(0, c.currentTime + fade);
  try { v.src.stop(c.currentTime + fade + 0.01); } catch (e) { /* schon gestoppt */ }
}

function scrubSound() {
  const t = current();
  if (!scrub || !state.settings.scrubAudio || !scrubBuf || scrubBufFor !== t?.id) return;
  const c = ctx();
  const speed = Math.abs(scrub.vel);
  if (speed < 0.12) return scrubVoiceStop(0.05);
  const dir = scrub.vel > 0 ? 1 : -1;
  const rate = clamp(speed, 0.3, 3);
  if (scrubSlice && scrubSlice.buf !== scrubBuf) scrubSlice = null;
  const sl = scrubSlices(scrub.pos);
  const now = c.currentTime;
  const v = scrubVoice;
  const heard = v ? v.pos0 + v.dir * (now - v.t0) * v.rate : null;
  if (!v || v.dir !== dir || v.slice !== sl || Math.abs(heard - scrub.pos) > 0.08) {
    // neu ansetzen (kurz überblenden)
    scrubVoiceStop(0.015);
    const src = c.createBufferSource();
    src.buffer = dir > 0 ? sl.fwd : sl.rev;
    src.playbackRate.value = rate;
    const g = c.createGain();
    g.gain.setValueAtTime(0, now);
    g.gain.linearRampToValueAtTime(state.volume * 0.9, now + 0.015);
    src.connect(g).connect(c.destination);
    const off = dir > 0 ? scrub.pos - sl.start : sl.end - scrub.pos;
    src.start(now, clamp(off, 0, sl.end - sl.start - 0.01));
    scrubVoice = { src, gain: g, dir, rate, pos0: scrub.pos, t0: now, slice: sl };
  } else if (Math.abs(rate - v.rate) > 0.04) {
    // Tempo dem Finger nachführen
    v.pos0 = heard;
    v.t0 = now;
    v.rate = rate;
    v.src.playbackRate.setTargetAtTime(rate, now, 0.02);
  }
}

function scrubEnd() {
  if (!scrub) return;
  const { pos, wasPlaying } = scrub;
  scrub = null;
  clearInterval(scrubTick);
  scrubVoiceStop(0.04);
  seek(pos);
  if (wasPlaying) play();
}

const viewTime = () => (scrub ? scrub.pos : audio.currentTime);

/* ---------- Marker & Cues ---------- */
function addMarker(time = viewTime()) {
  const t = current();
  if (!t) return toast('Erst einen Titel laden');
  const at = snap(clamp(time, 0, t.duration));
  if (t.markers.some((m) => Math.abs(m.time - at) < 0.05)) return toast('Hier ist schon ein Marker');
  const m = { id: uid(), time: at, label: '', color: MARKER_COLORS[t.markers.length % MARKER_COLORS.length] };
  const c = countAt(t, at + 0.001);
  edit(`Marker bei ${fmt(m.time, true)}${c?.count ? ` (Zählzeit ${c.count})` : ''} – ✎ für Namen`, () => {
    t.markers.push(m);
    t.markers.sort((a, b) => a.time - b.time);
    save();
    renderMarkers();
    renderTracks();
    draw();
  }, t);
}

// Marker an der aktuellen Stelle setzen und gleich den Namen abfragen
function quickMarker() {
  const t = current();
  if (!t) return toast('Erst einen Titel wählen');
  const before = t.markers.length;
  addMarker();
  if (t.markers.length > before) {
    const at = snap(viewTime());
    const m = t.markers.reduce((a, b) => (Math.abs(b.time - at) < Math.abs(a.time - at) ? b : a));
    openMarkerDialog(m, true);
  }
}
const QUICK_NAMES = ['Einmarsch', 'Einsatz', 'Hebefigur', 'Platzwechsel', 'Formation', 'Tusch', 'Übergang', 'Schluss'];

function removeMarker(id) {
  const t = current();
  if (!t) return;
  edit('Marker gelöscht', () => {
    t.markers = t.markers.filter((m) => m.id !== id);
    save();
    renderMarkers();
    renderTracks();
    draw();
  }, t);
}

// Marker als Cue: hinspringen und (je nach Einstellung) sofort abspielen
function cueMarker(m) {
  if (!m) return;
  seek(m.time);
  if (state.settings.markerPlay) play();
}

function setCue(which, at = viewTime()) {
  const t = current();
  if (!t) return;
  const now = snap(at);
  if (which === 'in' && now >= cueOut(t)) return toast('Der Start muss vor dem Ende liegen');
  if (which === 'out' && now <= cueIn(t)) return toast('Das Ende muss nach dem Start liegen');
  const label = which === 'in' ? `Start bei ${fmt(now, true)}` : which === 'out' ? `Ende bei ${fmt(now, true)}` : 'Wird wieder ganz abgespielt';
  edit(label, () => {
    if (which === 'in') t.cueIn = now;
    else if (which === 'out') t.cueOut = now;
    else { t.cueIn = 0; t.cueOut = null; }
    save();
    renderCues();
    renderTracks();
    draw();
  }, t);
}

/* ---------- Tempo und Lieder bearbeiten ---------- */
// Tempo-Änderungen gelten für das Lied an der aktuellen Stelle
function curSegIndex() {
  const t = current();
  return t?.segments ? segIndexAt(t, viewTime()) : -1;
}
function curSeg() {
  const t = current();
  return t?.segments?.[curSegIndex()] ?? null;
}
function afterBeatEdit() {
  save();
  resetCountSchedule();
  renderTempo();
  renderSongs();
  renderMarkers();
  draw();
}

// Schläge eines Lieds ersetzen (Ergebnis einer Erkennung)
function applyRange(t, i, r) {
  t.beats ??= [];
  t.ones ??= [];
  const end = segEnd(t, i);
  while (r.beats.length > 1 && i + 1 < t.segments.length && end - r.beats[r.beats.length - 1] < 0.5 * (r.beats[1] - r.beats[0])) r.beats.pop();
  const [a, e] = segBeatRange(t, i);
  replaceBeats(t, a, e, r.beats, r.ones);
}

// gleichmäßiges Raster mit neuem Tempo, ausgerichtet am Schlag an der aktuellen Stelle
// Tempo vorgeben (Feld oder Tippen): die Schläge werden mit diesem Tempo neu verfolgt,
// die Zählung (wo die 1 ist) bleibt an der aktuellen Stelle erhalten
async function setTempo(bpm) {
  const t = current();
  const i = curSegIndex();
  if (!t || i < 0 || !bpm) return;
  bpm = clamp(bpm, 40, 240);
  const now = viewTime();
  const keepCount = hasBeats(t) ? countAt(t, now) : null;
  let r;
  try {
    r = await withFeatures(t, { op: 'range', start: t.segments[i].start, end: segEnd(t, i), bpm },
      (F) => Analysis.analyseRange(F, t.segments[i].start, segEnd(t, i), bpm));
  } catch (e) {
    console.warn(e);
  }
  edit(`Tempo ${Math.round(bpm * 10) / 10}`, () => {
    if (r?.beats?.length) applyRange(t, i, r);
    else {
      const [a, e] = segBeatRange(t, i);
      const g = gridBeats(t.segments[i].start, segEnd(t, i), bpm, t.segments[i].start);
      replaceBeats(t, a, e, g.beats, g.ones);
    }
    // bisherige Zählung an dieser Stelle beibehalten
    if (keepCount?.count) {
      const k = bsearch(t.beats, now + 0.005);
      if (k >= 0) {
        const one = k - (keepCount.count - 1);
        const [a, e] = segBeatRange(t, i);
        t.ones = [...t.ones.filter((j) => j < a || j >= e), clamp(one, a, e - 1)].sort((x, y) => x - y);
      }
    }
    afterBeatEdit();
  }, t);
}

function scaleTempo(f) {
  const t = current();
  const i = curSegIndex();
  if (!hasBeats(t) || i < 0) return;
  const [a, e] = segBeatRange(t, i);
  if (e - a < 4) return;
  snapshot(t, f < 1 ? 'Halb so schnell zählen' : 'Doppelt so schnell zählen');
  const part = t.beats.slice(a, e);
  const firstOne = (t.ones.find((k) => k >= a && k < e) ?? a) - a;
  let nb, ones;
  if (f < 1) {
    // halbieren: jeden zweiten Schlag behalten, die 1 bleibt erhalten
    nb = part.filter((_, j) => mod(j - firstOne, 2) === 0);
    ones = [0];
  } else {
    nb = [];
    part.forEach((x, j) => { nb.push(x); if (j + 1 < part.length) nb.push((x + part[j + 1]) / 2); });
    ones = [firstOne * 2];
  }
  replaceBeats(t, a, e, nb, ones);
  afterBeatEdit();
  toast(f < 1 ? 'Zählt jetzt halb so schnell' : 'Zählt jetzt doppelt so schnell', 3000, { label: 'Rückgängig', fn: undo });
}

let nudgeTimer = null;
function nudge(sec) {
  const t = current();
  const i = curSegIndex();
  if (!hasBeats(t) || i < 0) return;
  // mehrere Tipper hintereinander = ein Rückgängig-Schritt
  if (!nudgeTimer) snapshot(t, 'Zählung verschoben');
  clearTimeout(nudgeTimer);
  nudgeTimer = setTimeout(() => { nudgeTimer = null; }, 2500);
  const [a, e] = segBeatRange(t, i);
  for (let k = a; k < e; k++) t.beats[k] = Math.round((t.beats[k] + sec) * 1000) / 1000;
  afterBeatEdit();
  toast(`Zählung ${sec < 0 ? 'früher' : 'später'} (${Math.round(sec * 1000)} ms)`, 2500, { label: 'Rückgängig', fn: undo });
}

let taps = [];
let tapTimer = null;
function tapTempo() {
  if (!current()) return;
  if (audio.paused) return toast('Erst die Musik abspielen, dann im Takt tippen');
  const now = performance.now();
  if (taps.length && now - taps[taps.length - 1] > 2000) taps = [];
  taps.push(now);
  if (taps.length > 12) taps.shift();
  clearTimeout(tapTimer);
  if (taps.length < 4) return toast(`Weiter im Takt tippen … (${taps.length}/4)`, 1500);
  const bpm = 60000 / ((taps[taps.length - 1] - taps[0]) / (taps.length - 1));
  toast(`≈ ${Math.round(bpm)} BPM – weiter tippen oder kurz warten`, 1500);
  // erst übernehmen, wenn nicht mehr getippt wird
  tapTimer = setTimeout(() => { taps = []; setTempo(Math.round(bpm * 10) / 10); }, 1600);
}

// Ab dieser Stelle neu mit 1 zählen
// Ab dieser Stelle neu mit 1 zählen
function setOne(at = viewTime(), msg = true) {
  const t = current();
  if (!hasBeats(t)) return toast('Erst Tempo erkennen oder antippen');
  const b = t.beats;
  // bei laufender Musik kommt der Druck meist etwas zu spät (Reaktion, Bluetooth): zurückrechnen
  if (!audio.paused && !scrub) at -= 0.25;
  let k = bsearch(b, at);
  if (k < 0 || (k + 1 < b.length && Math.abs(b[k + 1] - at) < Math.abs(b[k] - at))) k++;
  k = clamp(k, 0, b.length - 1);
  edit('Ab hier wird mit 1 gezählt', () => {
    t.ones = [...t.ones.filter((j) => Math.abs(j - k) >= 3), k].sort((x, y) => x - y);
    afterBeatEdit();
  }, t);
}

// Tempo und Schläge nur für das aktuelle Lied neu erkennen
// Tempo und Schläge nur für das aktuelle Lied neu erkennen
async function redetect() {
  const t = current();
  const i = curSegIndex();
  if (!t || i < 0) return;
  if (!(await ask('Takt neu erkennen?', `<p>Für <b>${esc(segName(t, i))}</b> werden Tempo und Zählung neu erkannt. Eigene Korrekturen in diesem Lied werden ersetzt.</p><p class="sub">Danach kannst du mit „Rückgängig“ zurück.</p>`, 'Neu erkennen'))) return;
  try {
    const r = await withFeatures(t, { op: 'range', start: t.segments[i].start, end: segEnd(t, i) },
      (F) => Analysis.analyseRange(F, t.segments[i].start, segEnd(t, i)));
    if (!r.beats.length) return toast('Kein Takt erkannt – bitte „Im Takt tippen“ benutzen');
    edit(`${segName(t, i)}: Takt neu erkannt (${Analysis.bpmOfBeats(r.beats)} BPM)`, () => { applyRange(t, i, r); afterBeatEdit(); }, t);
  } catch (e) {
    console.warn(e);
    toast('Erkennung fehlgeschlagen');
  }
}

// Alle Lieder in der Datei neu erkennen
// Alle Lieder in der Datei neu erkennen
async function detectSongs() {
  const t = current();
  if (!t) return;
  if (!(await ask('Lieder neu erkennen?', '<p>Liedgrenzen, Tempo und Zählung der ganzen Datei werden neu erkannt. Eigene Korrekturen gehen dabei verloren; Namen und Farben bleiben bei den Liedern, die wiedererkannt werden.</p><p class="sub">Danach kannst du mit „Rückgängig“ zurück.</p>', 'Neu erkennen'))) return;
  try {
    toast('Suche Lieder …', 30000);
    const r = await withFeatures(t, { op: 'songs' }, (F) => Analysis.songsFromFeatures(F));
    edit(`${r.segments.length > 1 ? r.segments.length + ' Lieder' : 'Ein Lied'} erkannt`, () => {
      t.segments = toSegments(r.segments, t.segments, t.duration);
      t.beats = r.beats;
      t.ones = r.ones;
      afterBeatEdit();
      renderAll();
    }, t);
  } catch (e) {
    console.warn(e);
    toast('Erkennung fehlgeschlagen');
  }
}

function colorizeSegments(t) {
  if (t.segments.length < 2) return;
  const used = new Set(t.segments.map((x) => x.color).filter(Boolean));
  for (const sg of t.segments) {
    if (sg.color) continue;
    sg.color = SEG_COLORS.find((c) => !used.has(c)) || SEG_COLORS[0];
    used.add(sg.color);
  }
}

function addOneAt(t, time) {
  if (!hasBeats(t)) return;
  const k = bsearch(t.beats, time - 0.03) + 1;
  if (k < t.beats.length && !t.ones.includes(k)) t.ones = [...t.ones, k].sort((x, y) => x - y);
}

// An der aktuellen Stelle ein neues Lied beginnen lassen
function splitHere() {
  const t = current();
  if (!t?.segments) return;
  const at = snap(viewTime(), t, 'beat');
  const i = segIndexAt(t, at);
  const sg = t.segments[i];
  if (at - sg.start < 2 || segEnd(t, i) - at < 2) return toast('Zu nah an einer Liedgrenze');
  edit(`Neues Lied ab ${fmt(at, true)} – zählt dort mit 1`, () => {
    t.segments.splice(i + 1, 0, { id: uid(), start: Math.round(at * 1000) / 1000, name: '', labelId: null, color: null });
    colorizeSegments(t);
    addOneAt(t, at);
    afterBeatEdit();
    renderAll();
  }, t);
}

function mergeSong(i) {
  const t = current();
  if (!t || i < 1) return;
  edit(`„${segName(t, i)}“ mit dem Lied davor verbunden`, () => {
    t.segments.splice(i, 1);
    if (t.segments.length === 1) t.segments[0].color = null;
    afterBeatEdit();
    renderAll();
  }, t);
}

function setSongStart(i) {
  const t = current();
  if (!t || i < 1) return;
  const at = snap(viewTime(), t, 'beat');
  const lo = t.segments[i - 1].start + 2, hi = segEnd(t, i) - 2;
  if (at < lo || at > hi) return toast('Erst die Musik an die Stelle schieben, wo das Lied beginnt');
  edit(`${segName(t, i)} beginnt jetzt bei ${fmt(at, true)}`, () => {
    const old = t.segments[i].start;
    if (hasBeats(t)) {
      const k = bsearch(t.beats, old - 0.03) + 1;
      t.ones = t.ones.filter((j) => j !== k);
    }
    t.segments[i].start = Math.round(at * 1000) / 1000;
    addOneAt(t, at);
    afterBeatEdit();
    renderAll();
  }, t);
}

/* ---------- Rendering ---------- */
function visibleTracks() {
  const q = $('search').value.trim().toLowerCase();
  return state.tracks.filter((t) =>
    (filterGroup === null || (t.groupId || '') === filterGroup) &&
    (!q || `${t.name} ${t.artist || ''}`.toLowerCase().includes(q)));
}

function renderGroups() {
  const ul = $('groupList');
  ul.replaceChildren();
  const row = (id, name, color, count, editable) => {
    const li = el('li', { className: filterGroup === id ? 'active' : '' });
    const dot = el('span', { className: 'dot' });
    dot.style.background = color;
    li.append(dot, el('span', { className: 'name', textContent: name }), el('span', { className: 'count', textContent: count }));
    if (editable) {
      const edit = el('button', { className: 'edit', textContent: '✎', title: 'Name und Farbe ändern' });
      edit.onclick = (e) => { e.stopPropagation(); openGroupDialog(group(id)); };
      dot.onclick = edit.onclick;
      li.append(edit);
    }
    li.onclick = () => { filterGroup = id; renderGroups(); renderTracks(); };
    return li;
  };
  ul.append(row(null, 'Alle Titel', 'conic-gradient(#e5484d,#ffd23f,#3ddc97,#4cc9f0,#b388ff,#e5484d)', state.tracks.length, false));
  for (const g of state.groups) {
    ul.append(row(g.id, g.name, g.color, state.tracks.filter((t) => t.groupId === g.id).length, true));
  }
  ul.append(row('', 'Ohne Gruppe', '#4a4a58', state.tracks.filter((t) => !t.groupId).length, false));
}

function tempoText(t) {
  const n = t.segments?.length || 0;
  if (n > 1) return `${n} Lieder`;
  const bpm = n ? segBpm(t, 0) : null;
  return bpm ? `${bpm} BPM` : null;
}

let dragId = null;
function renderTracks() {
  const ol = $('trackList');
  ol.replaceChildren();
  const list = visibleTracks();
  list.forEach((t, i) => {
    const g = group(t.groupId);
    const li = el('li', { draggable: !isTouch });
    li.dataset.id = t.id;
    if (t.id === selectedId) li.classList.add('selected');
    if (t.id === currentId) li.classList.add('playing');

    const stripe = el('span', { className: 'stripe' });
    stripe.style.background = t.color || g?.color || '#4a4a58';
    const title = el('span', { className: 'title' },
      el('span', { className: 't1', textContent: (t.id === currentId && !audio.paused ? '▶ ' : '') + t.name }),
      el('span', { className: 't2', textContent: [t.artist, g?.name, tempoText(t), t.markers.length ? `${t.markers.length} Marker` : null].filter(Boolean).join(' · ') }));
    title.title = t.name;

    const playing = t.id === currentId && !audio.paused;
    const playB = el('button', { className: 'row-btn play', textContent: playing ? '⏸' : '▶', title: playing ? 'Pause' : 'Abspielen' });
    playB.onclick = (e) => { e.stopPropagation(); if (t.id === currentId) toggle(); else { load(t.id, true); closeLibrary(); } };
    const editB = el('button', { className: 'row-btn', textContent: '✎', title: 'Titel bearbeiten' });
    editB.onclick = (e) => { e.stopPropagation(); openTrackDialog(t); };

    li.append(stripe, el('span', { className: 'num', textContent: i + 1 }), title,
      el('span', { className: 'meta', textContent: fmt(cueOut(t) - cueIn(t)) }), playB, editB);

    li.onclick = () => { selectedId = t.id; if (t.id !== currentId) { load(t.id, false); closeLibrary(); } };
    li.ondblclick = () => load(t.id, true);

    li.ondragstart = (e) => { dragId = t.id; li.classList.add('dragging'); e.dataTransfer.effectAllowed = 'move'; };
    li.ondragend = () => { dragId = null; li.classList.remove('dragging'); };
    li.ondragover = (e) => { if (dragId) e.preventDefault(); };
    li.ondrop = (e) => {
      if (!dragId || dragId === t.id) return;
      e.preventDefault();
      e.stopPropagation();
      const from = state.tracks.findIndex((x) => x.id === dragId);
      const [moved] = state.tracks.splice(from, 1);
      state.tracks.splice(state.tracks.findIndex((x) => x.id === t.id), 0, moved);
      save();
      renderTracks();
    };
    ol.append(li);
  });
  // Filter sichtbar machen, damit keine Titel „verschwinden“
  const fb = $('filterBar');
  const q = $('search').value.trim();
  if (filterGroup !== null || q) {
    const what = [filterGroup !== null ? `Gruppe „${filterGroup === '' ? 'Ohne Gruppe' : group(filterGroup)?.name}“` : null, q ? `Suche „${q}“` : null].filter(Boolean).join(', ');
    fb.hidden = false;
    fb.replaceChildren(el('span', { textContent: `Nur ${what}: ${list.length} von ${state.tracks.length} Titeln` }));
    const all = el('button', { className: 'btn small', textContent: 'Alle zeigen' });
    all.onclick = () => { filterGroup = null; $('search').value = ''; renderGroups(); renderTracks(); };
    fb.append(all);
  } else fb.hidden = true;
  const hint = $('dropHint');
  hint.hidden = list.length > 0;
  hint.textContent = state.tracks.length ? 'Keine Titel in dieser Auswahl.' : 'Noch keine Titel. Oben „+ Titel“ antippen.';
  $('emptyState').hidden = state.tracks.length > 0;
}

async function removeTrack(id) {
  const t = track(id);
  if (!t) return false;
  const ok = await ask('Titel löschen?', `<p><b>${esc(t.name)}</b> wird aus der App gelöscht – mit Audiodatei, ${t.markers.length} Marker(n), Liedern und Takt-Korrekturen.</p><p class="sub">Das lässt sich nicht rückgängig machen. Die Originaldatei auf dem Handy bleibt erhalten.</p>`, 'Endgültig löschen', true);
  if (!ok) return false;
  if (id === currentId) { audio.pause(); audio.removeAttribute('src'); currentId = null; }
  if (selectedId === id) selectedId = null;
  if (lastPlayedId === id) lastPlayedId = null;
  state.tracks = state.tracks.filter((x) => x.id !== id);
  if (urlCache.has(id)) { URL.revokeObjectURL(urlCache.get(id)); urlCache.delete(id); }
  hiCache.delete(id);
  await delFile(id);
  save();
  renderAll();
  toast('Titel gelöscht');
  return true;
}

function renderNow() {
  const t = current();
  const g = group(t?.groupId);
  $('nowTitle').textContent = t ? t.name : (state.tracks.length ? 'Titel in der Liste antippen' : 'Kein Titel geladen');
  $('nowSub').textContent = t ? [t.artist, g?.name].filter(Boolean).join(' · ') || 'antippen: anderen Titel wählen' : '';
  $('nowColor').style.background = t?.color || g?.color || '#4a4a58';
  $('timeDur').textContent = fmt(t?.duration || 0);
  const eight = state.settings.jumpEight && hasBeats(t);
  $('backCap').textContent = eight ? '−1 Achter' : '−5 s';
  $('fwdCap').textContent = eight ? '+1 Achter' : '+5 s';
  $('miniTitle').textContent = t ? t.name : '';
  $('fadeBtn').hidden = isIOS;
  $('iosVolHint').hidden = !isIOS;
  $('volume').closest('label').hidden = isIOS;
}

function renderCues() {
  const t = current();
  $('cueInVal').textContent = fmt(cueIn(t), true);
  $('cueOutVal').textContent = t?.cueOut != null ? fmt(t.cueOut, true) : 'Ende';
}

function renderTempo() {
  const t = current();
  const sg = curSeg();
  const i = t ? segIndexAt(t, viewTime()) : 0;
  const bpm = t?.segments ? segBpm(t, i) : null;
  const inp = $('bpmInput');
  if (document.activeElement !== inp) inp.value = bpm ?? '';
  const multi = t?.segments?.length > 1;
  $('tempoSeg').textContent = multi ? `– ${segName(t, i)}` : '';
  $('tempoSeg').style.color = multi ? segColor(t, sg) : '';
  $('countBox').classList.toggle('off', !bpm);
  for (const seg of ['countModeSeg', 'snapSeg']) {
    const key = seg === 'countModeSeg' ? 'countMode' : 'snap';
    for (const b of $(seg).children) b.classList.toggle('on', b.dataset.v === state.settings[key]);
  }
  renderNow();
}

// nur die Markierung des aktuellen Lieds umschalten (Eingabefelder nicht neu aufbauen)
function markCurrentSong() {
  const t = current();
  if (!t?.segments) return;
  const curI = segIndexAt(t, viewTime());
  [...$('songList').children].forEach((li, i) => li.classList.toggle('cur', i === curI));
}

function renderSongs() {
  const ul = $('songList');
  ul.replaceChildren();
  const t = current();
  if (!t?.segments) return;
  const curI = segIndexAt(t, viewTime());
  t.segments.forEach((sg, i) => {
    const color = el('button', { className: 'mcolor', title: 'Farbe des Lieds ändern' });
    color.style.background = segColor(t, sg);
    color.onclick = async () => {
      const c = await pickColor(sg.color, sg.labelId);
      if (!c) return;
      edit(`Farbe von ${segName(t, i)} geändert`, () => {
        sg.color = c.color;
        sg.labelId = c.labelId;
        colorizeSegments(t);
        save();
        renderSongs();
        draw();
      }, t);
    };
    const lab = colorLabel(sg.labelId);
    const name = el('input', { type: 'text', value: sg.name, placeholder: lab?.name || `Lied ${i + 1} – Name eintippen`, maxLength: 40 });
    name.oninput = () => { sg.name = name.value; save(); draw(); };
    const startB = el('button', { className: 'mini-text', textContent: `▶ ab ${fmt(sg.start)}`, title: 'Ab Liedanfang abspielen' });
    startB.onclick = () => { seek(sg.start); play(); };
    const sb = segBpm(t, i);
    const meta = el('div', { className: 'song-meta' }, startB,
      el('span', { textContent: [lab ? lab.name : null, sb ? `${sb} BPM` : null].filter(Boolean).join(' · ') }));
    const here = el('button', { className: 'mini-text', textContent: 'Anfang hierher', title: 'Liedanfang auf die aktuelle Stelle legen', disabled: i === 0 });
    here.onclick = () => setSongStart(i);
    const del = el('button', { className: 'mini-text', textContent: 'Mit vorigem verbinden', title: 'Dieses und das vorige Lied sind eins', disabled: i === 0 });
    del.onclick = () => mergeSong(i);
    const li = el('li', { className: i === curI ? 'cur' : '' }, color,
      el('div', { className: 'song-main' }, name, meta),
      el('div', { className: 'song-btns' }, here, del));
    li.style.setProperty('--c', segColor(t, sg));
    ul.append(li);
  });
}

function renderMarkers() {
  const ul = $('markerList');
  ul.replaceChildren();
  const t = current();
  $('addMarkerBtn').textContent = isTouch ? '+ Marker hier' : '+ Marker hier (M)';
  if (!t || !t.markers.length) {
    ul.append(el('li', { className: 'empty', textContent: t ? 'Noch keine Marker. „+ Marker hier“ setzt einen an der aktuellen Stelle – z. B. „Einsatz Garde“, „Hebefigur“.' : '' }));
    return;
  }
  t.markers.forEach((m, i) => {
    const c = countAt(t, m.time + 0.001);
    const cue = el('button', { className: 'mcue', title: 'Ab hier abspielen' },
      el('span', { className: 'mn', textContent: i + 1 }),
      el('span', { className: 'ml', textContent: m.label || `Marker ${i + 1}` }),
      el('span', { className: 'mt', textContent: `${fmt(m.time)}${c?.count ? ` · ${c.count}` : ''}` }));
    cue.style.background = m.color;
    cue.onclick = () => cueMarker(m);
    const ed = el('button', { className: 'medit', textContent: '✎', title: 'Name, Farbe, löschen' });
    ed.onclick = () => openMarkerDialog(m);
    ul.append(el('li', {}, cue, ed));
  });
}

function renderAll() {
  renderGroups();
  renderTracks();
  renderNow();
  renderCues();
  renderTempo();
  renderSongs();
  renderMarkers();
  draw();
}

/* ---------- Dialoge ---------- */
function swatches(container, selected, onPick) {
  container.replaceChildren();
  for (const c of PALETTE) {
    const b = el('button', { type: 'button', className: 'swatch' + (c === selected ? ' on' : ''), title: c });
    b.style.background = c;
    b.onclick = () => onPick(c);
    container.append(b);
  }
}

// Liefert {color, labelId} oder null
function pickColor(currentColor, currentLabel = null) {
  return new Promise((resolve) => {
    const dlg = $('colorDlg');
    let done = false;
    const finish = (v) => { if (done) return; done = true; dlg.close(); resolve(v); };
    swatches($('colorDlgSwatches'), currentLabel ? null : currentColor, (c) => finish({ color: c, labelId: null }));
    const box = $('colorDlgLabels');
    box.replaceChildren();
    $('colorDlgLabelBox').style.display = state.colorLabels.length ? '' : 'none';
    for (const l of state.colorLabels) {
      const b = el('button', { type: 'button', className: 'chip label-chip' + (l.id === currentLabel ? ' on' : ''), textContent: l.name || 'ohne Namen' });
      b.style.setProperty('--c', l.color);
      b.onclick = () => finish({ color: l.color, labelId: l.id });
      box.append(b);
    }
    dlg.onclose = () => { if (!done) { done = true; resolve(null); } };
    dlg.showModal();
  });
}

function renderLabelList() {
  const ul = $('labelList');
  ul.replaceChildren();
  if (!state.colorLabels.length) ul.append(el('li', { className: 'empty', textContent: 'Noch keine – z. B. „Garde tanzt“, „Alle“, „Solo“, „Pause“.' }));
  for (const l of state.colorLabels) {
    const sw = el('button', { type: 'button', className: 'mcolor big' });
    sw.style.background = l.color;
    sw.onclick = async () => {
      const dlg = $('colorDlg');
      $('settingsDlg').close();
      const c = await new Promise((resolve) => {
        swatches($('colorDlgSwatches'), l.color, (x) => { dlg.close(); resolve(x); });
        $('colorDlgLabelBox').style.display = 'none';
        dlg.onclose = () => resolve(null);
        dlg.showModal();
      });
      if (c) {
        l.color = c;
        // alle Lieder mit dieser Bezeichnung umfärben
        for (const t of state.tracks) for (const sg of t.segments || []) if (sg.labelId === l.id) sg.color = c;
        save();
        renderAll();
      }
      openSettings();
    };
    const name = el('input', { type: 'text', value: l.name, placeholder: 'Bezeichnung', maxLength: 30 });
    name.oninput = () => { l.name = name.value; save(); renderSongs(); draw(); };
    const del = el('button', { type: 'button', className: 'mmini del', textContent: '✕', title: 'Löschen' });
    del.onclick = () => {
      state.colorLabels = state.colorLabels.filter((x) => x !== l);
      for (const t of state.tracks) for (const sg of t.segments || []) if (sg.labelId === l.id) sg.labelId = null;
      save();
      renderLabelList();
      renderSongs();
    };
    ul.append(el('li', {}, sw, name, del));
  }
}

function openGroupDialog(g) {
  const isNew = !g;
  const dlg = $('groupDlg');
  let color = g?.color || PALETTE[state.groups.length % PALETTE.length];
  $('groupDlgTitle').textContent = isNew ? 'Neue Gruppe' : 'Gruppe bearbeiten';
  $('groupDlgName').value = g?.name || '';
  $('groupDlgDelete').parentElement.hidden = isNew;
  const paint = () => swatches($('groupDlgColors'), color, (c) => { color = c; paint(); });
  paint();
  $('groupDlgDelete').onclick = async () => {
    dlg.close();
    const n = state.tracks.filter((t) => t.groupId === g.id).length;
    if (!(await ask('Gruppe löschen?', `<p>Gruppe <b>${esc(g.name)}</b> löschen? ${n ? `Die ${n} Titel darin bleiben erhalten und stehen danach unter „Ohne Gruppe“.` : ''}</p>`, 'Löschen', true))) return;
    state.groups = state.groups.filter((x) => x.id !== g.id);
    state.tracks.forEach((t) => { if (t.groupId === g.id) t.groupId = ''; });
    if (filterGroup === g.id) filterGroup = null;
    save();
    renderAll();
  };
  dlg.onclose = () => {
    if (dlg.returnValue !== 'save') return;
    const name = $('groupDlgName').value.trim();
    if (!name) return;
    if (isNew) state.groups.push({ id: 'g-' + uid(), name, color });
    else Object.assign(g, { name, color });
    save();
    renderAll();
  };
  dlg.returnValue = '';
  dlg.showModal();
}

function openTrackDialog(t) {
  const dlg = $('trackDlg');
  let groupId = t.groupId || '';
  let color = t.color || null, labelId = t.labelId || null;
  const paintColor = () => {
    $('trackDlgColor').style.background = color || group(groupId)?.color || '#4a4a58';
    $('trackDlgColorName').textContent = color ? colorLabel(labelId)?.name || 'eigene Farbe' : 'Farbe der Gruppe';
  };
  $('trackDlgColor').onclick = async () => {
    dlg.close('keep');
    const c = await pickColor(color, labelId);
    if (c) { color = c.color; labelId = c.labelId; }
    paintColor();
    dlg.returnValue = '';
    dlg.showModal();
  };
  $('trackDlgColorReset').onclick = () => { color = null; labelId = null; paintColor(); };
  $('trackDlgName').value = t.name;
  $('trackDlgArtist').value = t.artist || '';
  $('trackDlgInfo').textContent = `Datei: ${t.fileName || '–'} · Länge ${fmt(t.duration)}${tempoText(t) ? ' · ' + tempoText(t) : ''}`;
  const paint = () => {
    const box = $('trackDlgGroups');
    box.replaceChildren();
    for (const g of [{ id: '', name: 'keine', color: '#4a4a58' }, ...state.groups]) {
      const b = el('button', { type: 'button', className: 'chip' + (g.id === groupId ? ' on' : ''), textContent: g.name });
      b.style.setProperty('--c', g.color);
      b.onclick = () => { groupId = g.id; paint(); paintColor(); };
      box.append(b);
    }
  };
  paint();
  paintColor();
  const move = (dir) => {
    const i = state.tracks.indexOf(t), j = i + dir;
    if (j < 0 || j >= state.tracks.length) return toast(dir < 0 ? 'Steht schon ganz oben' : 'Steht schon ganz unten');
    [state.tracks[i], state.tracks[j]] = [state.tracks[j], state.tracks[i]];
    save();
    renderTracks();
    toast(`Jetzt Platz ${j + 1} von ${state.tracks.length}`, 1500);
  };
  $('trackDlgUp').onclick = () => move(-1);
  $('trackDlgDown').onclick = () => move(1);
  $('trackDlgDelete').onclick = async () => { dlg.close(); await removeTrack(t.id); };
  $('trackDlgReplace').onchange = async (e) => {
    const f = e.target.files[0];
    e.target.value = '';
    if (!f) return;
    dlg.close();
    await replaceAudio(t, f);
  };
  dlg.onclose = () => {
    if (dlg.returnValue !== 'save') return;
    edit('Titel geändert', () => {
      t.name = $('trackDlgName').value.trim() || t.name;
      t.artist = $('trackDlgArtist').value.trim();
      t.groupId = groupId;
      t.color = color;
      t.labelId = labelId;
      save();
      renderAll();
    }, t);
  };
  dlg.returnValue = '';
  dlg.showModal();
}

// Audiodatei austauschen, Marker/Takt/Lieder bleiben
async function replaceAudio(t, file) {
  try {
    toast('Lade neue Audiodatei …', 30000);
    const info = await analyseBlob(file, t.id, '');
    if (Math.abs(info.duration - t.duration) > 2) {
      const ok = await ask('Andere Länge', `<p>Die neue Datei ist ${fmt(info.duration)} lang, die alte ${fmt(t.duration)}. Marker und Takt passen dann eventuell nicht mehr.</p>`, 'Trotzdem ersetzen');
      if (!ok) return toast('Abgebrochen');
    }
    await putFile(t.id, file);
    await putHi(t.id, info.hi);
    hiCache.set(t.id, info.hi);
    if (urlCache.has(t.id)) { URL.revokeObjectURL(urlCache.get(t.id)); urlCache.delete(t.id); }
    t.duration = info.duration;
    t.peaks = info.peaks;
    t.waveRef = info.ref;
    t.waveV = WAVE_V;
    t.fileName = file.name || t.fileName;
    if (!t.beats?.length) { t.segments = toSegments(info.segments, t.segments || [], t.duration); t.beats = info.beats; t.ones = info.ones; }
    save();
    if (currentId === t.id) { currentId = null; await load(t.id, false); }
    renderAll();
    toast('Audiodatei ersetzt – Marker und Takt sind erhalten');
  } catch (e) {
    console.warn(e);
    toast('Die Datei konnte nicht gelesen werden');
  }
}

function openMarkerDialog(m, fresh = false) {
  const t = current();
  if (!t) return;
  const dlg = $('markerDlg');
  const i = t.markers.indexOf(m);
  let color = m.color;
  $('markerDlgTitle').textContent = `Marker ${i + 1} bei ${fmt(m.time, true)}`;
  $('markerDlgName').value = m.label || '';
  const qn = $('markerDlgQuick');
  qn.replaceChildren(...QUICK_NAMES.map((n) => {
    const b = el('button', { type: 'button', textContent: n });
    b.onclick = () => { $('markerDlgName').value = n; };
    return b;
  }));
  if (fresh) setTimeout(() => $('markerDlgName').focus(), 50);
  const paint = () => swatches($('markerDlgColors'), color, (c) => { color = c; paint(); });
  paint();
  $('markerDlgIn').onclick = () => { dlg.close(); setCue('in', m.time); };
  $('markerDlgOut').onclick = () => { dlg.close(); setCue('out', m.time); };
  $('markerDlgDelete').onclick = () => { dlg.close(); removeMarker(m.id); };
  dlg.onclose = () => {
    if (dlg.returnValue !== 'save') return;
    edit('Marker geändert', () => {
      m.label = $('markerDlgName').value.trim();
      m.color = color;
      save();
      renderMarkers();
      draw();
    }, t);
  };
  dlg.returnValue = '';
  dlg.showModal();
}

function openSettings() {
  const s = state.settings;
  $('setScrubAudio').checked = s.scrubAudio;
  $('setMarkerPlay').checked = s.markerPlay;
  $('setJumpEight').checked = s.jumpEight;
  $('setWaveLock').checked = !!s.waveLock;
  $('setCountOffset').value = s.countOffsetMs;
  $('setCountOffsetVal').textContent = `${s.countOffsetMs} ms`;
  renderVoiceSelects();
  renderLabelList();
  $('setKeepAlive').checked = s.keepAlive;
  $('setKeepLevel').value = s.keepLevel;
  $('setScreenOn').checked = s.screenOn;
  $('iosHint').hidden = !isIOS;
  $('appVersion').textContent = APP_VERSION;
  $('lastBackup').textContent = state.lastBackup ? `Letzte Sicherung: ${new Date(state.lastBackup).toLocaleString('de-DE')}` : 'Noch nie gesichert.';
  updateBatteryRow();
  $('settingsDlg').showModal();
}
for (const [id, key, prop] of [
  ['setScrubAudio', 'scrubAudio', 'checked'], ['setMarkerPlay', 'markerPlay', 'checked'],
  ['setJumpEight', 'jumpEight', 'checked'], ['setWaveLock', 'waveLock', 'checked'],
  ['setCountOffset', 'countOffsetMs', 'value'],
]) {
  $(id).addEventListener('input', (e) => {
    state.settings[key] = prop === 'checked' ? e.target.checked : +e.target.value;
    $('setCountOffsetVal').textContent = `${state.settings.countOffsetMs} ms`;
    $('zoom').parentElement.classList.toggle('locked', !!state.settings.waveLock);
    save();
    renderNow();
  });
}

/* ---------- Wellenformen ---------- */
const wave = $('wave');
const zoom = $('zoom');
const LANE_H = 16;
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function prepCanvas(c) {
  const dpr = window.devicePixelRatio || 1;
  const w = c.clientWidth, h = c.clientHeight;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
    c.width = Math.round(w * dpr);
    c.height = Math.round(h * dpr);
  }
  const g = c.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  return { g, w, h };
}

// Marker-Beschriftungen auf Bahnen verteilen, damit sie sich nicht überdecken
function layoutLabels(g, items, maxLanes) {
  g.font = '600 11px system-ui, sans-serif';
  const laneEnd = [];
  for (const it of items) {
    const tw = Math.min(g.measureText(it.text).width + 8, 150);
    let lane = laneEnd.findIndex((end) => end <= it.x - 2);
    if (lane < 0) lane = laneEnd.length < maxLanes ? laneEnd.length : laneEnd.indexOf(Math.min(...laneEnd));
    it.lane = lane;
    it.w = tw;
    laneEnd[lane] = it.x + tw;
  }
  return Math.max(1, laneEnd.length);
}

function drawLabels(g, items, w, top) {
  g.font = '600 11px system-ui, sans-serif';
  for (const it of items) {
    const y = top + it.lane * LANE_H;
    const left = it.x + it.w > w ? Math.max(0, it.x - it.w + 2) : it.x;
    g.fillStyle = it.color;
    g.fillRect(left, y, it.w, LANE_H - 2);
    g.fillStyle = '#111';
    g.save();
    g.beginPath();
    g.rect(left, y, it.w - 2, LANE_H - 2);
    g.clip();
    g.textBaseline = 'middle';
    g.fillText(it.text, left + 4, y + (LANE_H - 2) / 2 + 0.5);
    g.restore();
  }
}

const markerText = (m, i) => `${i + 1}${m.label ? ' ' + m.label : ''}`;

let waveLayout = { top: 0 };
// Schmaler Streifen: ganzes Stück mit Liedfarben, Markern und dem Ausschnitt der großen Leiste
let overviewCache = null;
function overviewLevels(t, cols) {
  const hi = hiCache.get(t.id);
  if (!hi || t.waveV !== WAVE_V) {
    return Array.from({ length: cols }, (_, c) => t.peaks[Math.floor((c / cols) * t.peaks.length)] || 0);
  }
  const key = `${t.id}:${cols}:${hi.length}`;
  if (overviewCache?.key === key) return overviewCache.lv;
  const lv = new Float32Array(cols);
  for (let c = 0; c < cols; c++) lv[c] = levelRange(hi, Math.floor((c / cols) * hi.length), Math.floor(((c + 1) / cols) * hi.length), t.waveRef ?? -10);
  overviewCache = { key, lv };
  return lv;
}
function drawOverview() {
  const { g, w, h } = prepCanvas(wave);
  const t = current();
  const unplayed = css('--unplayed');
  if (!t) { g.fillStyle = unplayed; g.fillRect(0, h / 2, w, 1); return; }
  const dur = t.duration || 1;
  const x = (sec) => (sec / dur) * w;
  const cur = viewTime();
  const segs = t.segments || [{ start: 0 }];
  const multi = segs.length > 1;
  const BAND = 6, TOP = 8;
  const mid = TOP + (h - TOP - BAND) / 2, amp = (h - TOP - BAND) / 2 - 1;
  const px = x(cur), inX = x(cueIn(t)), outX = x(cueOut(t));
  let si = 0;
  const cols = Math.ceil(w / 2);
  const lvs = overviewLevels(t, cols);
  for (let bx = 0; bx < w; bx += 2) {
    const sec = (bx / w) * dur;
    while (si + 1 < segs.length && segs[si + 1].start <= sec) si++;
    const p = Math.max(0.05, lvs[bx / 2] || 0);
    const played = bx < px;
    g.fillStyle = played || multi ? segColor(t, segs[si]) : unplayed;
    g.globalAlpha = (bx < inX || bx > outX ? 0.3 : 1) * (played ? 1 : multi ? 0.4 : 1);
    g.fillRect(bx, mid - p * amp, 1.5, p * amp * 2);
    // Farbband der Lieder
    g.globalAlpha = 1;
    if (multi) { g.fillStyle = segColor(t, segs[si]); g.fillRect(bx, h - BAND + 1, 2, BAND - 1); }
  }
  g.globalAlpha = 1;
  // Ausschnitt der großen Leiste
  const span = state.settings.zoom;
  g.strokeStyle = 'rgba(255,255,255,.55)';
  g.lineWidth = 1;
  g.strokeRect(Math.max(0.5, x(cur - span / 2)), TOP - 1.5, Math.max(4, x(span)), h - TOP - BAND + 2);
  // Marker als Dreiecke oben
  waveLayout = { items: t.markers.map((m) => ({ x: x(m.time), m })) };
  for (const it of waveLayout.items) {
    g.fillStyle = it.m.color;
    g.beginPath();
    g.moveTo(it.x - 5, 0); g.lineTo(it.x + 5, 0); g.lineTo(it.x, TOP);
    g.fill();
  }
  g.fillStyle = css('--accent-2');
  if (t.cueIn > 0) g.fillRect(inX, TOP, 1, h - TOP);
  if (t.cueOut != null) g.fillRect(outX - 1, TOP, 1, h - TOP);
  g.fillStyle = '#fff';
  g.fillRect(px - 1, 0, 2, h);
}

let zoomLayout = {};
function drawZoom() {
  const { g, w, h } = prepCanvas(zoom);
  const t = current();
  if (!t) return;
  const hi = hiCache.get(t.id);
  const span = state.settings.zoom;
  const cur = viewTime();
  const start = cur - span / 2;
  const pxPerSec = w / span;
  const X = (sec) => (sec - start) * pxPerSec;
  const COUNT_H = 20;
  const segs = t.segments || [];

  const vis = t.markers.map((m, i) => ({ m, i })).filter(({ m }) => m.time >= start - 2 && m.time <= start + span);
  const items = vis.map(({ m, i }) => ({ x: Math.round(X(m.time)), text: markerText(m, i), color: m.color, m }));
  const lanes = items.length ? layoutLabels(g, items, 2) : 0;
  const top = COUNT_H + lanes * LANE_H;
  zoomLayout = { start, pxPerSec, items, top, COUNT_H };

  const unplayed = css('--unplayed');
  const multi = segs.length > 1;
  const mid = top + (h - top) / 2;
  const amp = (h - top) / 2 - 2;
  if (hi) {
    const dpr = window.devicePixelRatio || 1;
    const v2 = t.waveV === WAVE_V;
    // jeder Balken steht für ein festes Zeitstück → Höhen bleiben beim Abspielen ruhig
    const binSec = Math.max(3 / pxPerSec, 1 / HI_RATE);
    const barW = Math.max(1, Math.round(binSec * pxPerSec * 0.66 * dpr) / dpr);
    for (let k = Math.floor(Math.max(0, start) / binSec); k * binSec < start + span; k++) {
      const sec = k * binSec;
      if (sec > t.duration) break;
      const a = Math.floor(sec * HI_RATE), b = Math.max(a + 1, Math.floor((sec + binSec) * HI_RATE));
      let lv;
      if (v2) lv = levelRange(hi, a, b, t.waveRef ?? -10);
      else { let p = 0; for (let j = a; j < b && j < hi.length; j++) if (hi[j] > p) p = hi[j]; lv = p / 255; }
      const v = Math.max(0.02, lv) * amp;
      const x = Math.round(X(sec) * dpr) / dpr;
      const outside = sec < cueIn(t) || sec > cueOut(t);
      const played = sec + binSec / 2 < cur;
      g.fillStyle = played || multi ? segColor(t, segAt(t, sec)) : unplayed;
      g.globalAlpha = (outside ? 0.3 : 1) * (played ? 1 : multi ? 0.38 : 1);
      g.fillRect(x, mid - v, barW, v * 2);
    }
    g.globalAlpha = 1;
  } else {
    g.fillStyle = css('--muted');
    g.font = '12px system-ui, sans-serif';
    g.fillText('Detailansicht wird berechnet …', 10, mid);
  }

  // Liedgrenzen
  segs.forEach((sg, i) => {
    if (i === 0 || sg.start < start || sg.start > start + span) return;
    g.fillStyle = segColor(t, sg);
    g.fillRect(X(sg.start) - 1, 0, 3, h);
    g.font = '700 11px system-ui, sans-serif';
    g.textAlign = 'left';
    g.textBaseline = 'middle';
    g.fillText(segName(t, i), X(sg.start) + 5, top + 8);
  });
  // Zählzeiten 1–8 an den erkannten Schlägen
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  if (hasBeats(t)) {
    const b = t.beats;
    const vis = [];
    for (let k = Math.max(0, bsearch(b, start)); k < b.length && b[k] <= start + span; k++) {
      vis.push({ x: X(b[k]), c: countOfBeat(t, k) });
    }
    for (const v of vis) {
      const one = v.c === 1;
      g.fillStyle = one ? 'rgba(255,210,63,.55)' : 'rgba(255,255,255,.12)';
      g.fillRect(Math.round(v.x), COUNT_H, one ? 2 : 1, h - COUNT_H);
    }
    // Zahlen ohne Überlappung: erst alle Einsen, dann die übrigen nur, wo Platz ist
    const placed = [];
    const free = (x, gap) => placed.every((p) => Math.abs(p - x) >= gap);
    const label = (v) => {
      const one = v.c === 1;
      g.font = one ? '700 13px system-ui, sans-serif' : '600 11px system-ui, sans-serif';
      g.fillStyle = one ? css('--accent-2') : v.c === 5 ? '#ddd' : '#999';
      g.fillText(String(v.c), v.x, COUNT_H / 2);
      placed.push(v.x);
    };
    for (const v of vis) if (v.c === 1 && free(v.x, 12)) label(v);
    for (const want of [[5], [3, 7], [2, 4, 6, 8]]) {
      for (const v of vis) if (want.includes(v.c) && free(v.x, 13)) label(v);
    }
  } else {
    g.textAlign = 'left';
    g.fillStyle = css('--muted');
    g.font = '11px system-ui, sans-serif';
    g.fillText('Kein Takt – „Erkennen“ oder „Tap“ benutzen', 8, COUNT_H / 2);
  }
  g.textAlign = 'left';

  g.fillStyle = css('--accent-2');
  if (t.cueIn > 0) g.fillRect(X(t.cueIn), top, 2, h - top);
  if (t.cueOut != null) g.fillRect(X(t.cueOut) - 2, top, 2, h - top);

  for (const it of items) {
    g.fillStyle = it.color;
    g.fillRect(it.x, COUNT_H, 2, h - COUNT_H);
  }
  drawLabels(g, items, w, COUNT_H);

  g.fillStyle = '#fff';
  g.fillRect(w / 2 - 1, COUNT_H - 2, 2, h - COUNT_H + 2);
  g.beginPath();
  g.moveTo(w / 2 - 6, COUNT_H - 4);
  g.lineTo(w / 2 + 6, COUNT_H - 4);
  g.lineTo(w / 2, COUNT_H + 3);
  g.fill();
}

let lastCountShown = null;
function drawCount() {
  const t = current();
  const num = $('countNum'), eight = $('countEight');
  const c = countAt(t, viewTime());
  const multi = t?.segments?.length > 1;
  const box = $('countBox');
  box.style.borderColor = multi && c ? segColor(t, t.segments[c.seg]) : '';
  if (!c?.count) {
    num.textContent = '–';
    eight.textContent = t ? (multi && c ? segName(t, c.seg) : 'kein Takt') : '';
    $('miniCount').textContent = '';
    if (lastCountShown !== null) { lastCountShown = null; renderTempo(); markCurrentSong(); }
    return;
  }
  const key = `${c.seg}:${c.k}`;
  if (key === lastCountShown) return;
  const segChanged = !lastCountShown || lastCountShown.split(':')[0] !== String(c.seg);
  lastCountShown = key;
  num.textContent = c.count;
  const e = c.eight >= 1 ? `Achter ${c.eight}` : 'Vorlauf';
  eight.textContent = multi ? `${e} · Lied ${c.seg + 1}` : e;
  $('miniCount').textContent = `${c.count} · ${e}`;
  box.classList.toggle('one', c.count === 1);
  if (!audio.paused) { box.classList.remove('pulse'); void box.offsetWidth; box.classList.add('pulse'); }
  if (segChanged) { renderTempo(); markCurrentSong(); }
}

function draw() {
  const t = current();
  const cur = viewTime();
  $('timeCur').textContent = fmt(cur);
  $('timeRemain').textContent = '-' + fmt(Math.max(0, cueOut(t) - cur));
  drawOverview();
  drawZoom();
  drawCount();
}

function frame() {
  if (current() && (!audio.paused || scrub)) draw();
  requestAnimationFrame(frame);
}

/* ---------- Bedienung der Wellenformen ---------- */
function hitMarker(items, x, tol = 14) {
  let best = null, bd = tol;
  for (const it of items || []) {
    const d = Math.abs(it.x - x);
    if (d < bd) { bd = d; best = it; }
  }
  return best;
}

/* Fingerbedienung: senkrecht wischen = Seite scrollen (nichts passiert),
 * waagerecht ziehen = in der Musik schieben, kurz tippen = springen.
 * „Musik-Leiste sperren“ (⚙) schaltet beides für Auftritte ab. */
function gesture(canvas, { onScrubStart, onScrubMove, onTap }) {
  let g = null;
  canvas.addEventListener('pointerdown', (e) => {
    if (!current() || e.button === 2) return;
    g = { x0: e.clientX, y0: e.clientY, ly: e.clientY, t: performance.now(), mode: null, id: e.pointerId };
    canvas.setPointerCapture(e.pointerId);
  });
  canvas.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x0, dy = e.clientY - g.y0;
    if (!g.mode) {
      if (Math.abs(dy) > 12 && Math.abs(dy) > 1.5 * Math.abs(dx)) g.mode = 'scroll';
      else if (Math.abs(dx) > 8 && Math.abs(dx) >= Math.abs(dy)) {
        if (state.settings.waveLock) { g.mode = 'locked'; toast('Musik-Leiste ist gesperrt (⚙ → Bedienung)'); return; }
        g.mode = 'scrub';
        onScrubStart(e);
      }
    }
    if (g.mode === 'scrub') { e.preventDefault(); onScrubMove(e, dx); }
    else if (g.mode === 'scroll') window.scrollBy(0, g.ly - e.clientY);
    g.ly = e.clientY;
  });
  const end = (e, cancel) => {
    if (!g || e.pointerId !== g.id) return;
    const was = g;
    g = null;
    if (was.mode === 'scrub') return scrubEnd();
    if (cancel || was.mode) return;
    const dx = e.clientX - was.x0, dy = e.clientY - was.y0;
    if (Math.abs(dx) < 8 && Math.abs(dy) < 8 && performance.now() - was.t < 500) {
      if (state.settings.waveLock) return toast('Musik-Leiste ist gesperrt (⚙ → Bedienung)');
      onTap(e);
    }
  };
  canvas.addEventListener('pointerup', (e) => end(e, false));
  canvas.addEventListener('pointercancel', (e) => end(e, true));
}

// Schmaler Streifen: tippen = an die Stelle springen, ziehen = schnell durchs Stück
gesture(wave, {
  onScrubStart: () => scrubStart(),
  onScrubMove: (e) => {
    const r = wave.getBoundingClientRect();
    scrubTo(clamp((e.clientX - r.left) / r.width, 0, 1) * current().duration);
  },
  onTap: (e) => {
    const t = current();
    const r = wave.getBoundingClientRect();
    const x = e.clientX - r.left;
    const hit = hitMarker(waveLayout.items, x, 12);
    if (hit && e.clientY - r.top < 16) return cueMarker(hit.m);
    seek(snap((x / r.width) * t.duration));
  },
});

// Große Leiste: Musik mit dem Finger schieben, tippen = springen
let zm0 = 0;
gesture(zoom, {
  onScrubStart: () => { zm0 = audio.currentTime; scrubStart(); },
  onScrubMove: (e, dx) => scrubTo(zm0 - dx / zoomLayout.pxPerSec),
  onTap: (e) => {
    const r = zoom.getBoundingClientRect();
    const x = e.clientX - r.left;
    const hit = hitMarker(zoomLayout.items, x, 14);
    if (hit) return cueMarker(hit.m);
    seek(snap(zoomLayout.start + x / zoomLayout.pxPerSec));
  },
});
zoom.addEventListener('wheel', (e) => {
  if (!current() || state.settings.waveLock || Math.abs(e.deltaX) < Math.abs(e.deltaY)) return;
  e.preventDefault();
  seek(audio.currentTime + e.deltaX / zoomLayout.pxPerSec * 0.5);
}, { passive: false });

function setZoom(dir) {
  const i = clamp(ZOOMS.indexOf(state.settings.zoom) + dir, 0, ZOOMS.length - 1);
  state.settings.zoom = ZOOMS[i] ?? 8;
  save();
  draw();
}

/* ---------- Export / Import der Setliste ---------- */
const isNative = () => !!window.Capacitor?.isNativePlatform?.();

async function exportSet() {
  const data = {
    app: 'narrhalla-player',
    version: 3,
    appVersion: APP_VERSION,
    exported: new Date().toISOString(),
    groups: state.groups,
    colorLabels: state.colorLabels,
    settings: state.settings,
    tracks: state.tracks.map(({ peaks, id, ...rest }) => rest),
  };
  const json = JSON.stringify(data);
  const name = `narrhalla-sicherung-${new Date().toISOString().slice(0, 10)}.json`;
  const done = () => {
    state.lastBackup = Date.now();
    save();
    hideBanner('backup');
    hideBanner('persist');
    if (state.snoozed) { delete state.snoozed.backup; save(); }
    if ($('lastBackup')) $('lastBackup').textContent = `Letzte Sicherung: ${new Date(state.lastBackup).toLocaleString('de-DE')}`;
  };
  if (isNative()) {
    try {
      const cap = window.Capacitor;
      const { uri } = await cap.nativePromise('Filesystem', 'writeFile', {
        path: name, data: json, directory: 'CACHE', encoding: 'utf8', recursive: true,
      });
      await cap.nativePromise('Share', 'share', {
        title: 'Narrhalla Sicherung',
        dialogTitle: 'Sicherung speichern – z. B. in Drive oder per Mail an dich',
        files: [uri],
      });
      done();
      toast('Sicherung erstellt');
    } catch (e) {
      if (!/cancel/i.test(e?.message || '')) toast('Sichern fehlgeschlagen: ' + (e?.message || e), 5000);
    }
    return;
  }
  const blob = new Blob([json], { type: 'application/json' });
  const file = new File([blob], name, { type: 'application/json' });
  if (navigator.canShare?.({ files: [file] }) && /Android|iPhone|iPad/i.test(navigator.userAgent)) {
    try { await navigator.share({ files: [file], title: 'Narrhalla Sicherung' }); done(); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  const a = el('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  done();
  toast('Sicherung gespeichert: ' + name, 4000);
}

async function importSet(file) {
  let data;
  try {
    data = JSON.parse(await file.text());
  } catch (e) {
    return toast('Das ist keine Sicherungsdatei des Narrhalla Players (.json)', 5000);
  }
  if (data?.app !== 'narrhalla-player') return toast('Das ist keine Sicherungsdatei des Narrhalla Players', 5000);
  // Zuordnung vorschlagen: Name, Dateiname – Länge nur als Vorschlag mit Kennzeichnung
  const used = new Set();
  const pairs = [];
  for (const src of data.tracks || []) {
    const free = state.tracks.filter((x) => !used.has(x.id));
    let t = free.find((x) => x.name === src.name) || free.find((x) => src.fileName && x.fileName === src.fileName);
    let byLength = false;
    if (!t) {
      t = free.find((x) => Math.abs((x.duration || 0) - (src.duration || -9)) < 0.3);
      byLength = !!t;
    }
    if (t) { used.add(t.id); pairs.push({ src, t, byLength }); }
  }
  const missing = (data.tracks || []).length - pairs.length;
  const list = pairs.map((p) => `<li>${esc(p.src.name)}${p.src.name !== p.t.name ? ` → <b>${esc(p.t.name)}</b>` : ''}${p.byLength ? ' <span class="sub">(nur gleiche Länge – bitte prüfen)</span>' : ''}${p.t.markers?.length ? ` <span class="sub">– ${p.t.markers.length} vorhandene Marker werden ersetzt</span>` : ''}</li>`).join('');
  const ok = await ask('Sicherung laden?',
    `<p>Aus der Sicherung vom ${data.exported ? new Date(data.exported).toLocaleDateString('de-DE') : '?'}:</p>
     ${pairs.length ? `<ul>${list}</ul>` : '<p>Kein Titel passt zu den geladenen Audiodateien.</p>'}
     ${missing ? `<p class="sub">${missing} Titel der Sicherung fehlen hier – erst deren Audiodateien laden, dann die Sicherung nochmal laden.</p>` : ''}
     <p class="sub">Gruppen und Farben mit Bezeichnung werden ergänzt.</p>`,
    pairs.length ? 'Übernehmen' : 'Nur Gruppen übernehmen');
  if (!ok) return;
  for (const g of data.groups || []) {
    const have = group(g.id);
    if (have) Object.assign(have, g);
    else state.groups.push(g);
  }
  for (const l of data.colorLabels || []) {
    const have = state.colorLabels.find((x) => x.id === l.id);
    if (have) Object.assign(have, l);
    else state.colorLabels.push(l);
  }
  const order = [];
  for (const { src, t } of pairs) {
    snapshot(t, 'Sicherung geladen');
    order.push(t.id);
    t.name = src.name || t.name;
    t.artist = src.artist ?? t.artist;
    t.groupId = src.groupId || '';
    t.cueIn = src.cueIn || 0;
    t.cueOut = src.cueOut ?? null;
    if (src.segments) t.segments = src.segments.map((x) => ({ ...x, id: uid() }));
    if (src.beats) { t.beats = src.beats; t.ones = src.ones || [0]; }
    else if (t.segments) migrateTrack(t, src);
    if (src.color !== undefined) t.color = src.color;
    if (src.labelId !== undefined) t.labelId = src.labelId;
    t.markers = (src.markers || []).map((m) => ({ ...m, id: uid() }));
  }
  const pos = (t) => { const i = order.indexOf(t.id); return i < 0 ? order.length : i; };
  state.tracks = state.tracks.map((t, i) => [t, i]).sort((a, b) => pos(a[0]) - pos(b[0]) || a[1] - b[1]).map((x) => x[0]);
  save();
  renderAll();
  toast(`${pairs.length} Titel übernommen${missing ? ` – ${missing} fehlen noch` : ''}`, 5000);
}

/* ---------- Events ---------- */
$('fileInput').onchange = (e) => { addFiles(e.target.files); e.target.value = ''; };
$('emptyFileInput').onchange = (e) => { addFiles(e.target.files); e.target.value = ''; };
$('importInput').onchange = (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) { $('settingsDlg').close(); importSet(f); } };
$('exportBtn').onclick = () => { $('settingsDlg').close(); exportSet(); };
$('helpBtn').onclick = () => $('helpDlg').showModal();
$('settingsBtn').onclick = openSettings;
$('addGroupBtn').onclick = () => openGroupDialog(null);
// Titel oben antippen = anderen Titel wählen
const isPhone = () => matchMedia('(max-width: 1000px)').matches;
function openLibrary() {
  if (!isPhone()) { $('search').focus(); return; }
  document.body.classList.add('lib-open');
  history.pushState({ lib: true }, '');
  document.querySelector('.track-list li.playing')?.scrollIntoView({ block: 'center' });
}
function closeLibrary(fromHistory = false) {
  if (!document.body.classList.contains('lib-open')) return;
  document.body.classList.remove('lib-open');
  if (!fromHistory && history.state?.lib) history.back();
}
window.addEventListener('popstate', () => closeLibrary(true));
$('nowTitleBtn').onclick = openLibrary;
$('libClose').onclick = () => closeLibrary();
$('libFileInput').onchange = (e) => { addFiles(e.target.files); e.target.value = ''; };
$('editTrackBtn').onclick = () => current() ? openTrackDialog(current()) : toast('Erst einen Titel wählen');
$('miniPlay').onclick = toggle;
$('miniStop').onclick = stop;
$('miniReplay').onclick = replay;
$('miniInfo').onclick = () => $('playBtn').scrollIntoView({ behavior: 'smooth', block: 'center' });
// Mini-Steuerung zeigen, sobald die großen Knöpfe aus dem Bild sind
if ('IntersectionObserver' in window) {
  new IntersectionObserver(([en]) => {
    const show = !en.isIntersecting && !!current() && matchMedia('(max-width: 1000px)').matches;
    $('miniBar').hidden = !show;
    document.body.classList.toggle('has-mini', show);
  }).observe(document.querySelector('.transport'));
}

$('playBtn').onclick = toggle;
$('stopBtn').onclick = stop;
$('replayBtn').onclick = replay;
$('replayMarkerBtn').onclick = replayFromMarker;
$('backBtn').onclick = () => jump(-1);
$('fwdBtn').onclick = () => jump(1);
$('fadeBtn').onclick = () => fadeOut();
$('addMarkerBtn').onclick = quickMarker;
$('quickMarkerBtn').onclick = quickMarker;
$('cueInBtn').onclick = () => setCue('in');
$('cueOutBtn').onclick = () => setCue('out');
$('cueResetBtn').onclick = () => setCue('reset');
$('zoomIn').onclick = () => setZoom(-1);
$('zoomOut').onclick = () => setZoom(1);

$('bpmInput').onchange = (e) => setTempo(+e.target.value || null);
$('bpmHalf').onclick = () => scaleTempo(0.5);
$('bpmDouble').onclick = () => scaleTempo(2);
$('tapBtn').onclick = tapTempo;
$('detectBtn').onclick = redetect;
$('detectSongsBtn').onclick = detectSongs;
$('splitBtn').onclick = splitHere;
$('setOneBtn').onclick = () => setOne();
$('nudgeBack').onclick = () => nudge(-0.01);
$('nudgeFwd').onclick = () => nudge(0.01);

function setCountMode(v) {
  state.settings.countMode = v;
  save();
  if (v === 'voice') loadCountSounds().catch((e) => toast(e.message || 'Stimme nicht verfügbar', 3000));
  if (v !== 'off') ensureGraph();
  resetCountSchedule();
  renderTempo();
}
$('setKeepAlive').onchange = (e) => { state.settings.keepAlive = e.target.checked; save(); keepAliveTouch(); checkWarnings(); };
$('setKeepLevel').onchange = (e) => { state.settings.keepLevel = e.target.value; save(); updateKeepAlive(); };
$('setScreenOn').onchange = (e) => { state.settings.screenOn = e.target.checked; save(); updateScreenOn(); };
$('batteryBtn').onclick = async () => {
  try { await native('openBatterySettings'); } catch (e) { toast('Einstellungen → Apps → Narrhalla Player → Akku → „Nicht eingeschränkt“', 6000); }
};
// Browser erlauben Ton erst nach der ersten Berührung
const firstTouch = () => {
  window.removeEventListener('pointerdown', firstTouch, true);
  window.removeEventListener('keydown', firstTouch, true);
  ctx();
};
window.addEventListener('pointerdown', firstTouch, true);
window.addEventListener('keydown', firstTouch, true);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return;
  if (audioCtx?.state === 'suspended') audioCtx.resume();
  if (keepActive() && keepEl?.paused) keepEl.play().catch(() => {});
  if (state.settings.screenOn) updateScreenOn();
  checkWarnings();
});
window.addEventListener('pagehide', saveNow);
// Android-App: Tasten in der Benachrichtigung, am Sperrbildschirm und an der Bluetooth-Box
function onRemoteAction(a) {
  if (a === 'play') play();
  else if (a === 'pause') pause();
  else if (a === 'stop') stop();
  else if (a === 'next') step(1);
  else if (a === 'replay') replay();
}
if (isNative()) {
  try { window.Capacitor.nativeCallback('KeepAlive', 'addListener', { eventName: 'action' }, (r) => r?.action && onRemoteAction(r.action)); } catch (e) { console.warn(e); }
}
if ('mediaSession' in navigator) {
  const h = { play: () => play(), pause: () => pause(), stop: () => stop(), nexttrack: () => step(1), previoustrack: () => step(-1) };
  for (const [k, fn] of Object.entries(h)) { try { navigator.mediaSession.setActionHandler(k, fn); } catch (e) { /* nicht unterstützt */ } }
}
$('setVoice').onchange = (e) => setVoice(e.target.value);
$('countVol').oninput = (e) => { state.settings.countVolume = +e.target.value; save(); };
$('testVoiceBtn').onclick = testVoice;
$('recordVoiceBtn').onclick = () => { $('recordStatus').textContent = 'Bereit'; $('recordDlg').showModal(); };
$('recordStart').onclick = recordVoice;
$('addLabelBtn').onclick = () => {
  state.colorLabels.push({ id: 'l-' + uid(), name: '', color: PALETTE[(state.colorLabels.length * 3) % PALETTE.length] });
  save();
  renderLabelList();
  $('labelList').querySelector('li:last-child input')?.focus();
};
$('countModeSeg').onclick = (e) => { if (e.target.dataset.v) setCountMode(e.target.dataset.v); };
$('snapSeg').onclick = (e) => {
  if (!e.target.dataset.v) return;
  state.settings.snap = e.target.dataset.v;
  save();
  renderTempo();
};

function toggleLoop() {
  loop = !loop;
  toast(loop ? 'Schleife an: der Titel wiederholt sich' : 'Schleife aus');
}

$('volume').oninput = (e) => {
  state.volume = +e.target.value;
  if (!fading) setVol(state.volume);
  save();
};
$('search').oninput = renderTracks;

const tracksEl = document.querySelector('.tracks');
window.addEventListener('dragover', (e) => {
  if (e.dataTransfer?.types.includes('Files')) { e.preventDefault(); tracksEl.classList.add('dragover'); }
});
window.addEventListener('dragleave', () => tracksEl.classList.remove('dragover'));
window.addEventListener('drop', (e) => {
  tracksEl.classList.remove('dragover');
  if (e.dataTransfer?.files.length) { e.preventDefault(); addFiles(e.dataTransfer.files); }
});

window.addEventListener('resize', draw);

window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.target.matches('input[type=text], input[type=search], textarea')) { e.preventDefault(); return undo(); }
  if (e.target.matches('input:not([type=range]):not([type=checkbox]), select, textarea') || e.ctrlKey || e.metaKey || e.altKey) return;
  if (document.querySelector('dialog[open]')) return;
  const k = e.key.toLowerCase();
  const handled = () => e.preventDefault();
  if (e.code === 'Space') { toggle(); handled(); }
  else if (k === 'escape') stop();
  else if (k === 'r') e.shiftKey ? replayFromMarker() : replay();
  else if (k === 'l') replayLast();
  else if (k === 'm') addMarker();
  else if (k === 'f') fadeOut();
  else if (k === 'o') e.shiftKey ? setCue('out') : toggleLoop();
  else if (k === 'i') setCue('in');
  else if (k === 't') tapTempo();
  else if (k === 'z') setOne();
  else if (k === 'c') {
    const modes = ['off', 'voice', 'click'];
    setCountMode(modes[(modes.indexOf(state.settings.countMode) + 1) % 3]);
    toast('Mitzählen: ' + { off: 'aus', voice: 'Stimme', click: 'Klick' }[state.settings.countMode]);
  }
  else if (k === 'arrowleft') { jump(-1); handled(); }
  else if (k === 'arrowright') { jump(1); handled(); }
  else if (k === 'arrowup' || k === 'arrowdown') {
    const list = visibleTracks();
    const i = list.findIndex((t) => t.id === selectedId);
    const n = list[clamp(i + (k === 'arrowup' ? -1 : 1), 0, list.length - 1)];
    if (n) { selectedId = n.id; renderTracks(); document.querySelector('.track-list li.selected')?.scrollIntoView({ block: 'nearest' }); }
    handled();
  }
  else if (k === 'enter' && selectedId) load(selectedId, true);
  else if (/^[1-9]$/.test(e.key)) cueMarker(current()?.markers[+e.key - 1]);
});

/* ---------- Start ---------- */
function migrate() {
  // alte Beispielgruppen raus (nur wenn leer), neue Standardgruppen Show / Garde / Elfer rein
  if ((state.groupsVersion || 1) < 2) {
    state.groups = state.groups.filter((g) => !OLD_EXAMPLE_GROUPS.includes(g.id) || state.tracks.some((t) => t.groupId === g.id));
    for (const d of DEFAULT_GROUPS) if (!state.groups.some((g) => g.id === d.id)) state.groups.push({ ...d });
    state.groupsVersion = 2;
  }
  // Gruppen v3: Beispielgruppen (auch belegte) raus, Show / Garde / Elfer
  if ((state.groupsVersion || 1) < 3) {
    for (const t of state.tracks) if (OLD_EXAMPLE_GROUPS.includes(t.groupId) && t.groupId !== 'g-garde') t.groupId = '';
    state.groups = state.groups.filter((g) => !OLD_EXAMPLE_GROUPS.includes(g.id) || g.id === 'g-garde');
    const garde = state.groups.find((g) => g.id === 'g-garde');
    if (garde && /tanz/i.test(garde.name)) garde.name = 'Garde';
    for (const d of DEFAULT_GROUPS) if (!state.groups.some((g) => g.id === d.id)) state.groups.push({ ...d });
    state.groupsVersion = 3;
  }
  // Titel aus älteren Versionen: Tempo-Raster in einzelne Schläge umwandeln
  for (const t of state.tracks) {
    if (!t.segments && t.bpm !== undefined) {
      t.segments = [{ id: uid(), start: 0, bpm: t.bpm, beatOffset: t.beatOffset || 0, name: '', color: null }];
    }
    if (t.segments && !t.beats) migrateTrack(t);
    delete t.bpm;
    delete t.beatOffset;
  }
  save();
}

function migrateTrack(t, src = t) {
  const segs = src.segments || t.segments;
  if (!segs?.some((x) => x.bpm)) return;
  t.beats = [];
  t.ones = [];
  segs.forEach((sg, i) => {
    const end = i + 1 < segs.length ? segs[i + 1].start : t.duration;
    if (!sg.bpm) return;
    const g = gridBeats(sg.start, end, sg.bpm, sg.beatOffset ?? sg.start);
    const off = t.beats.length;
    t.beats.push(...g.beats.map((x) => Math.round(x * 1000) / 1000));
    t.ones.push(...g.ones.map((k) => off + k));
  });
  t.segments.forEach((sg) => { delete sg.bpm; delete sg.beatOffset; });
}

(function init() {
  try {
    const saved = JSON.parse(localStorage.getItem(STATE_KEY) || 'null');
    if (saved) Object.assign(state, { groupsVersion: 1 }, saved);
  } catch (e) {
    console.warn('Gespeicherter Stand unlesbar', e);
  }
  state.settings = { ...DEFAULT_SETTINGS, ...(state.settings || {}) };
  migrate();
  if (!ZOOMS.includes(state.settings.zoom)) state.settings.zoom = 8;
  $('volume').value = state.volume;
  state.autoNext = false; // nie versehentlich vom letzten Mal übernehmen
  audio.volume = state.volume;
  $('zoom').parentElement.classList.toggle('locked', !!state.settings.waveLock);
  document.getElementById('keyHelp').hidden = isTouch;
  state.colorLabels ??= [];
  if (!VOICES.some((v) => v.id === state.settings.voice)) state.settings.voice = 'de-frau';
  renderVoiceSelects();
  if (state.settings.countMode === 'voice') loadCountSounds().catch(() => {});
  renderAll();
  if (state.tracks.length) load(state.tracks[0].id, false);
  requestAnimationFrame(frame);
  if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !isNative()) {
    navigator.serviceWorker.register('sw.js').then((reg) => {
      // neue Version bereit → Hinweis
      reg.addEventListener('updatefound', () => {
        const nw = reg.installing;
        nw?.addEventListener('statechange', () => {
          if (nw.state === 'installed' && navigator.serviceWorker.controller) {
            showBanner('update', 'Eine neue Version der App ist da.', 'Jetzt neu laden', () => location.reload(), 'info');
          }
        });
      });
    }).catch((e) => console.warn('Service Worker', e));
  }
  (navigator.storage?.persist?.() || Promise.resolve()).finally(() => checkWarnings());
})();
