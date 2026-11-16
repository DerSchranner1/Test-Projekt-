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

function runAnalysis(mono, sr, onProgress) {
  const direct = () => Analysis.analyse(mono, sr, { onProgress });
  const w = getWorker();
  if (!w) return Promise.resolve().then(direct);
  return new Promise((resolve, reject) => {
    const id = ++jobId;
    const copy = new Float32Array(mono);
    jobs.set(id, { resolve, reject, onProgress, fallback: () => resolve(direct()) });
    w.postMessage({ id, mono: copy, sr }, [copy.buffer]);
  });
}

async function analyseBlob(blob, id = null, label = '') {
  const buf = await decodeLow(blob);
  const mono = mixMono(buf);
  const { peaks, hi } = makePeaks(mono, buf.sampleRate);
  const r = await runAnalysis(mono, buf.sampleRate, (p) => toast(`Analysiere ${label}… ${Math.round(p * 100)} %`, 30000));
  if (id) featCache = { id, F: r.F };
  return { duration: buf.duration, peaks, hi, segments: r.segments, beats: r.beats, ones: r.ones };
}

async function featuresFor(t) {
  if (featCache.id === t.id && featCache.F) return featCache.F;
  toast('Analysiere …', 30000);
  const buf = await decodeLow(await getFile(t.id));
  featCache = { id: t.id, F: Analysis.features(mixMono(buf), buf.sampleRate) };
  return featCache.F;
}

