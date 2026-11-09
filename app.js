/* Narrhalla Player – Draft
 * Reine Browser-App ohne Build-Schritt. Audiodateien liegen in IndexedDB,
 * Setliste (Gruppen, Marker, Cues) in localStorage.
 */
'use strict';

const $ = (id) => document.getElementById(id);
const STATE_KEY = 'narrhalla.state.v1';
const PEAK_COUNT = 600;
const MARKER_COLORS = ['#ffd23f', '#3ddc97', '#4cc9f0', '#f72585', '#ff7a1a', '#b388ff'];

const DEFAULT_GROUPS = [
  { id: 'g-einmarsch', name: 'Einmarsch', color: '#e5484d' },
  { id: 'g-garde', name: 'Garde / Tanz', color: '#4cc9f0' },
  { id: 'g-tusch', name: 'Tusch & Jingles', color: '#ffd23f' },
  { id: 'g-buett', name: 'Bütt', color: '#3ddc97' },
  { id: 'g-pause', name: 'Pause / Hintergrund', color: '#b388ff' },
];

const state = {
  groups: DEFAULT_GROUPS.slice(),
  tracks: [],          // {id, name, duration, groupId, markers[], cueIn, cueOut, peaks[]}
  volume: 1,
  autoNext: false,
};

let currentId = null;      // im Deck geladener Titel
let selectedId = null;     // in der Liste gewählter Titel
let lastPlayedId = null;   // davor gespielter Titel (für "letzter Titel")
let filterGroup = null;    // null = alle, '' = ohne Gruppe
let loop = false;
let fading = null;
const history = [];
const urlCache = new Map();
const audio = new Audio();
audio.preload = 'auto';

/* ---------- Helpers ---------- */
const uid = () => Math.random().toString(36).slice(2, 10);
const track = (id) => state.tracks.find((t) => t.id === id);
const group = (id) => state.groups.find((g) => g.id === id);
const current = () => track(currentId);

function fmt(sec, tenths = false) {
  if (!isFinite(sec) || sec < 0) sec = 0;
  const m = Math.floor(sec / 60);
  const s = sec - m * 60;
  return tenths
    ? `${m}:${s.toFixed(1).padStart(4, '0')}`
    : `${m}:${String(Math.floor(s)).padStart(2, '0')}`;
}

let toastTimer;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 1800);
}

function save() {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(state));
  } catch (e) {
    console.warn('Speichern fehlgeschlagen', e);
  }
}

/* ---------- IndexedDB (Audiodateien) ---------- */
let dbPromise;
function db() {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('narrhalla', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('files');
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}
async function idb(mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction('files', mode);
    const req = fn(tx.objectStore('files'));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = () => reject(tx.error);
  });
}
const putFile = (id, blob) => idb('readwrite', (s) => s.put(blob, id));
const getFile = (id) => idb('readonly', (s) => s.get(id));
const delFile = (id) => idb('readwrite', (s) => s.delete(id));

async function urlFor(id) {
  if (!urlCache.has(id)) {
    const blob = await getFile(id);
    if (!blob) return null;
    urlCache.set(id, URL.createObjectURL(blob));
  }
  return urlCache.get(id);
}

/* ---------- Import von Audiodateien ---------- */
let audioCtx;
async function analyse(file) {
  audioCtx ??= new (window.AudioContext || window.webkitAudioContext)();
  const buf = await audioCtx.decodeAudioData(await file.arrayBuffer());
  const ch = buf.getChannelData(0);
  const step = Math.max(1, Math.floor(ch.length / PEAK_COUNT));
  const peaks = [];
  let max = 0;
  for (let i = 0; i < PEAK_COUNT; i++) {
    let p = 0;
    const end = Math.min(ch.length, (i + 1) * step);
    for (let j = i * step; j < end; j += 8) {
      const v = Math.abs(ch[j]);
      if (v > p) p = v;
    }
    peaks.push(p);
    if (p > max) max = p;
  }
  return {
    duration: buf.duration,
    peaks: peaks.map((p) => Math.round((max ? p / max : 0) * 100) / 100),
  };
}

