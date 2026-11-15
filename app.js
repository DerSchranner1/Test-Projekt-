/* Narrhalla Player
 * Reine Browser-App ohne Build-Schritt; läuft auch in der Android-Hülle (Capacitor).
 * Audiodateien und Wellenformen liegen in IndexedDB, Setliste und Einstellungen in localStorage.
 */
'use strict';

const $ = (id) => document.getElementById(id);
const STATE_KEY = 'narrhalla.state.v1';
const PEAK_COUNT = 600;   // Punkte der Übersichts-Wellenform
const HI_RATE = 100;      // Punkte pro Sekunde der Detail-Wellenform
const PALETTE = [
  '#e5484d', '#ff7a1a', '#ffd23f', '#a3e635', '#3ddc97', '#14b8a6', '#4cc9f0', '#3b82f6',
  '#6366f1', '#b388ff', '#f72585', '#ec4899', '#a16207', '#9ca3af', '#f5f5f5', '#64748b',
];
const MARKER_COLORS = ['#ffd23f', '#3ddc97', '#4cc9f0', '#f72585', '#ff7a1a', '#b388ff'];
const ZOOMS = [2, 4, 8, 16, 32];

const DEFAULT_GROUPS = [
  { id: 'g-einmarsch', name: 'Einmarsch', color: '#e5484d' },
  { id: 'g-garde', name: 'Garde / Tanz', color: '#4cc9f0' },
  { id: 'g-tusch', name: 'Tusch & Jingles', color: '#ffd23f' },
  { id: 'g-buett', name: 'Bütt', color: '#3ddc97' },
  { id: 'g-pause', name: 'Pause / Hintergrund', color: '#b388ff' },
];
const DEFAULT_SETTINGS = {
  countMode: 'off',     // off | voice | click
  countVolume: 0.8,
  countOffsetMs: 0,
  snap: 'beat',         // off | beat | eight
  scrubAudio: true,
  markerPlay: true,
  jumpEight: true,
  zoom: 8,
};

const state = {
  groups: DEFAULT_GROUPS.slice(),
  tracks: [],   // {id, name, artist, fileName, duration, groupId, markers[], cueIn, cueOut, peaks[], bpm, beatOffset}
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
const history = [];
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
function toast(msg, ms = 2000) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), ms);
}