function toSegments(list, old = []) {
  return list.map((x, i) => ({
    id: uid(),
    start: x.start,
    name: old[i]?.name || '',
    labelId: old[i]?.labelId || null,
    color: list.length > 1 ? old[i]?.color || SEG_COLORS[i % SEG_COLORS.length] : old[0]?.color ?? null,
  }));
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
        duration: info.duration,
        peaks: info.peaks,
        groupId: filterGroup || '',
        markers: [],
        cueIn: 0,
        cueOut: null,
        segments: toSegments(info.segments),
        beats: info.beats,
        ones: info.ones,
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
  if (ok) {
    const last = state.tracks[state.tracks.length - 1];
    const n = last.segments.length;
    toast(ok === 1 && n > 1 ? `Geladen – ${n} Lieder erkannt, bitte kurz prüfen` : `${ok} Titel geladen – Takt bitte kurz prüfen`, 3500);
  }
  if (!currentId && state.tracks.length) load(state.tracks[0].id, false);
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
  const needTempo = !t.segments || !t.beats;
  if (!hi || needTempo) {
    try {
      const info = await analyseBlob(await getFile(t.id), t.id);
      hi = info.hi;
      await putHi(t.id, hi);
      if (needTempo) {
        t.segments = toSegments(info.segments, t.segments || []);
        t.beats = info.beats;
        t.ones = info.ones;
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
  for (const sel of [$('voiceSel'), $('setVoice')]) {
    sel.replaceChildren(...VOICES.map((v) => new Option(v.name, v.id)));
    sel.value = state.settings.voice;
  }
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
async function recordVoice() {
  const status = $('recordStatus');
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: true, autoGainControl: true } });
  } catch (e) {
    status.textContent = 'Kein Zugriff aufs Mikrofon – bitte erlauben';
    return;
  }
  const c = ctx();
  const src = c.createMediaStreamSource(stream);
  const proc = c.createScriptProcessor(4096, 1, 1);
  const chunks = [];
  proc.onaudioprocess = (e) => chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
  const mute = c.createGain();
  mute.gain.value = 0;
  src.connect(proc).connect(mute).connect(c.destination);
  status.classList.add('rec');
  for (let s = 10; s > 0; s--) {
    status.textContent = `● Aufnahme … ${s}`;
    await new Promise((r) => setTimeout(r, 1000));
  }
  status.classList.remove('rec');
  proc.disconnect();
  src.disconnect();
  stream.getTracks().forEach((t) => t.stop());
  const len = chunks.reduce((n, x) => n + x.length, 0);
  const all = new Float32Array(len);
  let o = 0;
  for (const x of chunks) { all.set(x, o); o += x.length; }
  const parts = splitWords(all, c.sampleRate);
  if (parts.length !== 8) {
    status.textContent = `${parts.length} Wörter erkannt statt 8 – bitte nochmal, mit kurzen Pausen dazwischen.`;
    return;
  }
  await putHi('voice:own', { sr: c.sampleRate, parts });
  voiceCache.delete('own');
  await setVoice('own');
  status.textContent = 'Gespeichert ✓ – „Eigene Aufnahme“ ist jetzt ausgewählt.';
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

function scheduler() {
  const t = current();
  if (!t || audio.paused) return;
  if (audio.currentTime >= cueOut(t) && t.cueOut != null) onEnded();
  if (state.settings.countMode === 'off' || !hasBeats(t) || !audioCtx || scrub) return;
  const now = audio.currentTime;
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
    scrubBuf = await decodeLow(await getFile(t.id));
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
  const c = countAt(t, at + 0.001);
  toast(`Marker bei ${fmt(m.time, true)}${c?.count ? ` (Zählzeit ${c.count})` : ''}`);
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

// gleichmäßiges Raster mit neuem Tempo, ausgerichtet am Schlag an der aktuellen Stelle
function setTempo(bpm) {
  const t = current();
  const i = curSegIndex();
  if (!t || i < 0 || !bpm) return;
  bpm = clamp(bpm, 40, 240);
  t.beats ??= [];
  t.ones ??= [];
  const [a, e] = segBeatRange(t, i);
  const now = viewTime();
  let k = bsearch(t.beats, now);
  if (k < a || k >= e) k = -1;
  const anchorTime = k >= 0 ? t.beats[k] : now;
  const cnt = k >= 0 ? countOfBeat(t, k) : 1;
  const bl = 60 / bpm;
  const oneAt = anchorTime - (cnt - 1) * bl;
  const g = gridBeats(t.segments[i].start, segEnd(t, i), bpm, oneAt);
  replaceBeats(t, a, e, g.beats, g.ones);
  afterBeatEdit();
}

function scaleTempo(f) {
  const t = current();
  const i = curSegIndex();
  if (!hasBeats(t) || i < 0) return;
  const [a, e] = segBeatRange(t, i);
  if (e - a < 4) return;
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
}

function nudge(sec) {
  const t = current();
  const i = curSegIndex();
  if (!hasBeats(t) || i < 0) return;
  const [a, e] = segBeatRange(t, i);
  for (let k = a; k < e; k++) t.beats[k] = Math.round((t.beats[k] + sec) * 1000) / 1000;
  afterBeatEdit();
}

let taps = [];
function tapTempo() {
  if (!current()) return;
  const now = performance.now();
  if (taps.length && now - taps[taps.length - 1] > 2000) taps = [];
  taps.push(now);
  if (taps.length > 8) taps.shift();
  if (taps.length >= 4) {
    const bpm = 60000 / ((taps[taps.length - 1] - taps[0]) / (taps.length - 1));
    setTempo(Math.round(bpm * 10) / 10);
    if (!audio.paused) setOne(audio.currentTime, false);
  } else {
    toast(`Weiter im Takt tippen … (${taps.length}/4)`, 1200);
  }
}

// Ab dieser Stelle neu mit 1 zählen
function setOne(at = viewTime(), msg = true) {
  const t = current();
  if (!hasBeats(t)) return toast('Erst Tempo erkennen oder antippen');
  const b = t.beats;
  let k = bsearch(b, at);
  if (k < 0 || (k + 1 < b.length && Math.abs(b[k + 1] - at) < Math.abs(b[k] - at))) k++;
  k = clamp(k, 0, b.length - 1);
  t.ones = [...t.ones.filter((j) => Math.abs(j - k) >= 8), k].sort((x, y) => x - y);
  afterBeatEdit();
  if (msg) toast('Ab hier wird neu mit 1 gezählt');
}

// Tempo und Schläge nur für das aktuelle Lied neu erkennen
async function redetect() {
  const t = current();
  const i = curSegIndex();
  if (!t || i < 0) return;
  try {
    const F = await featuresFor(t);
    const r = Analysis.analyseRange(F, t.segments[i].start, segEnd(t, i));
    if (!r.beats.length) return toast('Kein Takt erkannt – bitte Tap benutzen');
    t.beats ??= [];
    t.ones ??= [];
    const end = segEnd(t, i);
    while (r.beats.length > 1 && i + 1 < t.segments.length && end - r.beats[r.beats.length - 1] < 0.5 * (r.beats[1] - r.beats[0])) r.beats.pop();
    const [a, e] = segBeatRange(t, i);
    replaceBeats(t, a, e, r.beats, r.ones);
    afterBeatEdit();
    toast(`${segName(t, i)}: ${segBpm(t, i)} BPM erkannt`, 2500);
  } catch (e) {
    console.warn(e);
    toast('Erkennung fehlgeschlagen');
  }
}

// Alle Lieder in der Datei neu erkennen
async function detectSongs() {
  const t = current();
  if (!t) return;
  if (t.segments?.length > 1 && !confirm('Lieder neu erkennen? Eigene Grenzen und Zählkorrekturen werden ersetzt (Namen und Farben bleiben).')) return;
  try {
    const F = await featuresFor(t);
    toast('Suche Lieder …', 30000);
    await new Promise((r) => setTimeout(r, 30));
    const r = Analysis.songsFromFeatures(F);
    t.segments = toSegments(r.segments, t.segments);
    t.beats = r.beats;
    t.ones = r.ones;
    afterBeatEdit();
    renderAll();
    toast(t.segments.length > 1 ? `${t.segments.length} Lieder erkannt` : 'Ein durchgehendes Lied erkannt', 3000);
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
  t.segments.splice(i + 1, 0, { id: uid(), start: Math.round(at * 1000) / 1000, name: '', labelId: null, color: null });
  colorizeSegments(t);
  addOneAt(t, at);
  afterBeatEdit();
  renderAll();
  toast(`${segName(t, i + 1)} beginnt bei ${fmt(at, true)} – mit 1`, 2500);
}

function mergeSong(i) {
  const t = current();
  if (!t || i < 1) return;
  if (!confirm(`„${segName(t, i)}“ mit dem Lied davor zusammenführen?`)) return;
  t.segments.splice(i, 1);
  if (t.segments.length === 1) t.segments[0].color = null;
  afterBeatEdit();
  renderAll();
}

function setSongStart(i) {
  const t = current();
  if (!t || i < 1) return;
  const at = snap(viewTime(), t, 'beat');
  const lo = t.segments[i - 1].start + 2, hi = segEnd(t, i) - 2;
  if (at < lo || at > hi) return toast('Die Grenze muss zwischen den Nachbarliedern liegen');
  const old = t.segments[i].start;
  if (hasBeats(t)) {
    const k = bsearch(t.beats, old - 0.03) + 1;
    t.ones = t.ones.filter((j) => j !== k);
  }
  t.segments[i].start = Math.round(at * 1000) / 1000;
  addOneAt(t, at);
  afterBeatEdit();
  renderAll();
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
    const li = el('li', { draggable: true });
    li.dataset.id = t.id;
    if (t.id === selectedId) li.classList.add('selected');
    if (t.id === currentId) li.classList.add('playing');

    const stripe = el('span', { className: 'stripe' });
    stripe.style.background = t.color || g?.color || '#4a4a58';
    const title = el('span', { className: 'title' },
      el('span', { className: 't1', textContent: (t.id === currentId && !audio.paused ? '▶ ' : '') + t.name }),
      el('span', { className: 't2', textContent: [t.artist, g?.name, tempoText(t), t.markers.length ? `◆${t.markers.length}` : null].filter(Boolean).join(' · ') }));
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
  const eight = state.settings.jumpEight && hasBeats(t);
  $('backBtn').textContent = eight ? '−8' : '-5s';
  $('fwdBtn').textContent = eight ? '+8' : '+5s';
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
  $('tempoSeg').textContent = multi ? segName(t, i) : '';
  $('tempoSeg').style.color = multi ? segColor(t, sg) : '';
  $('countBox').classList.toggle('off', !bpm);
  for (const seg of ['countModeSeg', 'snapSeg']) {
    const key = seg === 'countModeSeg' ? 'countMode' : 'snap';
    for (const b of $(seg).children) b.classList.toggle('on', b.dataset.v === state.settings[key]);
  }
  renderNow();
}

function renderSongs() {
  const ul = $('songList');
  ul.replaceChildren();
  const t = current();
  if (!t?.segments) return;
  const curI = segIndexAt(t, viewTime());
  t.segments.forEach((sg, i) => {
    const color = el('button', { className: 'mcolor', title: 'Farbe des Lieds' });
    color.style.background = segColor(t, sg);
    color.onclick = async () => {
      const c = await pickColor(sg.color, sg.labelId);
      if (!c) return;
      sg.color = c.color;
      sg.labelId = c.labelId;
      colorizeSegments(t);
      save();
      renderSongs();
      draw();
    };
    const lab = colorLabel(sg.labelId);
    const name = el('input', { type: 'text', value: sg.name, placeholder: lab?.name || `Lied ${i + 1}`, maxLength: 40 });
    name.oninput = () => { sg.name = name.value; save(); draw(); };
    const startB = el('button', { className: 'mmini time', textContent: fmt(sg.start), title: 'Zum Liedanfang springen' });
    startB.onclick = () => { seek(sg.start); if (state.settings.markerPlay) play(); };
    const here = el('button', { className: 'mmini', textContent: '⇤', title: 'Liedanfang auf die aktuelle Stelle legen', disabled: i === 0 });
    here.onclick = () => setSongStart(i);
    const sb = segBpm(t, i);
    const bpm = el('span', { className: 'song-bpm', textContent: sb ? `${sb}` : '–', title: 'BPM' });
    const del = el('button', { className: 'mmini del', textContent: '✕', title: 'Mit dem Lied davor zusammenführen', disabled: i === 0 });
    del.onclick = () => mergeSong(i);
    const li = el('li', { className: i === curI ? 'cur' : '' }, color, name, startB, here, bpm, del);
    li.style.setProperty('--c', segColor(t, sg));
    ul.append(li);
  });
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
    const c = countAt(t, m.time + 0.001);
    const cue = el('button', { className: 'mcue', title: 'Ab hier abspielen' },
      el('span', { className: 'mn', textContent: i + 1 }),
      el('span', { textContent: fmt(m.time, true) }),
      c?.count ? el('span', { className: 'mc', textContent: `·${c.count}` }) : null);
    cue.style.background = m.color;
    cue.onclick = () => cueMarker(m);
    const label = el('input', { type: 'text', value: m.label, placeholder: 'Beschriftung…', maxLength: 40 });
    label.oninput = () => { m.label = label.value; save(); draw(); };
    const color = el('button', { className: 'mcolor', title: 'Farbe' });
    color.style.background = m.color;
    color.onclick = async () => {
      const c = await pickColor(m.color);
      if (c) { m.color = c.color; save(); renderMarkers(); draw(); }
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
  renderSongs();
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
  $('trackDlgDelete').onclick = async () => { if (await removeTrack(t.id)) dlg.close(); };
  dlg.onclose = () => {
    if (dlg.returnValue !== 'save') return;
    t.name = $('trackDlgName').value.trim() || t.name;
    t.artist = $('trackDlgArtist').value.trim();
    t.groupId = groupId;
    t.color = color;
    t.labelId = labelId;
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
  renderVoiceSelects();
  renderLabelList();
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
    $('countVol').value = state.settings.countVolume;
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
  const dur = t.duration || 1;
  const x = (sec) => (sec / dur) * w;
  const cur = viewTime();
  const segs = t.segments || [{ start: 0 }];
  const multi = segs.length > 1;

  const items = t.markers.map((m, i) => ({ x: Math.round(x(m.time)), text: markerText(m, i), color: m.color }));
  const lanes = t.markers.length ? layoutLabels(g, items, 3) : 0;
  const top = lanes * LANE_H + 2;
  const STRIP = multi ? 12 : 0;
  waveLayout = { top, items };

  const barW = 2, gap = 1;
  const bars = Math.floor(w / (barW + gap));
  const bottom = h - STRIP;
  const mid = top + (bottom - top) * 0.66;
  const up = mid - top - 1, down = bottom - mid - 1;
  const px = x(cur), inX = x(cueIn(t)), outX = x(cueOut(t));
  let si = 0;
  for (let i = 0; i < bars; i++) {
    const bx = i * (barW + gap);
    const sec = (bx / w) * dur;
    while (si + 1 < segs.length && segs[si + 1].start <= sec) si++;
    const col = segColor(t, segs[si]);
    const a = Math.floor((i / bars) * t.peaks.length);
    const b = Math.max(a + 1, Math.floor(((i + 1) / bars) * t.peaks.length));
    let p = 0;
    for (let j = a; j < b; j++) p = Math.max(p, t.peaks[j] || 0);
    p = Math.max(0.03, p);
    const outside = bx < inX || bx > outX;
    const played = bx < px;
    g.fillStyle = played || multi ? col : unplayed;
    g.globalAlpha = (outside ? 0.3 : 1) * (played ? 1 : multi ? 0.38 : 1);
    g.fillRect(bx, mid - p * up, barW, p * up);
    g.globalAlpha *= 0.45;
    g.fillRect(bx, mid + 1, barW, p * down);
  }
  g.globalAlpha = 1;
  // Lieder-Leiste mit Namen
  if (multi) {
    g.font = '600 10px system-ui, sans-serif';
    g.textBaseline = 'middle';
    segs.forEach((sg, i) => {
      const a = x(sg.start), b = x(segEnd(t, i));
      g.fillStyle = segColor(t, sg);
      g.fillRect(a, bottom + 1, Math.max(1, b - a - 1), STRIP - 1);
      g.fillStyle = 'rgba(255,255,255,.55)';
      if (i > 0) g.fillRect(a, top, 1, bottom - top);
      const label = segName(t, i);
      if (b - a > 26) {
        g.save();
        g.beginPath(); g.rect(a, bottom, b - a - 2, STRIP); g.clip();
        g.fillStyle = '#111';
        g.fillText(label, a + 3, bottom + STRIP / 2 + 0.5);
        g.restore();
      }
    });
  }
  g.fillStyle = css('--accent-2');
  if (t.cueIn > 0) g.fillRect(inX, top, 1, bottom - top);
  if (t.cueOut != null) g.fillRect(outX - 1, top, 1, bottom - top);
  for (const it of items) {
    g.fillStyle = it.color;
    g.fillRect(it.x, top, 2, bottom - top);
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
    const barW = 2;
    for (let x = 0; x < w; x += barW + 1) {
      const sec = start + x / pxPerSec;
      if (sec < 0 || sec > t.duration) continue;
      const a = Math.floor(sec * HI_RATE), b = Math.max(a + 1, Math.floor((start + (x + barW + 1) / pxPerSec) * HI_RATE));
      let p = 0;
      for (let j = a; j < b && j < hi.length; j++) if (hi[j] > p) p = hi[j];
      const v = Math.max(0.02, p / 255) * amp;
      const outside = sec < cueIn(t) || sec > cueOut(t);
      const played = sec < cur;
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
    let k = Math.max(0, bsearch(b, start));
    const avg = b.length > 1 ? (b[b.length - 1] - b[0]) / (b.length - 1) : 0.5;
    const step = pxPerSec * avg < 14 ? 2 : 1;
    for (; k < b.length && b[k] <= start + span; k++) {
      const x = X(b[k]);
      const c = countOfBeat(t, k);
      const one = c === 1;
      g.fillStyle = one ? 'rgba(255,210,63,.55)' : 'rgba(255,255,255,.12)';
      g.fillRect(Math.round(x), COUNT_H, one ? 2 : 1, h - COUNT_H);
      if (one || mod(c - 1, step) === 0) {
        g.font = one ? '700 13px system-ui, sans-serif' : '600 11px system-ui, sans-serif';
        g.fillStyle = one ? css('--accent-2') : c === 5 ? '#ddd' : '#999';
        g.fillText(String(c), x, COUNT_H / 2);
      }
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
  box.style.borderColor = multi ? segColor(t, t.segments[c.seg]) : '';
  if (!c?.count) {
    num.textContent = '–';
    eight.textContent = t ? (multi ? segName(t, c?.seg || 0) : 'kein Takt') : '';
    lastCountShown = null;
    return;
  }
  const key = `${c.seg}:${c.k}`;
  if (key === lastCountShown) return;
  const segChanged = !lastCountShown || lastCountShown.split(':')[0] !== String(c.seg);
  lastCountShown = key;
  num.textContent = c.count;
  eight.textContent = (c.eight >= 1 ? `Achter ${c.eight}` : 'Vorlauf') + (multi ? ` · ${c.seg + 1}` : '');
  box.classList.toggle('one', c.count === 1);
  if (!audio.paused) { box.classList.remove('pulse'); void box.offsetWidth; box.classList.add('pulse'); }
  if (segChanged) { renderTempo(); renderSongs(); }
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
      if (src.segments) t.segments = src.segments.map((x) => ({ ...x, id: uid() }));
      if (src.beats) { t.beats = src.beats; t.ones = src.ones || [0]; }
      else if (t.segments) migrateTrack(t, src);
      if (src.color !== undefined) t.color = src.color;
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
$('voiceSel').onchange = (e) => { setVoice(e.target.value); if (state.settings.countMode !== 'voice') setCountMode('voice'); };
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
  $('autoNext').checked = state.autoNext;
  audio.volume = state.volume;
  state.colorLabels ??= [];
  if (!VOICES.some((v) => v.id === state.settings.voice)) state.settings.voice = 'de-frau';
  renderVoiceSelects();
  if (state.settings.countMode === 'voice') loadCountSounds().catch(() => {});
  renderAll();
  if (state.tracks.length) load(state.tracks[0].id, false);
  requestAnimationFrame(frame);
  if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !isNative()) {
    navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Service Worker', e));
  }
  navigator.storage?.persist?.();
})();