async function addFiles(files) {
  const list = [...files].filter((f) => f.type.startsWith('audio/') || /\.(mp3|wav|m4a|ogg|flac|aac)$/i.test(f.name));
  if (!list.length) return;
  for (const file of list) {
    toast(`Lade „${file.name}“ …`);
    try {
      const info = await analyse(file);
      const id = uid();
      await putFile(id, file);
      state.tracks.push({
        id,
        name: file.name.replace(/\.[^.]+$/, ''),
        duration: info.duration,
        peaks: info.peaks,
        groupId: filterGroup || '',
        markers: [],
        cueIn: 0,
        cueOut: null,
      });
      save();
      renderAll();
    } catch (e) {
      console.error(e);
      toast(`„${file.name}“ konnte nicht gelesen werden`);
    }
  }
  toast(`${list.length} Datei(en) geladen`);
  if (!currentId && state.tracks.length) load(state.tracks[0].id, false);
}

/* ---------- Playback ---------- */
const cueIn = (t) => t?.cueIn || 0;
const cueOut = (t) => (t && t.cueOut != null ? t.cueOut : t?.duration ?? audio.duration ?? 0);

async function load(id, autoplay = true, at = null) {
  const t = track(id);
  if (!t) return;
  cancelFade();
  if (id !== currentId) {
    const url = await urlFor(id);
    if (!url) return toast('Audiodatei fehlt – bitte neu laden');
    if (currentId && history[0]?.id === currentId) lastPlayedId = currentId;
    currentId = id;
    selectedId = id;
    audio.src = url;
    await new Promise((res) => {
      audio.addEventListener('loadedmetadata', res, { once: true });
      audio.addEventListener('error', res, { once: true });
    });
  }
  audio.currentTime = at ?? cueIn(t);
  renderAll();
  if (autoplay) play();
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
  audio.volume = state.volume;
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
  audio.currentTime = Math.max(0, Math.min(sec, t.duration - 0.01));
  draw();
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
  // letzter Marker vor der aktuellen Position (0,3 s Toleranz, damit mehrfaches Drücken zurückspringt)
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
  const from = audio.volume;
  const tick = (now) => {
    const k = Math.min(1, (now - start) / ms);
    audio.volume = from * (1 - k);
    if (k < 1) fading = requestAnimationFrame(tick);
    else { fading = null; audio.pause(); audio.volume = state.volume; }
  };
  fading = requestAnimationFrame(tick);
}
function cancelFade() {
  if (fading) cancelAnimationFrame(fading);
  fading = null;
  audio.volume = state.volume;
}

audio.addEventListener('ended', onEnded);
audio.addEventListener('play', syncPlayBtn);
audio.addEventListener('pause', syncPlayBtn);

function syncPlayBtn() {
  $('playBtn').textContent = audio.paused ? '▶' : '⏸';
  renderTracks();
}