let saveTimer;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      localStorage.setItem(STATE_KEY, JSON.stringify(state));
    } catch (e) {
      console.warn('Speichern fehlgeschlagen', e);
      toast('Speichern fehlgeschlagen – Speicher voll?');
    }
  }, 150);
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
function ensureGraph() {
  try {
    ctx();
    if (!gainNode) {
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

function makePeaks(mono, sr) {
  const peaks = [];
  const step = Math.max(1, Math.floor(mono.length / PEAK_COUNT));
  let max = 0;
  for (let i = 0; i < PEAK_COUNT; i++) {
    let p = 0;
    const end = Math.min(mono.length, (i + 1) * step);
    for (let j = i * step; j < end; j += 4) { const v = Math.abs(mono[j]); if (v > p) p = v; }
    peaks.push(p);
    if (p > max) max = p;
  }
  const hop = sr / HI_RATE;
  const n = Math.floor(mono.length / hop);
  const hi = new Uint8Array(n);
  let hmax = 0;
  const tmp = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let p = 0;
    const s = Math.floor(i * hop), e = Math.floor((i + 1) * hop);
    for (let j = s; j < e; j += 2) { const v = Math.abs(mono[j]); if (v > p) p = v; }
    tmp[i] = p;
    if (p > hmax) hmax = p;
  }
  for (let i = 0; i < n; i++) hi[i] = Math.round((hmax ? tmp[i] / hmax : 0) * 255);
  return { peaks: peaks.map((p) => Math.round((max ? p / max : 0) * 100) / 100), hi };
}

/* Tempo-Erkennung: Einsatzstärke (Bass + Gesamt) in 10-ms-Schritten,
 * Autokorrelation für das Tempo, danach Feinsuche über den ganzen Titel,
 * Phase des Schlags und welche der acht Zählzeiten die „1“ ist. */
function detectTempo(mono, sr) {
  const fps = 100, hop = sr / fps;
  const n = Math.floor(mono.length / hop);
  if (n < fps * 6) return null;
  const low = new Float32Array(n), full = new Float32Array(n);
  const a = 1 - Math.exp((-2 * Math.PI * 150) / sr);
  let lp = 0;
  for (let i = 0; i < n; i++) {
    let el = 0, ef = 0;
    const s = Math.floor(i * hop), e = Math.floor((i + 1) * hop);
    for (let j = s; j < e; j++) { const x = mono[j]; lp += a * (x - lp); el += lp * lp; ef += x * x; }
    low[i] = Math.log1p(1000 * Math.sqrt(el / (e - s)));
    full[i] = Math.log1p(1000 * Math.sqrt(ef / (e - s)));
  }
  const onL = new Float32Array(n), on = new Float32Array(n);
  for (let i = 1; i < n; i++) {
    onL[i] = Math.max(0, low[i] - low[i - 1]);
    on[i] = 1.5 * onL[i] + Math.max(0, full[i] - full[i - 1]);
  }
  // gleitenden Mittelwert abziehen, damit nur echte Einsätze zählen
  const W = 25;
  let acc = 0;
  const flt = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    acc += on[i];
    if (i >= 2 * W) acc -= on[i - 2 * W];
    const c = i - W;
    if (c >= 0) flt[c] = Math.max(0, on[c] - acc / (2 * W));
  }

  const score = (lag) => {
    let s = 0;
    for (let i = 0; i + lag < n; i++) s += flt[i] * flt[i + lag];
    return s / (n - lag);
  };
  let best = 0, bestBpm = 0;
  for (let bpm = 60; bpm <= 200; bpm += 0.5) {
    const lag = (60 * fps) / bpm;
    const l0 = Math.floor(lag), f = lag - l0;
    const r = (1 - f) * score(l0) + f * score(l0 + 1);
    const r2l = Math.round(lag * 2) < n / 2 ? score(Math.round(lag * 2)) : 0;
    const prior = Math.exp(-0.5 * (Math.log2(bpm / 120) / 0.55) ** 2);
    const s = (r + 0.5 * r2l) * prior;
    if (s > best) { best = s; bestBpm = bpm; }
  }
  if (!bestBpm) return null;

  // Feinsuche: welches Tempo trifft über den ganzen Titel die meisten Einsätze?
  const at = (x) => { const i = Math.floor(x); const f = x - i; return i + 1 < n ? flt[i] * (1 - f) + flt[i + 1] * f : 0; };
  const phaseFit = (bpm) => {
    const P = (60 * fps) / bpm;
    let bs = -1, bp = 0;
    for (let ph = 0; ph < P; ph += 0.5) {
      let s = 0;
      for (let x = ph; x < n; x += P) s += at(x);
      if (s > bs) { bs = s; bp = ph; }
    }
    return { s: bs, ph: bp };
  };
  let fine = { bpm: bestBpm, ...phaseFit(bestBpm) };
  for (let bpm = bestBpm - 1.5; bpm <= bestBpm + 1.5; bpm += 0.05) {
    const r = phaseFit(bpm);
    if (r.s > fine.s) fine = { bpm, ...r };
  }
  let bpm = fine.bpm;
  if (Math.abs(bpm - Math.round(bpm)) < 0.12) bpm = Math.round(bpm);
  else bpm = Math.round(bpm * 10) / 10;
  const P = (60 * fps) / bpm;
  const ph = phaseFit(bpm).ph;

  // welche der acht Zählzeiten trägt den stärksten Bass-Einsatz? Das wird die „1“.
  let bj = 0, bjs = -1;
  for (let j = 0; j < 8; j++) {
    let s = 0;
    for (let x = ph + j * P; x + 1 < n; x += 8 * P) {
      const i = Math.round(x);
      s += Math.max(onL[i], onL[i + 1] || 0, onL[i - 1] || 0);
    }
    if (s > bjs) { bjs = s; bj = j; }
  }
  return { bpm, beatOffset: Math.round(((ph + bj * P) / fps) * 1000) / 1000 };
}

async function decodeBlob(blob) {
  const data = await blob.arrayBuffer();
  return ctx().decodeAudioData(data);
}

async function analyseBlob(blob) {
  const buf = await decodeBlob(blob);
  const mono = mixMono(buf);
  const { peaks, hi } = makePeaks(mono, buf.sampleRate);
  const tempo = detectTempo(mono, buf.sampleRate);
  return { duration: buf.duration, peaks, hi, tempo };
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
    toast(`Lade „${file.name || 'Audio'}“ …`, 60000);
    try {
      const [info, tags] = await Promise.all([analyseBlob(file), readTags(file)]);
      const id = uid();
      await putFile(id, file);
      await putHi(id, info.hi);
      hiCache.set(id, info.hi);
      state.tracks.push({
        id,
        name: tags.title || nameFromFile(file.name) || `Titel ${state.tracks.length + 1}`,
        artist: tags.artist || '',
        fileName: file.name || '',
        duration: info.duration,
        peaks: info.peaks,
        groupId: filterGroup || '',
        markers: [],
        cueIn: 0,
        cueOut: null,
        bpm: info.tempo?.bpm ?? null,
        beatOffset: info.tempo?.beatOffset ?? 0,
      });
      ok++;
      save();
      renderAll();
    } catch (e) {
      console.error(e);
      toast(`„${file.name}“ ist keine lesbare Audiodatei`);
      await new Promise((r) => setTimeout(r, 1500));
    }
  }
  if (ok) toast(`${ok} Titel geladen – Tempo erkannt, bitte kurz prüfen`);
  if (!currentId && state.tracks.length) load(state.tracks[0].id, false);
}

/* ---------- Zählzeiten ---------- */
const beatLen = (t) => (t?.bpm ? 60 / t.bpm : 0);
function beatIndex(t, time) {
  const b = beatLen(t);
  return b ? Math.floor((time - (t.beatOffset || 0)) / b + 1e-6) : null;
}
const beatTime = (t, idx) => (t.beatOffset || 0) + idx * beatLen(t);

function snap(time, t = current(), mode = state.settings.snap) {
  if (!t || mode === 'off' || !t.bpm) return time;
  const unit = beatLen(t) * (mode === 'eight' ? 8 : 1);
  let k = Math.round((time - (t.beatOffset || 0)) / unit);
  let s = (t.beatOffset || 0) + k * unit;
  if (s < 0) s += unit;
  return clamp(s, 0, t.duration);
}

/* ---------- Playback ---------- */
const cueIn = (t) => t?.cueIn || 0;
const cueOut = (t) => (t && t.cueOut != null ? t.cueOut : t?.duration ?? 0);

async function load(id, autoplay = true, at = null) {
  const t = track(id);
  if (!t) return;
  cancelFade();
  if (id !== currentId) {
    const url = await urlFor(id);
    if (!url) return toast('Audiodatei fehlt – bitte neu laden');
    if (currentId) lastPlayedId = currentId;
    currentId = id;
    selectedId = id;
    scrubBuf = null;
    audio.src = url;
    await new Promise((res) => {
      audio.addEventListener('loadedmetadata', res, { once: true });
      audio.addEventListener('error', res, { once: true });
    });
    ensureDetail(t);
  }
  audio.currentTime = at ?? cueIn(t);
  resetCountSchedule();
  renderAll();
  if (autoplay) play();
}

