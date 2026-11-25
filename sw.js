/* Service Worker: macht die App offline nutzbar.
 * Erst aus dem Netz laden (damit Updates sofort und vollständig ankommen),
 * ohne Netz aus dem Zwischenspeicher. Bei Änderungen die Versionsnummer erhöhen. */
const CACHE = 'narrhalla-v9';
const FILES = ['./', 'index.html', 'style.css', 'app.js', 'analysis.js', 'manifest.webmanifest',
  'voices/de-frau.js', 'voices/de-frau2.js', 'voices/de-mann.js', 'voices/en-frau.js', 'voices/en-mann.js',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE)
    .then((c) => Promise.all(FILES.map((f) => fetch(f, { cache: 'reload' }).then((r) => { if (r.ok) return c.put(f, r); }))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 4000);
      const res = await fetch(e.request, { signal: ctrl.signal, cache: 'no-cache' });
      clearTimeout(timer);
      if (res.ok) cache.put(e.request, res.clone());
      return res;
    } catch (err) {
      const hit = await cache.match(e.request, { ignoreSearch: true });
      if (hit) return hit;
      throw err;
    }
  })());
});
