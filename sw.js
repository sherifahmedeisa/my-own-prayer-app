/* Offline-first Service Worker for Prayer Times */
const CACHE_NAME = 'prayer-times-v4';
const TILE_CACHE_NAME = 'map-tiles-v1';
const APP_SHELL = [
  './',
  'index.html',
  'leaflet.css',
  'leaflet.js',
  'mosques.json',
  'manifest.webmanifest',
  'icon-192.png',
  'icon-512.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(APP_SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(key => key !== CACHE_NAME && key !== TILE_CACHE_NAME).map(key => caches.delete(key)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // Allow app's own data engine to manage API caching
  if (url.hostname.includes('ummahapi.com') || url.hostname.includes('project-osrm.org')) return;

  // Cache Map Tiles (CartoDB / OpenStreetMap) for offline navigation
  if (url.hostname.includes('tile.openstreetmap.org') || url.hostname.includes('basemaps.cartocdn.com')) {
    event.respondWith(
      caches.open(TILE_CACHE_NAME).then(async cache => {
        const cached = await cache.match(req);
        if (cached) return cached;
        try {
          const networkRes = await fetch(req);
          if (networkRes && networkRes.status === 200) {
            cache.put(req, networkRes.clone());
          }
          return networkRes;
        } catch {
          return cached || new Response('', { status: 408 });
        }
      })
    );
    return;
  }

  // Cache Google Fonts
  if (url.hostname.includes('fonts.googleapis.com') || url.hostname.includes('fonts.gstatic.com')) {
    event.respondWith(
      caches.open(CACHE_NAME).then(async cache => {
        const cached = await cache.match(req);
        if (cached) return cached;
        try {
          const networkRes = await fetch(req);
          cache.put(req, networkRes.clone());
          return networkRes;
        } catch {
          return cached;
        }
      })
    );
    return;
  }

  // App shell resources: cache-first with network fallback
  if (url.origin === location.origin) {
    event.respondWith(
      caches.match(req, { ignoreSearch: true }).then(cached => {
        if (cached) {
          // background refresh
          fetch(req).then(networkRes => {
            if (networkRes && networkRes.status === 200) {
              caches.open(CACHE_NAME).then(cache => cache.put(req, networkRes));
            }
          }).catch(() => {});
          return cached;
        }
        return fetch(req);
      })
    );
  }
});