// Detail-Wellenform (und bei alten Titeln das Tempo) bei Bedarf nachberechnen
async function ensureDetail(t) {
  if (hiCache.has(t.id)) return;
  let hi = await getHi(t.id).catch(() => null);
  if (!hi || t.bpm === undefined) {
    try {
      toast('Analysiere Wellenform und Takt …', 30000);
      const info = await analyseBlob(await getFile(t.id));
      hi = info.hi;
      await putHi(t.id, hi);
      if (t.bpm === undefined) {
        t.bpm = info.tempo?.bpm ?? null;
        t.beatOffset = info.tempo?.beatOffset ?? 0;
        save();
      }
      toast('Analyse fertig');
    } catch (e) {
      console.warn(e);
      toast('Analyse fehlgeschlagen');
      return;
    }
  }
  hiCache.set(t.id, hi);
  renderAll();
}

function play() {
  const t = current();
  if (!t) {
    const first = visibleTracks()[0];
    if (first) load(selectedId || first.id);
    return;
  }
  cancelFade();
  if (audio.currentTime >= cueOut(t) - 0.05) audio.currentTime = cueIn(t);
  ensureGraph();
  setVol(state.volume);
  resetCountSchedule();
  audio.play().catch((e) => toast('Wiedergabe blockiert: ' + e.message));
  if (history[0]?.id !== t.id) {
    history.unshift({ id: t.id, at: new Date() });
    history.length = Math.min(history.length, 8);
    renderHistory();
  }
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
  resetCountSchedule();
  draw();
}