/* ---------- Marker & Cues ---------- */
function addMarker(time = audio.currentTime) {
  const t = current();
  if (!t) return toast('Erst einen Titel laden');
  const m = {
    id: uid(),
    time: Math.max(0, Math.min(time, t.duration)),
    label: '',
    color: MARKER_COLORS[t.markers.length % MARKER_COLORS.length],
  };
  t.markers.push(m);
  t.markers.sort((a, b) => a.time - b.time);
  save();
  renderMarkers();
  renderTracks();
  draw();
  toast(`Marker bei ${fmt(m.time, true)}`);
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

function jumpMarker(i) {
  const m = current()?.markers[i];
  if (m) { seek(m.time); if (audio.paused) play(); }
}

function setCue(which) {
  const t = current();
  if (!t) return;
  const now = audio.currentTime;
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
  draw();
}

/* ---------- Rendering ---------- */
function visibleTracks() {
  const q = $('search').value.trim().toLowerCase();
  return state.tracks.filter((t) =>
    (filterGroup === null || (t.groupId || '') === filterGroup) &&
    (!q || t.name.toLowerCase().includes(q)));
}

function el(tag, props = {}, ...children) {
  const n = Object.assign(document.createElement(tag), props);
  n.append(...children);
  return n;
}

function renderGroups() {
  const ul = $('groupList');
  ul.replaceChildren();
  const row = (id, name, color, count, deletable) => {
    const li = el('li', { className: filterGroup === id ? 'active' : '' });
    let dot;
    if (deletable) {
      dot = el('input', { type: 'color', value: color, className: 'dot', title: 'Farbe ändern' });
      dot.style.cssText = 'padding:0;border:0;background:none;width:18px;height:18px;cursor:pointer';
      dot.onclick = (e) => e.stopPropagation();
      dot.oninput = () => { group(id).color = dot.value; save(); renderTracks(); renderNow(); };
    } else {
      dot = el('span', { className: 'dot' });
      dot.style.background = color;
    }
    li.append(dot, el('span', { className: 'name', textContent: name }), el('span', { className: 'count', textContent: count }));
    if (deletable) {
      const del = el('button', { className: 'del', textContent: '✕', title: 'Gruppe löschen' });
      del.onclick = (e) => {
        e.stopPropagation();
        if (!confirm(`Gruppe „${name}“ löschen? Titel bleiben erhalten.`)) return;
        state.groups = state.groups.filter((g) => g.id !== id);
        state.tracks.forEach((t) => { if (t.groupId === id) t.groupId = ''; });
        if (filterGroup === id) filterGroup = null;
        save();
        renderAll();
      };
      li.append(del);
    }
    li.onclick = () => { filterGroup = id; renderGroups(); renderTracks(); };
    return li;
  };
  ul.append(row(null, 'Alle Titel', 'linear-gradient(135deg,#e5484d,#ffd23f,#4cc9f0)', state.tracks.length, false));
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
    const title = el('span', { className: 'title', textContent: t.name, title: t.name });
    if (t.id === currentId && !audio.paused) title.prepend('▶ ');
    if (t.markers.length) title.append(el('span', { className: 'badge', textContent: `◆${t.markers.length}` }));

    const sel = el('select', { title: 'Farbgruppe' });
    sel.append(new Option('– keine –', ''));
    state.groups.forEach((x) => sel.append(new Option(x.name, x.id)));
    sel.value = t.groupId || '';
    sel.onclick = (e) => e.stopPropagation();
    sel.onchange = () => { t.groupId = sel.value; save(); renderAll(); };

    const del = el('button', { className: 'mdel', textContent: '✕', title: 'Titel entfernen' });
    del.style.cssText = 'background:none;border:0;color:var(--muted);cursor:pointer';
    del.onclick = (e) => { e.stopPropagation(); removeTrack(t.id); };

    li.append(stripe, el('span', { className: 'num', textContent: i + 1 }), title,
      el('span', { className: 'meta', textContent: fmt(cueOut(t) - cueIn(t)) }), sel, del);

    li.onclick = () => { selectedId = t.id; renderTracks(); };
    li.ondblclick = () => load(t.id, true);

    // Reihenfolge per Drag & Drop
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
  if (!t || !confirm(`„${t.name}“ entfernen?`)) return;
  if (id === currentId) { audio.pause(); audio.removeAttribute('src'); currentId = null; }
  state.tracks = state.tracks.filter((x) => x.id !== id);
  if (urlCache.has(id)) { URL.revokeObjectURL(urlCache.get(id)); urlCache.delete(id); }
  await delFile(id);
  save();
  renderAll();
}

function renderNow() {
  const t = current();
  const g = group(t?.groupId);
  $('nowTitle').textContent = t ? t.name : 'Kein Titel geladen';
  $('nowGroup').textContent = g ? g.name : '–';
  $('nowColor').style.background = g?.color || '#4a4a58';
  $('timeDur').textContent = fmt(t?.duration || 0);
}

function renderCues() {
  const t = current();
  $('cueInVal').textContent = fmt(cueIn(t), true);
  $('cueOutVal').textContent = t?.cueOut != null ? fmt(t.cueOut, true) : 'Ende';
}

function renderMarkers() {
  const ul = $('markerList');
  ul.replaceChildren();
  const t = current();
  if (!t || !t.markers.length) {
    ul.append(el('li', { className: 'empty', textContent: 'Noch keine Marker – z. B. „Einsatz Garde“, „Tusch“, „Refrain“.' }));
    ul.firstChild.style.display = 'block';
    return;
  }
  t.markers.forEach((m, i) => {
    const color = el('input', { type: 'color', value: m.color });
    color.oninput = () => { m.color = color.value; save(); draw(); };
    const time = el('button', { className: 'mtime', textContent: fmt(m.time, true), title: 'Hierhin springen' });
    time.onclick = () => jumpMarker(i);
    const label = el('input', { type: 'text', value: m.label, placeholder: 'Beschriftung…', maxLength: 60 });
    label.oninput = () => { m.label = label.value; save(); draw(); };
    const del = el('button', { className: 'mdel', textContent: '✕', title: 'Marker löschen' });
    del.onclick = () => removeMarker(m.id);
    ul.append(el('li', {}, el('span', { className: 'mnum', textContent: i + 1 }), color, time, label, del));
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
  renderMarkers();
  renderHistory();
  draw();
}

/* ---------- Wellenform (SoundCloud-Stil) ---------- */
const canvas = $('wave');
const ctx = canvas.getContext('2d');
const MARKER_ZONE = 22; // Platz oben für Marker-Fähnchen

function resizeCanvas() {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
    canvas.width = w * dpr;
    canvas.height = h * dpr;
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return { w, h };
}

function draw() {
  const { w, h } = resizeCanvas();
  ctx.clearRect(0, 0, w, h);
  const t = current();
  const css = getComputedStyle(document.documentElement);
  const unplayed = css.getPropertyValue('--unplayed').trim();
  const played = group(t?.groupId)?.color || css.getPropertyValue('--played').trim();

  const cur = t ? audio.currentTime : 0;
  $('timeCur').textContent = fmt(cur);
  $('timeRemain').textContent = '-' + fmt(Math.max(0, cueOut(t) - cur));
  if (!t) {
    ctx.fillStyle = unplayed;
    ctx.fillRect(0, (h + MARKER_ZONE) / 2, w, 1);
    return;
  }

  const dur = t.duration || 1;
  const x = (sec) => (sec / dur) * w;
  const barW = 3, gap = 1;
  const bars = Math.floor(w / (barW + gap));
  const mid = MARKER_ZONE + (h - MARKER_ZONE) * 0.68; // Spiegelung unten wie bei SoundCloud
  const up = mid - MARKER_ZONE - 2;
  const down = h - mid - 2;
  const px = x(cur);
  const inX = x(cueIn(t)), outX = x(cueOut(t));

  for (let i = 0; i < bars; i++) {
    const bx = i * (barW + gap);
    const a = Math.floor((i / bars) * t.peaks.length);
    const b = Math.max(a + 1, Math.floor(((i + 1) / bars) * t.peaks.length));
    let p = 0;
    for (let j = a; j < b; j++) p = Math.max(p, t.peaks[j] || 0);
    p = Math.max(0.03, p);
    const outside = bx < inX || bx > outX;
    ctx.globalAlpha = outside ? 0.3 : 1;
    ctx.fillStyle = bx < px && !outside ? played : unplayed;
    ctx.fillRect(bx, mid - p * up, barW, p * up);
    ctx.globalAlpha *= 0.45;
    ctx.fillRect(bx, mid + 1, barW, p * down);
  }
  ctx.globalAlpha = 1;

  // Cue-Grenzen
  ctx.fillStyle = css.getPropertyValue('--accent-2').trim();
  if (t.cueIn > 0) ctx.fillRect(inX, MARKER_ZONE, 1, h - MARKER_ZONE);
  if (t.cueOut != null) ctx.fillRect(outX - 1, MARKER_ZONE, 1, h - MARKER_ZONE);

  // Marker
  ctx.font = '600 11px system-ui, sans-serif';
  ctx.textBaseline = 'middle';
  t.markers.forEach((m, i) => {
    const mx = Math.round(x(m.time));
    ctx.fillStyle = m.color;
    ctx.fillRect(mx, 4, 2, h - 4);
    const text = `${i + 1}${m.label ? ' ' + m.label : ''}`;
    const tw = Math.min(ctx.measureText(text).width + 8, 140);
    const left = mx + tw > w ? mx - tw + 2 : mx; // am rechten Rand nach links klappen
    ctx.fillRect(left, 2, tw, 16);
    ctx.fillStyle = '#111';
    ctx.save();
    ctx.beginPath();
    ctx.rect(left, 2, tw - 2, 16);
    ctx.clip();
    ctx.fillText(text, left + 4, 10.5);
    ctx.restore();
  });

  // Abspielposition
  ctx.fillStyle = '#fff';
  ctx.fillRect(px, MARKER_ZONE - 4, 1.5, h - MARKER_ZONE + 4);
}

function canvasTime(e) {
  const t = current();
  if (!t) return null;
  const r = canvas.getBoundingClientRect();
  return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * t.duration;
}
function markerAt(e) {
  const t = current();
  if (!t) return null;
  const r = canvas.getBoundingClientRect();
  const px = e.clientX - r.left;
  return t.markers.find((m) => Math.abs((m.time / t.duration) * r.width - px) < 6) || null;
}

canvas.addEventListener('click', (e) => {
  const m = markerAt(e);
  const sec = m ? m.time : canvasTime(e);
  if (sec != null) seek(sec);
});
canvas.addEventListener('dblclick', (e) => {
  const sec = canvasTime(e);
  if (sec != null && !markerAt(e)) addMarker(sec);
});
canvas.addEventListener('contextmenu', (e) => {
  const m = markerAt(e);
  if (m) { e.preventDefault(); removeMarker(m.id); }
});

function frame() {
  const t = current();
  if (t && !audio.paused) {
    if (t.cueOut != null && audio.currentTime >= t.cueOut) onEnded();
    draw();
  }
  requestAnimationFrame(frame);
}

/* ---------- Export / Import der Setliste ---------- */
function exportSet() {
  const data = {
    app: 'narrhalla-player',
    version: 1,
    groups: state.groups,
    tracks: state.tracks.map(({ peaks, id, ...rest }) => rest),
  };
  const a = el('a', {
    href: URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })),
    download: `narrhalla-setliste-${new Date().toISOString().slice(0, 10)}.json`,
  });
  a.click();
  URL.revokeObjectURL(a.href);
}

