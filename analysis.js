/* Narrhalla Player – Audio-Analyse
 * Findet in einer Datei (z. B. einem fertig geschnittenen Show-Medley) die einzelnen Lieder,
 * verfolgt jeden Schlag einzeln und legt fest, wo die „1“ der Tanz-Zählung (1–8) ist.
 *
 *  1. Einsatzkurve: spektraler Fluss über 40 Mel-Bänder in 10-ms-Schritten
 *  2. Lieder: Neuheitskurve aus Harmonie, Klangfarbe und Rhythmus
 *  3. Tempo je Lied: Autokorrelation in 8-s-Fenstern mit Vorliebe für ~120 BPM
 *  4. Schläge: dynamische Programmierung (Ellis 2007) – folgt auch Schnitten im Lied
 *  5. „1“: an jedem Liedanfang und nach jedem Schnitt neu, sonst aus Bass und Harmoniewechsel
 *
 * Reine Funktionen auf einem Mono-Signal; läuft im Browser und in Node (für Tests).
 */
function NarrhallaAnalysis(root) {
  'use strict';

  const FPS = 100;   // Auflösung der Einsatzkurve
  const TEMPO = { center: 120, sd: 1.0, comb: 0, halfAbove: 150, halfRatio: 1.3 };
  const CFPS = 10;   // Auflösung von Harmonie und Klangfarbe

  /* ---------- FFT (radix 2, in place) ---------- */
  function fft(re, im) {
    const n = re.length;
    for (let i = 1, j = 0; i < n; i++) {
      let bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const ang = (-2 * Math.PI) / len;
      const wr = Math.cos(ang), wi = Math.sin(ang);
      const half = len >> 1;
      for (let i = 0; i < n; i += len) {
        let cr = 1, ci = 0;
        for (let k = 0; k < half; k++) {
          const a = i + k, b = a + half;
          const tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
          re[b] = re[a] - tr; im[b] = im[a] - ti;
          re[a] += tr; im[a] += ti;
          const nr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = nr;
        }
      }
    }
  }

  const mod = (a, n) => ((a % n) + n) % n;
  function normalize(v) {
    let s = 0;
    for (const x of v) s += x * x;
    s = Math.sqrt(s) || 1;
    return v.map((x) => x / s);
  }
  function cos(a, b) { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
  function meanVec(arr, dim, from, to) {
    const v = new Float32Array(dim);
    const a = Math.max(0, from), b = Math.max(a + 1, to);
    for (let i = a; i < b; i++) for (let d = 0; d < dim; d++) v[d] += arr[i * dim + d] || 0;
    return v.map((x) => x / (b - a));
  }
  function median(arr) {
    const s = Array.from(arr).sort((a, b) => a - b);
    return s.length ? s[s.length >> 1] : 0;
  }
  function hann(n) {
    const w = new Float32Array(n);
    for (let i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1));
    return w;
  }

  /* ---------- 1. Merkmale ---------- */
  function features(mono, sr, onProgress) {
    const hop = sr / FPS;
    const n = Math.floor(mono.length / hop);
    const N = 1024, NB = 40;
    const win = hann(N);
    const re = new Float32Array(N), im = new Float32Array(N);

    // dreieckige Mel-Filter
    const mel = (f) => 2595 * Math.log10(1 + f / 700);
    const imel = (m) => 700 * (10 ** (m / 2595) - 1);
    const lo = mel(30), hi = mel(sr / 2);
    const cen = [];
    for (let b = 0; b < NB + 2; b++) cen.push(imel(lo + ((hi - lo) * b) / (NB + 1)));
    const fk = [], fb = [], fw = [];
    let lowBands = 0;
    for (let b = 0; b < NB; b++) if (cen[b + 1] < 200) lowBands = b + 1;
    lowBands = Math.max(2, lowBands);
    for (let k = 1; k < N / 2; k++) {
      const f = (k * sr) / N;
      for (let b = 0; b < NB; b++) {
        const l = cen[b], c = cen[b + 1], r = cen[b + 2];
        const w = f > l && f <= c ? (f - l) / (c - l) : f > c && f < r ? (r - f) / (r - c) : 0;
        if (w > 0) { fk.push(k); fb.push(b); fw.push(w); }
      }
    }

    const db = new Float32Array(n * NB);
    const rms = new Float32Array(n);
    const p = new Float32Array(NB);
    let gmax = -1e9;
    for (let i = 0; i < n; i++) {
      const c = Math.floor(i * hop - N / 2);
      for (let j = 0; j < N; j++) { re[j] = (mono[c + j] || 0) * win[j]; im[j] = 0; }
      let e = 0;
      for (let j = Math.floor(i * hop), end = Math.floor((i + 1) * hop); j < end; j++) e += mono[j] * mono[j];
      rms[i] = Math.sqrt(e / hop);
      fft(re, im);
      p.fill(0);
      for (let q = 0; q < fk.length; q++) { const k = fk[q]; p[fb[q]] += fw[q] * (re[k] * re[k] + im[k] * im[k]); }
      for (let b = 0; b < NB; b++) {
        const v = 10 * Math.log10(Math.max(1e-10, p[b]));
        db[i * NB + b] = v;
        if (v > gmax) gmax = v;
      }
      if (onProgress && i % 4000 === 0) onProgress(i / n);
    }
    const floor = gmax - 80;
    const env = new Float32Array(n), onL = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      let s = 0, sl = 0;
      for (let b = 0; b < NB; b++) {
        const d = Math.max(floor, db[i * NB + b]) - (i ? Math.max(floor, db[(i - 1) * NB + b]) : floor);
        if (d > 0) { s += d; if (b < lowBands) sl += d; }
      }
      env[i] = s / NB;
      onL[i] = sl / lowBands;
    }
    let sd = 0;
    for (const v of env) sd += v * v;
    sd = Math.sqrt(sd / n) || 1;
    for (let i = 0; i < n; i++) env[i] /= sd;
    // nur für die Liedsuche: Einsätze über dem gleitenden Mittel
    const flt = new Float32Array(n);
    const pre = new Float64Array(n + 1);
    for (let i = 0; i < n; i++) pre[i + 1] = pre[i] + env[i];
    for (let i = 0; i < n; i++) {
      const a = Math.max(0, i - 25), b = Math.min(n, i + 26);
      flt[i] = Math.max(0, env[i] - (pre[b] - pre[a]) / (b - a));
    }

    // Harmonie (Chroma) und Klangfarbe in 0,1-s-Schritten
    const NF = 2048;
    const nC = Math.floor(mono.length / (sr / CFPS));
    const chroma = new Float32Array(nC * 12), bands = new Float32Array(nC * 6);
    const re2 = new Float32Array(NF), im2 = new Float32Array(NF), win2 = hann(NF);
    const binPc = new Int8Array(NF / 2), binBand = new Int8Array(NF / 2);
    const edges = [150, 400, 1000, 2500, 6000];
    for (let k = 1; k < NF / 2; k++) {
      const f = (k * sr) / NF;
      binPc[k] = f >= 55 && f <= 4200 ? mod(Math.round(12 * Math.log2(f / 440) + 69), 12) : -1;
      let b = 0;
      while (b < edges.length && f > edges[b]) b++;
      binBand[k] = b;
    }
    for (let c = 0; c < nC; c++) {
      const s = Math.floor((c * sr) / CFPS);
      for (let i = 0; i < NF; i++) { re2[i] = (mono[s + i] || 0) * win2[i]; im2[i] = 0; }
      fft(re2, im2);
      for (let k = 1; k < NF / 2; k++) {
        const m = Math.sqrt(re2[k] * re2[k] + im2[k] * im2[k]);
        if (binPc[k] >= 0) chroma[c * 12 + binPc[k]] += m;
        bands[c * 6 + binBand[k]] += m * m;
      }
      for (let b = 0; b < 6; b++) bands[c * 6 + b] = Math.log1p(bands[c * 6 + b]);
    }
    return { n, nC, env, flt, onL, rms, chroma, bands, duration: mono.length / sr };
  }

  /* ---------- 3. Tempo ---------- */
  // Autokorrelation der Einsatzkurve in 8-s-Fenstern (je Fenster normiert, dann gemittelt)
  function autocorr(env, a, b, maxLag) {
    const x = env.subarray(Math.max(0, a), Math.min(env.length, b));
    const n = x.length;
    const W = Math.min(8 * FPS, n);
    const w = hann(W);
    const acc = new Float64Array(maxLag + 1);
    const seg = new Float32Array(W);
    let cnt = 0;
    for (let s = 0; s + W <= n; s += FPS) {
      for (let i = 0; i < W; i++) seg[i] = x[s + i] * w[i];
      let r0 = 0;
      for (let i = 0; i < W; i++) r0 += seg[i] * seg[i];
      if (r0 <= 0) continue;
      for (let l = 1; l <= maxLag; l++) {
        let v = 0;
        for (let i = 0; i + l < W; i++) v += seg[i] * seg[i + l];
        acc[l] += v / r0;
      }
      cnt++;
    }
    if (cnt) for (let l = 0; l <= maxLag; l++) acc[l] /= cnt;
    return acc;
  }

  function tempoOf(F, start, end, quick = false) {
    const a = Math.floor(start * FPS), b = Math.floor(end * FPS);
    if (b - a < 4 * FPS) return null;
    const maxLag = Math.round((60 * FPS) / 40);
    const ac = autocorr(F.env, a, b, maxLag);
    const at = (lag) => { const l0 = Math.floor(lag), f = lag - l0; return l0 + 1 <= maxLag ? (1 - f) * ac[l0] + f * ac[l0 + 1] : 0; };
    const sdp = TEMPO.sd, comb = TEMPO.comb;
    let best = -1, bpm = 0;
    for (let x = 50; x <= 220; x += 0.25) {
      const lag = (60 * FPS) / x;
      const s = (at(lag) + comb * at(2 * lag)) * Math.exp(-0.5 * (Math.log2(x / TEMPO.center) / sdp) ** 2);
      if (s > best) { best = s; bpm = x; }
    }
    if (best <= 0) return null;
    // sehr schnelle Tempi: liegt der halbe Takt ebenso stark, zählen Tänzer den halben
    // Bass-Einsätze (Kick) markieren den Grundschlag; Hi-Hats auf den Zwischenschlägen nicht
    if (!quick && bpm > TEMPO.halfAbove) {
      const acL = autocorr(F.onL, a, b, maxLag);
      const atL = (lag) => { const l0 = Math.floor(lag), f = lag - l0; return l0 + 1 <= maxLag ? (1 - f) * acL[l0] + f * acL[l0 + 1] : 0; };
      if (atL((120 * FPS) / bpm) > TEMPO.halfRatio * atL((60 * FPS) / bpm)) bpm /= 2;
    }
    return bpm;
  }

  /* ---------- 4. Schläge verfolgen ---------- */
  function trackBeats(F, start, end, bpm, tight = 100) {
    const a = Math.max(0, Math.floor(start * FPS)), b = Math.min(F.n, Math.floor(end * FPS));
    const x = F.env.subarray(a, b);
    const n = x.length;
    const P = (60 * FPS) / bpm;
    if (n < P * 2) return [];
    const C = new Float32Array(n), back = new Int32Array(n).fill(-1);
    const lo = Math.round(P / 2), hi = Math.round(2 * P);
    const pen = new Float32Array(hi + 1);
    for (let d = lo; d <= hi; d++) pen[d] = tight * Math.log(d / P) ** 2;
    for (let t = 0; t < n; t++) {
      let bv = -Infinity, bi = -1;
      for (let d = lo; d <= hi && t - d >= 0; d++) {
        const v = C[t - d] - pen[d];
        if (v > bv) { bv = v; bi = t - d; }
      }
      if (bi >= 0 && bv > 0) { C[t] = x[t] + bv; back[t] = bi; } else C[t] = x[t];
    }
    let t = n - 1, m = -Infinity;
    for (let i = Math.max(0, n - hi); i < n; i++) if (C[i] > m) { m = C[i]; t = i; }
    const beats = [];
    while (t >= 0) { beats.push((a + t) / FPS); t = back[t]; }
    beats.reverse();
    // Schläge in der Stille am Anfang und Ende weglassen
    const loud = (sec) => {
      let s = 0;
      const i0 = Math.round(sec * FPS);
      for (let i = i0 - 5; i <= i0 + 5; i++) s = Math.max(s, F.rms[i] || 0);
      return s;
    };
    let maxR = 0;
    for (let i = a; i < b; i++) maxR = Math.max(maxR, F.rms[i]);
    const thr = maxR * 0.03;
    while (beats.length && loud(beats[0]) < thr) beats.shift();
    while (beats.length && loud(beats[beats.length - 1]) < thr) beats.pop();
    // auf die Zwischenschläge gerutscht? Der Bass (Kick) liegt auf dem Schlag, nicht dazwischen
    if (beats.length > 8) {
      const bassAt = (t) => { const i = Math.round(t * FPS); return Math.max(F.onL[i] || 0, F.onL[i + 1] || 0, F.onL[i - 1] || 0); };
      let on = 0, off = 0;
      for (let k = 0; k + 1 < beats.length; k++) { on += bassAt(beats[k]); off += bassAt((beats[k] + beats[k + 1]) / 2); }
      if (off > on * 1.3) {
        const shifted = [];
        for (let k = 0; k + 1 < beats.length; k++) shifted.push((beats[k] + beats[k + 1]) / 2);
        // Schlag vor dem ersten Zwischenschlag ergänzen, falls dort Musik ist
        const first = shifted[0] - (beats[1] - beats[0]);
        if (first >= start - 0.06 && loud(Math.max(start, first)) >= thr) shifted.unshift(Math.max(start, first));
        return shifted;
      }
    }
    return beats;
  }

  /* ---------- 5. Wo ist die „1“? ---------- */
  // Liefert Indizes in „beats“, an denen eine 1 liegt (ein Eintrag je Abschnitt zwischen Schnitten)
  function findOnes(F, beats) {
    if (beats.length < 2) return beats.length ? [0] : [];
    const ibi = [];
    for (let i = 1; i < beats.length; i++) ibi.push(beats[i] - beats[i - 1]);
    const med = median(ibi);
    // Schnitte: ein Schlagabstand weicht deutlich ab → dahinter beginnt ein neuer Abschnitt
    const starts = [0];
    for (let i = 1; i < beats.length; i++) {
      const r = (beats[i] - beats[i - 1]) / med;
      if ((r < 0.75 || r > 1.25) && i - starts[starts.length - 1] >= 8) starts.push(i);
    }
    const chromaAt = (t0, t1) => normalize(meanVec(F.chroma, 12, Math.round(t0 * CFPS), Math.round(t1 * CFPS)));
    const n = beats.length;
    const tb = (k) => (k < 0 ? beats[0] + k * med : k >= n ? beats[n - 1] + (k - n + 1) * med : beats[k]);
    const bass = beats.map((t) => {
      const i = Math.round(t * FPS);
      return Math.max(F.onL[i] || 0, F.onL[i + 1] || 0, F.onL[i - 1] || 0, F.onL[i + 2] || 0);
    });
    // Harmoniewechsel: Klang der w Schläge davor gegen die w Schläge danach
    const change = (w) => beats.map((t, k) => (k < 1 ? 0 : 1 - cos(chromaAt(tb(k - w), t), chromaAt(t, tb(k + w)))));
    const ch1 = change(1), ch2 = change(2), ch4 = change(4), ch8 = change(8);
    const strength = beats.map((t) => {
      const i = Math.round(t * FPS);
      return Math.max(F.env[i] || 0, F.env[i + 1] || 0, F.env[i - 1] || 0) + (F.onL[i] || 0);
    });
    const zs = (arr, s, e) => {
      let m = 0, q = 0;
      for (let k = s; k < e; k++) m += arr[k];
      m /= e - s;
      for (let k = s; k < e; k++) q += (arr[k] - m) ** 2;
      const sd = Math.sqrt(q / (e - s)) || 1;
      return (k) => (arr[k] - m) / sd;
    };
    // Bewertung, wie gut „Schlag s+j ist eine 1“ passt
    const score = (s, e) => {
      const zb = zs(bass, s, e), z1 = zs(ch1, s, e), z2 = zs(ch2, s, e), z4 = zs(ch4, s, e), z8 = zs(ch8, s, e);
      // Stufe 1: Taktanfang (alle 4 Schläge) – Bass-Einsatz und Harmoniewechsel
      const s4 = [0, 0, 0, 0], c4 = [0, 0, 0, 0];
      for (let k = s; k < e; k++) { const j = (k - s) % 4; s4[j] += zb(k) + z1(k) + z2(k) + z4(k); c4[j]++; }
      for (let j = 0; j < 4; j++) s4[j] /= c4[j] || 1;
      // Stufe 2: welcher der beiden Takte im Achter – größere Wechsel alle 8 Schläge
      const s8 = new Float32Array(8), c8 = new Float32Array(8);
      for (let k = s; k < e; k++) { const j = (k - s) % 8; s8[j] += z1(k) + z4(k) + z8(k) + 0.5 * zb(k); c8[j]++; }
      for (let j = 0; j < 8; j++) s8[j] /= c8[j] || 1;
      return { s4, s8 };
    };
    const ones = [];
    starts.forEach((s, si) => {
      const e = si + 1 < starts.length ? starts[si + 1] : n;
      const len = e - s;
      // nach einer vermuteten Schnittstelle: im Zweifel einfach weiterzählen
      const prev = ones[ones.length - 1];
      const jc = si > 0 ? mod(prev - s, 8) : null; // wo die 1 läge, wenn weitergezählt wird
      if (len < 16) { if (si === 0) ones.push(s); return; }
      const { s4, s8 } = score(s, e);
      // erster kräftiger Schlag – geschnittene Teile beginnen oft auf einer 1 (nur als leichter Hinweis)
      const medS = median(strength.slice(s, e));
      let first = s;
      while (first < Math.min(e, s + 8) && strength[first] < 0.6 * medS) first++;
      if (first >= s + 8) first = s;
      const fj = (first - s) % 8;
      // bei kurzen Liedern sind die Messwerte unsicher – dann zählt der Liedanfang mehr
      const bias = len < 48 ? 2 : 0.12;
      let j4 = 0;
      for (let j = 1; j < 4; j++) if (s4[j] + (j === fj % 4 ? bias : 0) > s4[j4] + (j4 === fj % 4 ? bias : 0)) j4 = j;
      const a1 = j4, a2 = j4 + 4;
      const v = (j) => s8[j] + (j === fj ? bias : 0);
      const bj = v(a1) >= v(a2) ? a1 : a2;
      if (si > 0) {
        // nur neu anfangen, wenn die Musik deutlich eine andere 1 zeigt und der Abschnitt lang ist
        const sameBar = jc % 4 === bj % 4;
        const margin = sameBar ? s8[bj] - s8[jc] : s4[bj % 4] - s4[jc % 4];
        if (bj === jc || len < 32 || margin < 0.35) return;
      }
      ones.push(s + bj);
    });
    return ones;
  }

  /* ---------- 2. Lieder finden ---------- */
  const TG_BPMS = [];
  for (let b = 60; b <= 200; b *= 1.035) TG_BPMS.push(b);
  function tempogram(env, from, to) {
    const out = new Float32Array(TG_BPMS.length);
    from = Math.max(0, from); to = Math.min(env.length, to);
    if (to - from < 10) return out;
    let m = 0;
    for (let i = from; i < to; i++) m += env[i];
    m /= to - from;
    const x = new Float32Array(to - from);
    for (let i = from; i < to; i++) x[i - from] = env[i] - m;
    TG_BPMS.forEach((bpm, k) => {
      const lag = Math.round((60 * FPS) / bpm);
      let s = 0;
      for (let i = 0; i + lag < x.length; i++) s += x[i] * x[i + lag];
      out[k] = Math.max(0, s / Math.max(1, x.length - lag));
    });
    return out;
  }

  function blockFeat(F, from, to) {
    const ch = normalize(meanVec(F.chroma, 12, from, to));
    const bd = meanVec(F.bands, 6, from, to);
    const m = bd.reduce((p, q) => p + q, 0) / 6;
    const tg = normalize(tempogram(F.flt, Math.round((from / CFPS) * FPS), Math.round((to / CFPS) * FPS)));
    return { ch, bd: normalize(bd.map((x) => x - m)), tg };
  }
  const blockDist = (A, B) => (1 - cos(A.ch, B.ch)) + 0.8 * (1 - cos(A.bd, B.bd)) + (1 - cos(A.tg, B.tg));

  function segmentBoundaries(F, opts = {}) {
    const minLen = opts.minLen ?? 15;
    const N = Math.floor(F.duration);
    if (N < minLen * 2) return [{ t: 0, v: Infinity }];
    const vecs = [];
    const loud = new Float32Array(N);
    for (let t = 0; t < N; t++) {
      const ch = normalize(meanVec(F.chroma, 12, t * CFPS - 10, t * CFPS + 10));
      const bd = meanVec(F.bands, 6, t * CFPS - 10, t * CFPS + 10);
      const bdn = normalize(bd.map((x) => x - bd.reduce((p, q) => p + q, 0) / 6));
      const tg = normalize(tempogram(F.flt, (t - 4) * FPS, (t + 4) * FPS));
      let r = 0;
      for (let i = t * FPS; i < (t + 1) * FPS && i < F.n; i++) r += F.rms[i] * F.rms[i];
      loud[t] = 10 * Math.log10(r / FPS + 1e-10);
      const v = new Float32Array(12 + 6 + tg.length);
      v.set(ch, 0);
      v.set(bdn.map((x) => x * 0.8), 12);
      v.set(tg, 18);
      vecs.push(normalize(v));
    }
    const L = 8;
    const nov = new Float32Array(N);
    for (let t = L; t < N - L; t++) {
      let s = 0, wsum = 0;
      for (let i = -L; i < L; i++) {
        for (let j = -L; j < L; j++) {
          const w = Math.exp(-((i + 0.5) ** 2 + (j + 0.5) ** 2) / (2 * (L / 2) ** 2));
          s += ((i < 0) === (j < 0) ? 1 : -1) * w * cos(vecs[t + i], vecs[t + j]);
          wsum += w;
        }
      }
      nov[t] = Math.max(0, s / wsum);
    }
    const maxLoud = Math.max(...loud);
    const mean = nov.reduce((a, b) => a + b, 0) / N;
    const sd = Math.sqrt(nov.reduce((a, b) => a + (b - mean) ** 2, 0) / N) || 1;
    const cands = [];
    for (let t = 1; t < N - 1; t++) {
      if (loud[t - 1] < maxLoud - 35 && loud[t] >= maxLoud - 35) cands.push({ t, v: 10 });
    }
    // kurze Einbrüche (z. B. Schnitt mit kurzer Pause) in 10-ms-Auflösung
    const db = (i) => 20 * Math.log10((F.rms[i] || 0) + 1e-9);
    let typical = [];
    for (let i = 0; i < F.n; i += 50) typical.push(db(i));
    typical = typical.sort((a, b) => a - b)[Math.floor(typical.length * 0.6)];
    for (let i = 20; i < F.n - 20; i += 5) {
      let m = 0;
      for (let k = -10; k <= 10; k++) m += db(i + k);
      m /= 21;
      if (m < typical - 22) {
        let j = i;
        while (j < F.n - 1 && db(j) < typical - 12) j++;
        cands.push({ t: Math.round(j / FPS), v: 2, kind: 'dip' });
        i = j + 100;
      }
    }
    // Tempowechsel: lokales Tempo vorher/nachher vergleichen
    const lt = new Float32Array(N);
    for (let t = 4; t < N - 4; t++) lt[t] = tempoOf(F, t - 4, t + 4, true) || 0;
    const lr = new Float32Array(N);
    for (let t = 12; t < N - 12; t++) {
      const before = median(lt.subarray(t - 11, t - 1).filter((x) => x > 0));
      const after = median(lt.subarray(t + 2, t + 12).filter((x) => x > 0));
      if (!before || !after) continue;
      const r = Math.abs(Math.log2(after / before));
      if (Math.abs(r - 1) < 0.05) continue; // nur Verdopplung: gleiches Lied, anders gezählt
      lr[t] = r;
    }
    for (let t = 12; t < N - 12; t++) {
      if (lr[t] < 0.06) continue;
      let isMax = true;
      for (let k = t - 8; isMax && k <= t + 8; k++) if (lr[k] > lr[t]) isMax = false;
      if (isMax) cands.push({ t, v: 3 + lr[t], kind: 'tempo' });
    }
    const thr = mean + (opts.sensitivity ?? 1.0) * sd;
    for (let t = L; t < N - L; t++) {
      let isMax = nov[t] > thr;
      for (let k = Math.max(0, t - 6); isMax && k <= Math.min(N - 1, t + 6); k++) if (nov[k] > nov[t]) isMax = false;
      if (isMax) cands.push({ t, v: (nov[t] - mean) / sd });
    }
    cands.sort((a, b) => b.v - a.v);
    const chosen = [];
    for (const c of cands) {
      if (c.t < minLen * 0.7 || c.t > N - minLen * 0.7) continue;
      if (chosen.every((x) => Math.abs(x.t - c.t) >= minLen)) chosen.push(c);
    }
    chosen.sort((a, b) => a.t - b.t);
    return [{ t: 0, v: Infinity }, ...chosen.map((c) => ({ t: refineBoundary(F, c.t), v: c.v, kind: c.kind || 'novelty' }))];
  }

  // Grenze genauer legen: grob (4-s-Blöcke), fein (1,5-s-Blöcke), dann auf den Einsatz
  function refineBoundary(F, t) {
    const search = (center, radius, W) => {
      let best = center, bv = -Infinity;
      for (let c = Math.max(W, Math.round((center - radius) * CFPS)); c <= Math.min(F.nC - W, Math.round((center + radius) * CFPS)); c++) {
        const d = blockDist(blockFeat(F, c - W, c), blockFeat(F, c, c + W));
        if (d > bv) { bv = d; best = c / CFPS; }
      }
      return best;
    };
    let b = search(t, 6, 4 * CFPS);
    b = search(b, 2.5, Math.round(1.5 * CFPS));
    let bi = Math.round(b * FPS), biv = -1;
    for (let i = Math.max(1, Math.round((b - 0.35) * FPS)); i < Math.min(F.n, Math.round((b + 0.35) * FPS)); i++) {
      const w = 1 - Math.abs(i / FPS - b) / 0.7;
      const v = (F.env[i] + F.onL[i]) * w;
      if (v > biv) { biv = v; bi = i; }
    }
    return bi / FPS;
  }

  /* ---------- Gesamt ---------- */
  // Analysiert einen Bereich: Tempo, Schläge, Einsen
  function analyseRange(F, start, end, bpm = null) {
    const tempo = bpm || tempoOf(F, start, end);
    if (!tempo) return { beats: [], ones: [] };
    const beats = trackBeats(F, start, end, tempo);
    return { beats, ones: findOnes(F, beats) };
  }

  function songsFromFeatures(F, opts = {}) {
    let bounds = opts.single ? [{ t: 0, v: Infinity }] : segmentBoundaries(F, opts);
    const tempos = bounds.map((b, i) => tempoOf(F, b.t, i + 1 < bounds.length ? bounds[i + 1].t : F.duration));
    // Teile mit gleichem Tempo und schwacher Grenze sind dasselbe Lied (z. B. Strophe → Refrain)
    const keep = [0];
    for (let i = 1; i < bounds.length; i++) {
      const prev = keep[keep.length - 1];
      const same = tempos[i] && tempos[prev] && Math.abs(tempos[i] - tempos[prev]) / tempos[prev] < 0.02;
      if (same && (bounds[i].kind !== 'novelty' || bounds[i].v < 2.2)) continue;
      keep.push(i);
    }
    bounds = keep.map((i) => bounds[i]);
    const segments = [], beats = [], ones = [];
    bounds.forEach((b, i) => {
      const end = i + 1 < bounds.length ? bounds[i + 1].t : F.duration;
      const r = analyseRange(F, b.t, end);
      // letzter Schlag nicht zu nah an das nächste Lied
      const minGap = r.beats.length > 1 ? 0.5 * (r.beats[1] - r.beats[0]) : 0;
      while (r.beats.length && i + 1 < bounds.length && end - r.beats[r.beats.length - 1] < minGap) r.beats.pop();
      const off = beats.length;
      segments.push({ start: Math.round(b.t * 1000) / 1000, bpm: bpmOfBeats(r.beats) });
      for (const t of r.beats) beats.push(Math.round(t * 1000) / 1000);
      for (const k of r.ones) if (k < r.beats.length) ones.push(off + k);
    });
    return { segments, beats, ones };
  }

  function bpmOfBeats(beats) {
    if (beats.length < 4) return null;
    const ibi = [];
    for (let i = 1; i < beats.length; i++) ibi.push(beats[i] - beats[i - 1]);
    const v = 60 / median(ibi);
    return Math.abs(v - Math.round(v)) < 0.25 ? Math.round(v) : Math.round(v * 10) / 10;
  }

  function analyse(mono, sr, opts = {}) {
    const F = features(mono, sr, opts.onProgress);
    return { ...songsFromFeatures(F, opts), F };
  }

  // Für den Hintergrund-Thread (Web Worker): derselbe Code als Text
  const workerSource = () => `(${NarrhallaAnalysis.toString()})(self);
const bufs = (F) => [F.env.buffer, F.flt.buffer, F.onL.buffer, F.rms.buffer, F.chroma.buffer, F.bands.buffer];
self.onmessage = (e) => {
  const { id, op, mono, sr, F, start, end, bpm } = e.data;
  const A = self.Analysis;
  try {
    if (op === 'analyse') {
      const r = A.analyse(mono, sr, { onProgress: (p) => self.postMessage({ id, progress: p }) });
      self.postMessage({ id, result: r }, bufs(r.F));
    } else if (op === 'features') {
      const G = A.features(mono, sr, (p) => self.postMessage({ id, progress: p }));
      self.postMessage({ id, result: { F: G } }, bufs(G));
    } else if (op === 'songs') {
      const r = A.songsFromFeatures(F);
      self.postMessage({ id, result: { ...r, F } }, bufs(F));
    } else if (op === 'range') {
      const r = A.analyseRange(F, start, end, bpm || null);
      self.postMessage({ id, result: { ...r, F } }, bufs(F));
    }
  } catch (err) {
    self.postMessage({ id, error: String(err && err.message || err) });
  }
};`;

  const api = { TEMPO, workerSource, analyse, songsFromFeatures, analyseRange, features, tempoOf, trackBeats, findOnes, bpmOfBeats, segmentBoundaries, fft, FPS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Analysis = api;
}
NarrhallaAnalysis(typeof window !== 'undefined' ? window : globalThis);
