/* Narrhalla Player – Audio-Analyse
 * Erkennt in einer Datei (z. B. einem fertig geschnittenen Medley) die einzelnen Lieder
 * und für jedes Lied Tempo und die „1“ der Tanz-Zählung (1–8).
 * Reine Funktionen auf einem Mono-Signal; läuft im Browser und in Node (für Tests).
 */
(function (root) {
  'use strict';

  const FPS = 100;   // Auflösung der Einsatz-Kurve (Frames pro Sekunde)
  const CFPS = 10;   // Auflösung der Klang-Merkmale (Frames pro Sekunde)
  const NFFT = 2048;

  /* ---------- FFT (radix 2, in place) ---------- */
  function fft(re, im) {
    const n = re.length;
    for (let i = 1, j = 0; i < n; i++) {
      let bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const ang = (-2 * Math.PI) / len;
      const wr = Math.cos(ang), wi = Math.sin(ang);
      for (let i = 0; i < n; i += len) {
        let cr = 1, ci = 0;
        for (let k = 0; k < len / 2; k++) {
          const a = i + k, b = a + len / 2;
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

  /* ---------- Merkmale ---------- */
  function features(mono, sr) {
    const hop = sr / FPS;
    const n = Math.floor(mono.length / hop);
    const low = new Float32Array(n), full = new Float32Array(n), rms = new Float32Array(n);
    const a = 1 - Math.exp((-2 * Math.PI * 150) / sr);
    let lp = 0;
    for (let i = 0; i < n; i++) {
      let el = 0, ef = 0;
      const s = Math.floor(i * hop), e = Math.floor((i + 1) * hop);
      for (let j = s; j < e; j++) { const x = mono[j]; lp += a * (x - lp); el += lp * lp; ef += x * x; }
      rms[i] = Math.sqrt(ef / (e - s));
      low[i] = Math.log1p(1000 * Math.sqrt(el / (e - s)));
      full[i] = Math.log1p(1000 * rms[i]);
    }
    const onL = new Float32Array(n), on = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      // vor dem ersten Frame ist Stille: ein Einsatz ganz am Anfang zählt mit
      onL[i] = Math.max(0, low[i] - (i ? low[i - 1] : 0));
      on[i] = 1.5 * onL[i] + Math.max(0, full[i] - (i ? full[i - 1] : 0));
    }
    // gleitenden Mittelwert abziehen: übrig bleiben die Einsätze
    const W = 25;
    const flt = new Float32Array(n);
    let acc = 0;
    for (let i = 0; i < n + W; i++) {
      if (i < n) acc += on[i];
      if (i >= 2 * W + 1) acc -= on[i - 2 * W - 1];
      const c = i - W;
      if (c >= 0 && c < n) {
        const cnt = Math.min(i, n - 1) - Math.max(0, i - 2 * W) + 1;
        flt[c] = Math.max(0, on[c] - acc / cnt);
      }
    }

    // Klangfarbe und Harmonie (Chroma) in 0,1-s-Schritten
    const nC = Math.floor(mono.length / (sr / CFPS));
    const chroma = new Float32Array(nC * 12);
    const bands = new Float32Array(nC * 6);
    const re = new Float32Array(NFFT), im = new Float32Array(NFFT);
    const win = new Float32Array(NFFT);
    for (let i = 0; i < NFFT; i++) win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (NFFT - 1));
    const binPc = new Int8Array(NFFT / 2), binBand = new Int8Array(NFFT / 2);
    const edges = [150, 400, 1000, 2500, 6000];
    for (let k = 1; k < NFFT / 2; k++) {
      const f = (k * sr) / NFFT;
      binPc[k] = f >= 55 && f <= 4200 ? mod(Math.round(12 * Math.log2(f / 440) + 69), 12) : -1;
      let b = 0;
      while (b < edges.length && f > edges[b]) b++;
      binBand[k] = b;
    }
    for (let c = 0; c < nC; c++) {
      const s = Math.floor((c * sr) / CFPS);
      for (let i = 0; i < NFFT; i++) { re[i] = (mono[s + i] || 0) * win[i]; im[i] = 0; }
      fft(re, im);
      for (let k = 1; k < NFFT / 2; k++) {
        const m = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
        if (binPc[k] >= 0) chroma[c * 12 + binPc[k]] += m;
        bands[c * 6 + binBand[k]] += m * m;
      }
      for (let b = 0; b < 6; b++) bands[c * 6 + b] = Math.log1p(bands[c * 6 + b]);
    }
    return { n, nC, onL, flt, rms, chroma, bands, duration: mono.length / sr };
  }

  /* ---------- Hilfsfunktionen ---------- */
  function mod(a, n) { return ((a % n) + n) % n; }
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

  // Autokorrelation der Einsatzkurve für einen Bereich → Stärke je Tempo
  const TG_BPMS = [];
  for (let b = 60; b <= 200; b *= 1.035) TG_BPMS.push(b);
  function tempogram(flt, from, to) {
    const out = new Float32Array(TG_BPMS.length);
    from = Math.max(0, from); to = Math.min(flt.length, to);
    TG_BPMS.forEach((bpm, k) => {
      const lag = Math.round((60 * FPS) / bpm);
      let s = 0;
      for (let i = from; i + lag < to; i++) s += flt[i] * flt[i + lag];
      out[k] = s / Math.max(1, to - from - lag);
    });
    return out;
  }

  /* ---------- Lieder innerhalb der Datei finden ---------- */
  function segmentBoundaries(F, opts = {}) {
    const minLen = opts.minLen ?? 15;          // kürzestes Lied in Sekunden
    const N = Math.floor(F.duration);           // 1-s-Frames
    if (N < minLen * 2) return [0];
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
      v.set(ch.map((x) => x * 1.0), 0);
      v.set(bdn.map((x) => x * 0.8), 12);
      v.set(tg.map((x) => x * 1.0), 18);
      vecs.push(normalize(v));
    }
    // Neuheitskurve (Foote): Ähnlichkeit vorher/nachher gegen Übergreifend
    const L = 8;
    const nov = new Float32Array(N);
    for (let t = L; t < N - L; t++) {
      let s = 0, wsum = 0;
      for (let i = -L; i < L; i++) {
        for (let j = -L; j < L; j++) {
          const w = Math.exp(-((i + 0.5) ** 2 + (j + 0.5) ** 2) / (2 * (L / 2) ** 2));
          const same = (i < 0) === (j < 0) ? 1 : -1;
          s += same * w * cos(vecs[t + i], vecs[t + j]);
          wsum += w;
        }
      }
      nov[t] = Math.max(0, s / wsum);
    }
    // Stille-Lücken sind sichere Grenzen
    const maxLoud = Math.max(...loud);
    const cands = [];
    for (let t = 1; t < N - 1; t++) {
      if (loud[t - 1] < maxLoud - 35 && loud[t] >= maxLoud - 35) cands.push({ t, v: 10 });
    }
    const mean = nov.reduce((a, b) => a + b, 0) / N;
    const sd = Math.sqrt(nov.reduce((a, b) => a + (b - mean) ** 2, 0) / N);
    const thr = mean + (opts.sensitivity ?? 1.0) * sd;
    for (let t = L; t < N - L; t++) {
      let isMax = nov[t] > thr;
      for (let k = Math.max(0, t - 6); isMax && k <= Math.min(N - 1, t + 6); k++) if (nov[k] > nov[t]) isMax = false;
      if (isMax) cands.push({ t, v: nov[t] });
    }
    cands.sort((a, b) => b.v - a.v);
    const chosen = [];
    for (const c of cands) {
      if (c.t < minLen * 0.7 || c.t > N - minLen * 0.7) continue;
      if (chosen.every((x) => Math.abs(x - c.t) >= minLen)) chosen.push(c.t);
    }
    chosen.sort((a, b) => a - b);
    return [0, ...chosen.map((t) => refineBoundary(F, t))];
  }

  // Grenze genauer legen: Stelle mit dem größten Unterschied zwischen den 4 s davor und danach
  // (Harmonie, Klangfarbe, Rhythmus), danach auf den stärksten Einsatz in der Nähe.
  function refineBoundary(F, t) {
    const W = 4 * CFPS;
    const block = (from, to) => {
      const ch = normalize(meanVec(F.chroma, 12, from, to));
      const bd = meanVec(F.bands, 6, from, to);
      const m = bd.reduce((p, q) => p + q, 0) / 6;
      const tg = normalize(tempogram(F.flt, (from / CFPS) * FPS, (to / CFPS) * FPS));
      return { ch, bd: normalize(bd.map((x) => x - m)), tg };
    };
    let best = t, bv = -Infinity;
    for (let c = Math.max(W, (t - 6) * CFPS); c <= Math.min(F.nC - W, (t + 6) * CFPS); c += 2) {
      const A = block(c - W, c), B = block(c, c + W);
      const d = (1 - cos(A.ch, B.ch)) + 0.8 * (1 - cos(A.bd, B.bd)) + (1 - cos(A.tg, B.tg));
      if (d > bv) { bv = d; best = c / CFPS; }
    }
    let bi = Math.round(best * FPS), biv = -1;
    for (let i = Math.max(1, Math.round((best - 0.6) * FPS)); i < Math.min(F.n, Math.round((best + 0.6) * FPS)); i++) {
      const w = 1 - Math.abs(i / FPS - best) / 1.2;
      const v = (F.onL[i] * 2 + F.flt[i]) * w;
      if (v > biv) { biv = v; bi = i; }
    }
    return bi / FPS;
  }

  /* ---------- Tempo und „1“ eines Abschnitts ---------- */
  function detectTempo(F, start, end) {
    const a = Math.max(0, Math.floor(start * FPS)), b = Math.min(F.n, Math.floor(end * FPS));
    const n = b - a;
    if (n < FPS * 6) return null;
    const flt = F.flt.subarray(a, b), onL = F.onL.subarray(a, b);

    const score = (lag) => {
      let s = 0;
      for (let i = 0; i + lag < n; i++) s += flt[i] * flt[i + lag];
      return s / (n - lag);
    };
    let best = 0, bestBpm = 0;
    for (let bpm = 60; bpm <= 200; bpm += 0.5) {
      const lag = (60 * FPS) / bpm;
      const l0 = Math.floor(lag), f = lag - l0;
      const r = (1 - f) * score(l0) + f * score(l0 + 1);
      const r2 = lag * 2 < n / 2 ? score(Math.round(lag * 2)) : 0;
      const r4 = lag * 4 < n / 2 ? score(Math.round(lag * 4)) : 0;
      const prior = Math.exp(-0.5 * (Math.log2(bpm / 118) / 0.5) ** 2);
      const s = (r + 0.5 * r2 + 0.25 * r4) * prior;
      if (s > best) { best = s; bestBpm = bpm; }
    }
    if (!bestBpm) return null;

    const at = (x) => { const i = Math.floor(x); const f = x - i; return i >= 0 && i + 1 < n ? flt[i] * (1 - f) + flt[i + 1] * f : 0; };
    const phaseFit = (bpm) => {
      const P = (60 * FPS) / bpm;
      let bs = -1, bp = 0;
      for (let ph = 0; ph < P; ph += 0.5) {
        let s = 0;
        for (let x = ph; x < n; x += P) s += at(x) + 0.5 * (at(x - 1) + at(x + 1));
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
    bpm = Math.abs(bpm - Math.round(bpm)) < 0.12 ? Math.round(bpm) : Math.round(bpm * 10) / 10;
    const P = (60 * FPS) / bpm;
    let ph = phaseFit(bpm).ph;
    if (ph > P - 3) ph -= P; // Schlag direkt am Abschnittsbeginn nicht verlieren

    // Welche der acht Zählzeiten ist die „1“?
    //  a) Bass-Einsatz und Harmoniewechsel wiederholen sich alle 8 Schläge
    //  b) geschnittene Lieder beginnen fast immer auf einer „1“
    const beats = [];
    for (let x = ph; x < n - 1; x += P) beats.push(x);
    const chromaAt = (frame) => {
      const t = (a + frame) / FPS;
      return normalize(meanVec(F.chroma, 12, Math.round(t * CFPS), Math.round((t + P / FPS) * CFPS)));
    };
    const acc = new Float32Array(8);
    let prev = null;
    let maxOn = 1e-9;
    for (const x of beats) maxOn = Math.max(maxOn, onL[Math.round(x)] || 0);
    beats.forEach((x, k) => {
      const i = Math.round(x);
      const bass = Math.max(onL[i] || 0, onL[i + 1] || 0, onL[i - 1] || 0) / maxOn;
      const ch = chromaAt(x);
      const change = prev ? 1 - cos(ch, prev) : 0;
      prev = ch;
      acc[k % 8] += bass + 2 * change;
    });
    const total = acc.reduce((p, q) => p + q, 0) || 1;
    // erster deutlicher Schlag nach Abschnittsbeginn
    let firstK = 0;
    for (let k = 0; k < Math.min(beats.length, 8); k++) {
      const i = Math.round(beats[k]);
      if ((flt[i] || 0) + (flt[i + 1] || 0) > 0.3 * maxOn) { firstK = k; break; }
    }
    let bj = 0, bjs = -Infinity;
    for (let j = 0; j < 8; j++) {
      const s = acc[j] / total + (j === firstK % 8 ? 0.12 : 0) + (j === mod(firstK + 4, 8) ? 0.03 : 0);
      if (s > bjs) { bjs = s; bj = j; }
    }
    const offset = (a + ph + bj * P) / FPS;
    return { bpm, beatOffset: Math.round(offset * 1000) / 1000, confidence: fine.s / Math.max(1, beats.length) };
  }

  /* ---------- Gesamtanalyse ---------- */
  function analyse(mono, sr, opts = {}) {
    const F = features(mono, sr);
    return { segments: songsFromFeatures(F, opts), F };
  }

  function songsFromFeatures(F, opts = {}) {
    const starts = opts.single ? [0] : segmentBoundaries(F, opts);
    const detect = (start, end) => {
      const tempo = detectTempo(F, start, end);
      return { start: Math.round(start * 1000) / 1000, bpm: tempo?.bpm ?? null, beatOffset: tempo?.beatOffset ?? start };
    };
    let segs = starts.map((start, i) => detect(start, i + 1 < starts.length ? starts[i + 1] : F.duration));
    // Teile mit gleichem Tempo und durchlaufendem Raster sind dasselbe Lied (z. B. Strophe → Refrain)
    for (let i = 1; i < segs.length; i++) {
      const A = segs[i - 1], B = segs[i];
      if (!A.bpm || !B.bpm || Math.abs(A.bpm - B.bpm) / A.bpm > 0.012) continue;
      const b = 60 / A.bpm;
      const d = (B.beatOffset - A.beatOffset) / b;
      if (Math.abs(d - Math.round(d)) * b > 0.035) continue;
      const end = i + 1 < segs.length ? segs[i + 1].start : F.duration;
      segs.splice(i - 1, 2, detect(A.start, end));
      i = 0;
    }
    return segs;
  }

  const api = { analyse, songsFromFeatures, features, segmentBoundaries, detectTempo, fft, FPS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Analysis = api;
})(typeof window !== 'undefined' ? window : globalThis);