async function importSet(file) {
  try {
    const data = JSON.parse(await file.text());
    if (data.app !== 'narrhalla-player') throw new Error('Unbekanntes Format');
    for (const g of data.groups || []) {
      const have = group(g.id);
      if (have) Object.assign(have, g);
      else state.groups.push(g);
    }
    let hit = 0;
    for (const src of data.tracks || []) {
      const t = state.tracks.find((x) => x.name === src.name);
      if (!t) continue;
      hit++;
      t.groupId = src.groupId || '';
      t.cueIn = src.cueIn || 0;
      t.cueOut = src.cueOut ?? null;
      t.markers = (src.markers || []).map((m) => ({ ...m, id: uid() }));
    }
    // Reihenfolge der Setliste übernehmen
    const order = (data.tracks || []).map((x) => x.name);
    const pos = (t) => { const i = order.indexOf(t.name); return i < 0 ? order.length : i; };
    state.tracks = state.tracks.map((t, i) => [t, i]).sort((a, b) => pos(a[0]) - pos(b[0]) || a[1] - b[1]).map((x) => x[0]);
    save();
    renderAll();
    toast(`${hit} von ${(data.tracks || []).length} Titeln zugeordnet`);
  } catch (e) {
    toast('Import fehlgeschlagen: ' + e.message);
  }
}