function jump(dir) {
  const t = current();
  if (!t) return;
  if (state.settings.jumpEight && t.bpm) {
    const idx = beatIndex(t, audio.currentTime + 0.05);
    const startOfEight = Math.floor(idx / 8) * 8;
    // zurück: an den Anfang des aktuellen Achters, wenn wir schon drin sind, sonst einen Achter zurück
    let target = dir < 0
      ? (idx - startOfEight >= 1 ? startOfEight : startOfEight - 8)
      : startOfEight + 8;
    seek(Math.max(0, beatTime(t, target)));
  } else {
    seek(audio.currentTime + dir * 5);
  }
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

function fadeOut(ms = 2500) {
  if (audio.paused || fading) return;
  const start = performance.now();
  const from = state.volume;
  const tick = (now) => {
    const k = Math.min(1, (now - start) / ms);
    setVol(from * (1 - k));
    if (k < 1) fading = requestAnimationFrame(tick);
    else { fading = null; audio.pause(); setVol(state.volume); }
  };
  fading = requestAnimationFrame(tick);
}
function cancelFade() {
  if (fading) cancelAnimationFrame(fading);
  fading = null;
  setVol(state.volume);
}

audio.addEventListener('ended', onEnded);
audio.addEventListener('play', syncPlayBtn);
audio.addEventListener('pause', syncPlayBtn);
audio.addEventListener('seeked', resetCountSchedule);

function syncPlayBtn() {
  $('playBtn').textContent = audio.paused ? '▶' : '⏸';
  renderTracks();
  draw();
}

/* ---------- Mitzählen (Stimme / Klick) ---------- */
let countBuffers = null;
let lastScheduledBeat = null;

async function loadCountSounds() {
  if (countBuffers) return countBuffers;
  countBuffers = await Promise.all((window.COUNT_SOUNDS || []).map(async (uri) => {
    const bin = atob(uri.split(',')[1]);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return ctx().decodeAudioData(bytes.buffer);
  }));
  return countBuffers;
}

function resetCountSchedule() {
  const t = current();
  if (!t?.bpm) { lastScheduledBeat = null; return; }
  const idx = beatIndex(t, audio.currentTime);
  // Steht die Wiedergabe genau auf einem Schlag, soll dieser noch gezählt werden
  lastScheduledBeat = audio.currentTime - beatTime(t, idx) < 0.03 ? idx - 1 : idx;
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

function scheduler() {
  const t = current();
  if (!t || audio.paused) return;
  if (audio.currentTime >= cueOut(t) && t.cueOut != null) onEnded();
  if (state.settings.countMode === 'off' || !t.bpm || !audioCtx || scrub) return;
  const now = audio.currentTime;
  const rate = audio.playbackRate || 1;
  const next = (lastScheduledBeat ?? beatIndex(t, now)) + 1;
  const tb = beatTime(t, next);
  const ahead = (tb - now) / rate;
  if (ahead < 0.15) {
    lastScheduledBeat = next;
    if (ahead > -0.03 && tb < cueOut(t)) {
      const when = audioCtx.currentTime + Math.max(0, ahead + state.settings.countOffsetMs / 1000);
      playCount(mod(next, 8) + 1, when);
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
    scrubBuf = await decodeBlob(await getFile(t.id));
  } catch (e) {
    console.warn(e);
    scrubBufFor = null;
  }
}

function scrubStart() {
  const t = current();
  if (!t) return false;
  scrub = { pos: audio.currentTime, wasPlaying: !audio.paused, lastPos: audio.currentTime, lastAt: performance.now() };
  if (scrub.wasPlaying) audio.pause();
  if (state.settings.scrubAudio) { ensureGraph(); ensureScrubBuffer(); }
  return true;
}

function scrubTo(pos) {
  const t = current();
  if (!scrub || !t) return;
  scrub.pos = clamp(pos, 0, t.duration - 0.01);
  const now = performance.now();
  if (state.settings.scrubAudio && scrubBuf && scrubBufFor === t.id && now - lastGrain > 35) {
    const speed = Math.abs(scrub.pos - scrub.lastPos) / Math.max(0.001, (now - scrub.lastAt) / 1000);
    lastGrain = now;
    scrub.lastPos = scrub.pos;
    scrub.lastAt = now;
    if (speed > 0.05) grain(scrub.pos, clamp(speed, 0.4, 2.5));
  }
  draw();
}

function grain(pos, rate) {
  const c = ctx();
  const src = c.createBufferSource();
  src.buffer = scrubBuf;
  src.playbackRate.value = rate;
  const e = c.createGain();
  const now = c.currentTime;
  e.gain.setValueAtTime(0, now);
  e.gain.linearRampToValueAtTime(state.volume, now + 0.006);
  e.gain.setValueAtTime(state.volume, now + 0.06);
  e.gain.linearRampToValueAtTime(0, now + 0.08);
  src.connect(e).connect(c.destination);
  src.start(now, pos, 0.09);
}

function scrubEnd(doSnap = true) {
  if (!scrub) return;
  const { pos, wasPlaying } = scrub;
  scrub = null;
  seek(doSnap ? snap(pos) : pos);
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
  t.markers.push(m);
  t.markers.sort((a, b) => a.time - b.time);
  save();
  renderMarkers();
  renderTracks();
  draw();
  const idx = beatIndex(t, at + 0.001);
  toast(`Marker bei ${fmt(m.time, true)}${t.bpm ? ` (Zählzeit ${mod(idx, 8) + 1})` : ''}`);
}

function removeMarker(id) {
  const t = current();
  if (!t) return;
  t.markers = t.markers.filter((m) => m.id !== id);
  save();
  renderMarkers();
  renderTracks();
  draw();
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
  if (which === 'in') {
    if (now >= cueOut(t)) return toast('Cue-In muss vor Cue-Out liegen');
    t.cueIn = now;
  } else if (which === 'out') {
    if (now <= cueIn(t)) return toast('Cue-Out muss nach Cue-In liegen');
    t.cueOut = now;
  } else {
    t.cueIn = 0;
    t.cueOut = null;
  }
  save();
  renderCues();
  renderTracks();
  draw();
}

/* ---------- Tempo bearbeiten ---------- */
function setTempo(bpm, offset) {
  const t = current();
  if (!t) return;
  t.bpm = bpm ? Math.round(clamp(bpm, 40, 240) * 100) / 100 : null;
  if (offset != null) t.beatOffset = offset;
  save();
  resetCountSchedule();
  renderTempo();
  draw();
}

let taps = [];
function tapTempo() {
  const t = current();
  if (!t) return;
  const now = performance.now();
  if (taps.length && now - taps[taps.length - 1] > 2000) taps = [];
  taps.push(now);
  if (taps.length > 8) taps.shift();
  if (taps.length >= 4) {
    const bpm = 60000 / ((taps[taps.length - 1] - taps[0]) / (taps.length - 1));
    setTempo(Math.round(bpm * 10) / 10);
    // Raster an den letzten Tipp legen, wenn die Musik läuft
    if (!audio.paused) setOne(audio.currentTime, false);
  } else {
    toast(`Weiter im Takt tippen … (${taps.length}/4)`, 1200);
  }
}

function setOne(at = viewTime(), msg = true) {
  const t = current();
  if (!t) return;
  if (!t.bpm) return toast('Erst Tempo erkennen oder antippen');
  setTempo(t.bpm, Math.round(at * 1000) / 1000);
  if (msg) toast('Zählzeit gesetzt: hier ist die 1');
}

async function redetect() {
  const t = current();
  if (!t) return;
  toast('Erkenne Tempo …', 30000);
  try {
    const buf = await decodeBlob(await getFile(t.id));
    const tempo = detectTempo(mixMono(buf), buf.sampleRate);
    if (!tempo) return toast('Kein Takt erkannt – bitte Tap benutzen');
    setTempo(tempo.bpm, tempo.beatOffset);
    toast(`${tempo.bpm} BPM erkannt – „Hier ist die 1“ zum Korrigieren`);
  } catch (e) {
    toast('Erkennung fehlgeschlagen');
  }
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

let dragId = null;
function renderTracks() {
  const ol = $('trackList');
  ol.replaceChildren();
  const list = visibleTracks();
  list.forEach((t, i) => {
    const g = group(t.groupId);
    const li = el('li', { draggable: true });
    li.dataset.id = t.id;
    if (t.id === selectedId) li.classList.add('selected');
    if (t.id === currentId) li.classList.add('playing');

    const stripe = el('span', { className: 'stripe' });
    stripe.style.background = g?.color || '#4a4a58';
    const title = el('span', { className: 'title' },
      el('span', { className: 't1', textContent: (t.id === currentId && !audio.paused ? '▶ ' : '') + t.name }),
      el('span', { className: 't2', textContent: [t.artist, g?.name, t.bpm ? `${t.bpm} BPM` : null, t.markers.length ? `◆${t.markers.length}` : null].filter(Boolean).join(' · ') }));
    title.title = t.name;

    const playB = el('button', { className: 'row-btn', textContent: '▶', title: 'Abspielen' });
    playB.onclick = (e) => { e.stopPropagation(); load(t.id, true); };
    const editB = el('button', { className: 'row-btn', textContent: '✎', title: 'Titel, Gruppe bearbeiten' });
    editB.onclick = (e) => { e.stopPropagation(); openTrackDialog(t); };

    li.append(stripe, el('span', { className: 'num', textContent: i + 1 }), title,
      el('span', { className: 'meta', textContent: fmt(cueOut(t) - cueIn(t)) }), playB, editB);

    li.onclick = () => { selectedId = t.id; renderTracks(); };
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
  $('dropHint').style.display = state.tracks.length ? 'none' : '';
}

async function removeTrack(id) {
  const t = track(id);
  if (!t || !confirm(`„${t.name}“ entfernen?`)) return false;
  if (id === currentId) { audio.pause(); audio.removeAttribute('src'); currentId = null; }
  state.tracks = state.tracks.filter((x) => x.id !== id);
  if (urlCache.has(id)) { URL.revokeObjectURL(urlCache.get(id)); urlCache.delete(id); }
  hiCache.delete(id);
  await delFile(id);
  save();
  renderAll();
  return true;
}

function renderNow() {
  const t = current();
  const g = group(t?.groupId);
  $('nowTitle').textContent = t ? t.name : 'Kein Titel geladen';
  $('nowSub').textContent = t ? [t.artist, g?.name].filter(Boolean).join(' · ') || '–' : '–';
  $('nowColor').style.background = g?.color || '#4a4a58';
  $('timeDur').textContent = fmt(t?.duration || 0);
  $('backBtn').textContent = state.settings.jumpEight && t?.bpm ? '−8' : '-5s';
  $('fwdBtn').textContent = state.settings.jumpEight && t?.bpm ? '+8' : '+5s';
}

function renderCues() {
  const t = current();
  $('cueInVal').textContent = fmt(cueIn(t), true);
  $('cueOutVal').textContent = t?.cueOut != null ? fmt(t.cueOut, true) : 'Ende';
}

function renderTempo() {
  const t = current();
  const inp = $('bpmInput');
  if (document.activeElement !== inp) inp.value = t?.bpm ?? '';
  $('countBox').classList.toggle('off', !t?.bpm);
  for (const seg of ['countModeSeg', 'snapSeg']) {
    const key = seg === 'countModeSeg' ? 'countMode' : 'snap';
    for (const b of $(seg).children) b.classList.toggle('on', b.dataset.v === state.settings[key]);
  }
  renderNow();
}

function renderMarkers() {
  const ul = $('markerList');
  ul.replaceChildren();
  const t = current();
  if (!t || !t.markers.length) {
    ul.append(el('li', { className: 'empty', textContent: 'Noch keine Marker – z. B. „Einsatz Garde“, „Hebefigur“, „Tusch“.' }));
    return;
  }
  t.markers.forEach((m, i) => {
    const idx = beatIndex(t, m.time + 0.001);
    const cue = el('button', { className: 'mcue', title: 'Ab hier abspielen' },
      el('span', { className: 'mn', textContent: i + 1 }),
      el('span', { textContent: fmt(m.time, true) }),
      t.bpm ? el('span', { className: 'mc', textContent: `·${mod(idx, 8) + 1}` }) : null);
    cue.style.background = m.color;
    cue.onclick = () => cueMarker(m);
    const label = el('input', { type: 'text', value: m.label, placeholder: 'Beschriftung…', maxLength: 40 });
    label.oninput = () => { m.label = label.value; save(); draw(); };
    const color = el('button', { className: 'mcolor', title: 'Farbe' });
    color.style.background = m.color;
    color.onclick = async () => {
      const c = await pickColor(m.color);
      if (c) { m.color = c; save(); renderMarkers(); draw(); }
    };
    const inB = el('button', { className: 'mmini', textContent: 'In', title: 'Als Cue-In (Start) setzen' });
    inB.onclick = () => setCue('in', m.time);
    const outB = el('button', { className: 'mmini', textContent: 'Out', title: 'Als Cue-Out (Ende) setzen' });
    outB.onclick = () => setCue('out', m.time);
    const del = el('button', { className: 'mmini del', textContent: '✕', title: 'Marker löschen' });
    del.onclick = () => removeMarker(m.id);
    ul.append(el('li', {}, cue, label, color, inB, outB, del));
  });
}

function renderHistory() {
  const ul = $('historyList');
  ul.replaceChildren();
  if (!history.length) return ul.append(el('li', { textContent: 'Noch nichts gespielt' }));
  for (const h of history) {
    const t = track(h.id);
    if (!t) continue;
    const li = el('li', {
      textContent: `${h.at.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}  ${t.name}`,
      title: 'Nochmal abspielen',
    });
    li.onclick = () => load(t.id, true);
    ul.append(li);
  }
}

function renderAll() {
  renderGroups();
  renderTracks();
  renderNow();
  renderCues();
  renderTempo();
  renderMarkers();
  renderHistory();
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

function pickColor(currentColor) {
  return new Promise((resolve) => {
    const dlg = $('colorDlg');
    swatches($('colorDlgSwatches'), currentColor, (c) => { dlg.close(); resolve(c); });
    dlg.onclose = () => resolve(null);
    dlg.showModal();
  });
}

function openGroupDialog(g) {
  const isNew = !g;
  const dlg = $('groupDlg');
  let color = g?.color || PALETTE[state.groups.length % PALETTE.length];
  $('groupDlgTitle').textContent = isNew ? 'Neue Gruppe' : 'Gruppe bearbeiten';
  $('groupDlgName').value = g?.name || '';
  $('groupDlgDelete').style.display = isNew ? 'none' : '';
  const paint = () => swatches($('groupDlgColors'), color, (c) => { color = c; paint(); });
  paint();
  $('groupDlgDelete').onclick = () => {
    if (!confirm(`Gruppe „${g.name}“ löschen? Die Titel bleiben erhalten.`)) return;
    state.groups = state.groups.filter((x) => x.id !== g.id);
    state.tracks.forEach((t) => { if (t.groupId === g.id) t.groupId = ''; });
    if (filterGroup === g.id) filterGroup = null;
    save();
    dlg.close();
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
  $('trackDlgName').value = t.name;
  $('trackDlgArtist').value = t.artist || '';
  $('trackDlgInfo').textContent = `Datei: ${t.fileName || '–'} · Länge ${fmt(t.duration)}${t.bpm ? ` · ${t.bpm} BPM` : ''}`;
  const paint = () => {
    const box = $('trackDlgGroups');
    box.replaceChildren();
    for (const g of [{ id: '', name: 'keine', color: '#4a4a58' }, ...state.groups]) {
      const b = el('button', { type: 'button', className: 'chip' + (g.id === groupId ? ' on' : ''), textContent: g.name });
      b.style.setProperty('--c', g.color);
      b.onclick = () => { groupId = g.id; paint(); };
      box.append(b);
    }
  };
  paint();
  $('trackDlgDelete').onclick = async () => { if (await removeTrack(t.id)) dlg.close(); };
  dlg.onclose = () => {
    if (dlg.returnValue !== 'save') return;
    t.name = $('trackDlgName').value.trim() || t.name;
    t.artist = $('trackDlgArtist').value.trim();
    t.groupId = groupId;
    save();
    renderAll();
  };
  dlg.returnValue = '';
  dlg.showModal();
}

function openSettings() {
  const s = state.settings;
  $('setScrubAudio').checked = s.scrubAudio;
  $('setMarkerPlay').checked = s.markerPlay;
  $('setJumpEight').checked = s.jumpEight;
  $('setCountVol').value = s.countVolume;
  $('setCountOffset').value = s.countOffsetMs;
  $('setCountOffsetVal').textContent = `${s.countOffsetMs} ms`;
  $('settingsDlg').showModal();
}
for (const [id, key, prop] of [
  ['setScrubAudio', 'scrubAudio', 'checked'], ['setMarkerPlay', 'markerPlay', 'checked'],
  ['setJumpEight', 'jumpEight', 'checked'], ['setCountVol', 'countVolume', 'value'],
  ['setCountOffset', 'countOffsetMs', 'value'],
]) {
  $(id).addEventListener('input', (e) => {
    state.settings[key] = prop === 'checked' ? e.target.checked : +e.target.value;
    $('setCountOffsetVal').textContent = `${state.settings.countOffsetMs} ms`;
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
function drawOverview() {
  const { g, w, h } = prepCanvas(wave);
  const t = current();
  const unplayed = css('--unplayed');
  if (!t) { g.fillStyle = unplayed; g.fillRect(0, h / 2, w, 1); return; }
  const played = group(t.groupId)?.color || css('--played');
  const dur = t.duration || 1;
  const x = (sec) => (sec / dur) * w;
  const cur = viewTime();

  const items = t.markers.map((m, i) => ({ x: Math.round(x(m.time)), text: markerText(m, i), color: m.color }));
  const lanes = t.markers.length ? layoutLabels(g, items, 3) : 0;
  const top = lanes * LANE_H + 2;
  waveLayout = { top, items };

  const barW = 2, gap = 1;
  const bars = Math.floor(w / (barW + gap));
  const mid = top + (h - top) * 0.66;
  const up = mid - top - 1, down = h - mid - 1;
  const px = x(cur), inX = x(cueIn(t)), outX = x(cueOut(t));
  for (let i = 0; i < bars; i++) {
    const bx = i * (barW + gap);
    const a = Math.floor((i / bars) * t.peaks.length);
    const b = Math.max(a + 1, Math.floor(((i + 1) / bars) * t.peaks.length));
    let p = 0;
    for (let j = a; j < b; j++) p = Math.max(p, t.peaks[j] || 0);
    p = Math.max(0.03, p);
    const outside = bx < inX || bx > outX;
    g.globalAlpha = outside ? 0.3 : 1;
    g.fillStyle = bx < px && !outside ? played : unplayed;
    g.fillRect(bx, mid - p * up, barW, p * up);
    g.globalAlpha *= 0.45;
    g.fillRect(bx, mid + 1, barW, p * down);
  }
  g.globalAlpha = 1;
  g.fillStyle = css('--accent-2');
  if (t.cueIn > 0) g.fillRect(inX, top, 1, h - top);
  if (t.cueOut != null) g.fillRect(outX - 1, top, 1, h - top);
  for (const it of items) {
    g.fillStyle = it.color;
    g.fillRect(it.x, top + it.lane * 0, 2, h - top);
    g.fillRect(it.x, it.lane * LANE_H, 2, top);
  }
  drawLabels(g, items, w, 0);
  g.fillStyle = '#fff';
  g.fillRect(px, top - 2, 1.5, h - top + 2);
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

  // Marker-Bahnen
  const vis = t.markers.map((m, i) => ({ m, i })).filter(({ m }) => m.time >= start - 2 && m.time <= start + span);
  const items = vis.map(({ m, i }) => ({ x: Math.round(X(m.time)), text: markerText(m, i), color: m.color, m }));
  const lanes = items.length ? layoutLabels(g, items, 2) : 0;
  const top = COUNT_H + lanes * LANE_H;
  zoomLayout = { start, pxPerSec, items, top, COUNT_H };

  // Bereich außerhalb Cue abdunkeln
  const played = group(t.groupId)?.color || css('--played');
  const unplayed = css('--unplayed');
  const mid = top + (h - top) / 2;
  const amp = (h - top) / 2 - 2;
  if (hi) {
    const barW = 2;
    for (let x = 0; x < w; x += barW + 1) {
      const sec = start + x / pxPerSec;
      if (sec < 0 || sec > t.duration) continue;
      const a = Math.floor(sec * HI_RATE), b = Math.max(a + 1, Math.floor((start + (x + barW + 1) / pxPerSec) * HI_RATE));
      let p = 0;
      for (let j = a; j < b && j < hi.length; j++) if (hi[j] > p) p = hi[j];
      const v = Math.max(0.02, p / 255) * amp;
      const outside = sec < cueIn(t) || sec > cueOut(t);
      g.globalAlpha = outside ? 0.3 : 1;
      g.fillStyle = sec < cur ? played : unplayed;
      g.fillRect(x, mid - v, barW, v * 2);
    }
    g.globalAlpha = 1;
  } else {
    g.fillStyle = css('--muted');
    g.font = '12px system-ui, sans-serif';
    g.fillText('Detailansicht wird berechnet …', 10, mid);
  }

  // Zählzeiten-Raster mit 1–8
  if (t.bpm) {
    const b = beatLen(t);
    const first = Math.ceil((start - t.beatOffset) / b);
    const last = Math.floor((start + span - t.beatOffset) / b);
    const step = pxPerSec * b < 14 ? 2 : 1; // bei kleinem Zoom nicht jede Zahl schreiben
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    for (let k = first; k <= last; k++) {
      const x = X(beatTime(t, k));
      const c = mod(k, 8) + 1;
      const one = c === 1;
      g.fillStyle = one ? 'rgba(255,210,63,.55)' : 'rgba(255,255,255,.12)';
      g.fillRect(Math.round(x), COUNT_H, one ? 2 : 1, h - COUNT_H);
      if (one || mod(k, step) === 0) {
        g.font = one ? '700 13px system-ui, sans-serif' : '600 11px system-ui, sans-serif';
        g.fillStyle = one ? css('--accent-2') : c === 5 ? '#ddd' : '#999';
        g.fillText(String(c), x, COUNT_H / 2);
      }
    }
    g.textAlign = 'left';
  } else {
    g.fillStyle = css('--muted');
    g.font = '11px system-ui, sans-serif';
    g.textBaseline = 'middle';
    g.fillText('Kein Takt – „Erkennen“ oder „Tap“ benutzen', 8, COUNT_H / 2);
  }

  // Cue-Grenzen
  g.fillStyle = css('--accent-2');
  if (t.cueIn > 0) g.fillRect(X(t.cueIn), top, 2, h - top);
  if (t.cueOut != null) g.fillRect(X(t.cueOut) - 2, top, 2, h - top);

  for (const it of items) {
    g.fillStyle = it.color;
    g.fillRect(it.x, COUNT_H, 2, h - COUNT_H);
  }
  drawLabels(g, items, w, COUNT_H);

  // feste Abspielposition in der Mitte
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
  if (!t?.bpm) {
    num.textContent = '–';
    eight.textContent = t ? 'kein Takt' : '';
    return;
  }
  const idx = beatIndex(t, viewTime());
  const c = mod(idx, 8) + 1;
  const e = Math.floor(idx / 8) + 1;
  const key = idx;
  if (key !== lastCountShown) {
    lastCountShown = key;
    num.textContent = c;
    eight.textContent = e >= 1 ? `Achter ${e}` : 'Vorlauf';
    const box = $('countBox');
    box.classList.toggle('one', c === 1);
    if (!audio.paused) { box.classList.remove('pulse'); void box.offsetWidth; box.classList.add('pulse'); }
  }
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

// Übersicht: tippen = springen, ziehen = schieben, doppelt tippen = Marker
let ov = null, lastTap = { at: 0, x: -99 };
wave.addEventListener('pointerdown', (e) => {
  const t = current();
  if (!t || e.button === 2) return;
  wave.setPointerCapture(e.pointerId);
  ov = { x0: e.clientX, moved: false };
});
wave.addEventListener('pointermove', (e) => {
  if (!ov) return;
  const t = current();
  const r = wave.getBoundingClientRect();
  if (!ov.moved && Math.abs(e.clientX - ov.x0) > 6) { ov.moved = true; scrubStart(); }
  if (ov.moved) scrubTo(clamp((e.clientX - r.left) / r.width, 0, 1) * t.duration);
});
wave.addEventListener('pointerup', (e) => {
  if (!ov) return;
  const t = current();
  const r = wave.getBoundingClientRect();
  const x = e.clientX - r.left;
  const y = e.clientY - r.top;
  if (ov.moved) { ov = null; return scrubEnd(); }
  ov = null;
  const now = performance.now();
  const dbl = now - lastTap.at < 350 && Math.abs(x - lastTap.x) < 30;
  lastTap = { at: now, x };
  const hit = hitMarker(waveLayout.items, x, y < waveLayout.top ? 30 : 10);
  if (dbl && !hit) { lastTap.at = 0; return addMarker((x / r.width) * t.duration); }
  if (hit) {
    const m = t.markers[waveLayout.items.indexOf(hit)];
    return cueMarker(m);
  }
  seek(snap((x / r.width) * t.duration));
});
wave.addEventListener('pointercancel', () => { if (ov?.moved) scrubEnd(false); ov = null; });
wave.addEventListener('contextmenu', (e) => {
  const r = wave.getBoundingClientRect();
  const hit = hitMarker(waveLayout.items, e.clientX - r.left, 10);
  if (hit) { e.preventDefault(); removeMarker(current().markers[waveLayout.items.indexOf(hit)].id); }
});

// Detailansicht: Musik mit dem Finger schieben, tippen = springen
let zm = null;
zoom.addEventListener('pointerdown', (e) => {
  if (!current() || e.button === 2) return;
  zoom.setPointerCapture(e.pointerId);
  zm = { x0: e.clientX, t0: audio.currentTime, moved: false };
});
zoom.addEventListener('pointermove', (e) => {
  if (!zm) return;
  if (!zm.moved && Math.abs(e.clientX - zm.x0) > 5) { zm.moved = true; scrubStart(); }
  if (zm.moved) scrubTo(zm.t0 - (e.clientX - zm.x0) / zoomLayout.pxPerSec);
});
zoom.addEventListener('pointerup', (e) => {
  if (!zm) return;
  const moved = zm.moved;
  zm = null;
  if (moved) return scrubEnd();
  const r = zoom.getBoundingClientRect();
  const x = e.clientX - r.left;
  const hit = hitMarker(zoomLayout.items, x, 12);
  if (hit) return cueMarker(hit.m);
  seek(snap(zoomLayout.start + x / zoomLayout.pxPerSec));
});
zoom.addEventListener('pointercancel', () => { if (zm?.moved) scrubEnd(false); zm = null; });
zoom.addEventListener('wheel', (e) => {
  if (!current()) return;
  e.preventDefault();
  seek(audio.currentTime + (e.deltaY + e.deltaX) / zoomLayout.pxPerSec * 0.5);
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
    version: 2,
    exported: new Date().toISOString(),
    groups: state.groups,
    settings: state.settings,
    tracks: state.tracks.map(({ peaks, id, ...rest }) => rest),
  };
  const json = JSON.stringify(data, null, 2);
  const name = `narrhalla-setliste-${new Date().toISOString().slice(0, 10)}.json`;
  if (isNative()) {
    try {
      const cap = window.Capacitor;
      const { uri } = await cap.nativePromise('Filesystem', 'writeFile', {
        path: name, data: json, directory: 'CACHE', encoding: 'utf8', recursive: true,
      });
      await cap.nativePromise('Share', 'share', {
        title: 'Narrhalla Setliste',
        dialogTitle: 'Setliste speichern oder senden',
        files: [uri],
      });
    } catch (e) {
      if (!/cancel/i.test(e?.message || '')) toast('Export fehlgeschlagen: ' + (e?.message || e));
    }
    return;
  }
  const blob = new Blob([json], { type: 'application/json' });
  const file = new File([blob], name, { type: 'application/json' });
  if (navigator.canShare?.({ files: [file] }) && /Android|iPhone|iPad/i.test(navigator.userAgent)) {
    try { await navigator.share({ files: [file], title: 'Narrhalla Setliste' }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  const a = el('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  toast('Setliste gespeichert: ' + name);
}

async function importSet(file) {
  try {
    const data = JSON.parse(await file.text());
    if (data.app !== 'narrhalla-player') throw new Error('Das ist keine Narrhalla-Setliste');
    for (const g of data.groups || []) {
      const have = group(g.id);
      if (have) Object.assign(have, g);
      else state.groups.push(g);
    }
    let hit = 0;
    const used = new Set();
    const findMatch = (src) => {
      const free = state.tracks.filter((x) => !used.has(x.id));
      return free.find((x) => x.name === src.name)
        || free.find((x) => src.fileName && x.fileName === src.fileName)
        || free.find((x) => Math.abs((x.duration || 0) - (src.duration || -9)) < 0.3);
    };
    const order = [];
    for (const src of data.tracks || []) {
      const t = findMatch(src);
      if (!t) continue;
      used.add(t.id);
      order.push(t.id);
      hit++;
      t.name = src.name || t.name;
      t.artist = src.artist ?? t.artist;
      t.groupId = src.groupId || '';
      t.cueIn = src.cueIn || 0;
      t.cueOut = src.cueOut ?? null;
      if (src.bpm !== undefined) { t.bpm = src.bpm; t.beatOffset = src.beatOffset || 0; }
      t.markers = (src.markers || []).map((m) => ({ ...m, id: uid() }));
    }
    const pos = (t) => { const i = order.indexOf(t.id); return i < 0 ? order.length : i; };
    state.tracks = state.tracks.map((t, i) => [t, i]).sort((a, b) => pos(a[0]) - pos(b[0]) || a[1] - b[1]).map((x) => x[0]);
    save();
    renderAll();
    const missing = (data.tracks || []).length - hit;
    toast(`${hit} Titel übernommen${missing ? ` – ${missing} fehlen (erst die Audiodateien laden)` : ''}`, 4000);
  } catch (e) {
    toast('Import fehlgeschlagen: ' + e.message, 4000);
  }
}

/* ---------- Events ---------- */
$('fileInput').onchange = (e) => { addFiles(e.target.files); e.target.value = ''; };
$('importInput').onchange = (e) => { if (e.target.files[0]) importSet(e.target.files[0]); e.target.value = ''; };
$('exportBtn').onclick = exportSet;
$('helpBtn').onclick = () => $('helpDlg').showModal();
$('settingsBtn').onclick = openSettings;
$('addGroupBtn').onclick = () => openGroupDialog(null);
$('nowTitle').onclick = () => current() && openTrackDialog(current());

$('playBtn').onclick = toggle;
$('stopBtn').onclick = stop;
$('replayBtn').onclick = replay;
$('replayMarkerBtn').onclick = replayFromMarker;
$('replayLastBtn').onclick = replayLast;
$('prevBtn').onclick = () => step(-1);
$('nextBtn').onclick = () => step(1);
$('backBtn').onclick = () => jump(-1);
$('fwdBtn').onclick = () => jump(1);
$('fadeBtn').onclick = () => fadeOut();
$('loopBtn').onclick = toggleLoop;
$('addMarkerBtn').onclick = () => addMarker();
$('cueInBtn').onclick = () => setCue('in');
$('cueOutBtn').onclick = () => setCue('out');
$('cueResetBtn').onclick = () => setCue('reset');
$('zoomIn').onclick = () => setZoom(-1);
$('zoomOut').onclick = () => setZoom(1);

$('bpmInput').onchange = (e) => setTempo(+e.target.value || null);
$('bpmHalf').onclick = () => current()?.bpm && setTempo(current().bpm / 2);
$('bpmDouble').onclick = () => current()?.bpm && setTempo(current().bpm * 2);
$('tapBtn').onclick = tapTempo;
$('detectBtn').onclick = redetect;
$('setOneBtn').onclick = () => setOne();
$('nudgeBack').onclick = () => current()?.bpm && setTempo(current().bpm, current().beatOffset - 0.01);
$('nudgeFwd').onclick = () => current()?.bpm && setTempo(current().bpm, current().beatOffset + 0.01);

function setCountMode(v) {
  state.settings.countMode = v;
  save();
  if (v === 'voice') loadCountSounds().catch(() => toast('Stimme nicht verfügbar – nutze Klick'));
  if (v !== 'off') ensureGraph();
  resetCountSchedule();
  renderTempo();
}
$('countModeSeg').onclick = (e) => { if (e.target.dataset.v) setCountMode(e.target.dataset.v); };
$('snapSeg').onclick = (e) => {
  if (!e.target.dataset.v) return;
  state.settings.snap = e.target.dataset.v;
  save();
  renderTempo();
};

function toggleLoop() {
  loop = !loop;
  $('loopBtn').classList.toggle('on', loop);
}

$('volume').oninput = (e) => {
  state.volume = +e.target.value;
  if (!fading) setVol(state.volume);
  save();
};
$('autoNext').onchange = (e) => { state.autoNext = e.target.checked; save(); };
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
(function init() {
  try {
    const saved = JSON.parse(localStorage.getItem(STATE_KEY) || 'null');
    if (saved) Object.assign(state, saved);
  } catch (e) {
    console.warn('Gespeicherter Stand unlesbar', e);
  }
  state.settings = { ...DEFAULT_SETTINGS, ...(state.settings || {}) };
  if (!ZOOMS.includes(state.settings.zoom)) state.settings.zoom = 8;
  $('volume').value = state.volume;
  $('autoNext').checked = state.autoNext;
  audio.volume = state.volume;
  if (state.settings.countMode === 'voice') loadCountSounds().catch(() => {});
  renderAll();
  if (state.tracks.length) load(state.tracks[0].id, false);
  requestAnimationFrame(frame);
  if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !isNative()) {
    navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Service Worker', e));
  }
  navigator.storage?.persist?.();
})();