/* ---------- Events ---------- */
$('fileInput').onchange = (e) => { addFiles(e.target.files); e.target.value = ''; };
$('importInput').onchange = (e) => { if (e.target.files[0]) importSet(e.target.files[0]); e.target.value = ''; };
$('exportBtn').onclick = exportSet;
$('helpBtn').onclick = () => $('helpDlg').showModal();

$('playBtn').onclick = toggle;
$('stopBtn').onclick = stop;
$('replayBtn').onclick = replay;
$('replayMarkerBtn').onclick = replayFromMarker;
$('replayLastBtn').onclick = replayLast;
$('prevBtn').onclick = () => step(-1);
$('nextBtn').onclick = () => step(1);
$('back5Btn').onclick = () => seek(audio.currentTime - 5);
$('fwd5Btn').onclick = () => seek(audio.currentTime + 5);
$('fadeBtn').onclick = () => fadeOut();
$('loopBtn').onclick = toggleLoop;
$('addMarkerBtn').onclick = () => addMarker();
$('cueInBtn').onclick = () => setCue('in');
$('cueOutBtn').onclick = () => setCue('out');
$('cueResetBtn').onclick = () => setCue('reset');

function toggleLoop() {
  loop = !loop;
  $('loopBtn').classList.toggle('on', loop);
}

$('volume').oninput = (e) => {
  state.volume = +e.target.value;
  if (!fading) audio.volume = state.volume;
  save();
};
$('autoNext').onchange = (e) => { state.autoNext = e.target.checked; save(); };
$('search').oninput = renderTracks;

$('groupForm').onsubmit = (e) => {
  e.preventDefault();
  const name = $('groupName').value.trim();
  if (!name) return;
  state.groups.push({ id: 'g-' + uid(), name, color: $('groupColor').value });
  $('groupName').value = '';
  save();
  renderAll();
};

// Dateien per Drag & Drop
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
  if ($('helpDlg').open) return;
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
  else if (k === 'arrowleft') { seek(audio.currentTime - 5); handled(); }
  else if (k === 'arrowright') { seek(audio.currentTime + 5); handled(); }
  else if (k === 'arrowup' || k === 'arrowdown') {
    const list = visibleTracks();
    const i = list.findIndex((t) => t.id === selectedId);
    const n = list[Math.max(0, Math.min(list.length - 1, i + (k === 'arrowup' ? -1 : 1)))];
    if (n) { selectedId = n.id; renderTracks(); document.querySelector('.track-list li.selected')?.scrollIntoView({ block: 'nearest' }); }
    handled();
  }
  else if (k === 'enter' && selectedId) load(selectedId, true);
  else if (/^[1-9]$/.test(e.key)) jumpMarker(+e.key - 1);
});

/* ---------- Start ---------- */
(function init() {
  try {
    const saved = JSON.parse(localStorage.getItem(STATE_KEY) || 'null');
    if (saved) Object.assign(state, saved);
  } catch (e) {
    console.warn('Gespeicherter Stand unlesbar', e);
  }
  $('volume').value = state.volume;
  $('autoNext').checked = state.autoNext;
  audio.volume = state.volume;
  renderAll();
  if (state.tracks.length) load(state.tracks[0].id, false);
  requestAnimationFrame(frame);
})();
