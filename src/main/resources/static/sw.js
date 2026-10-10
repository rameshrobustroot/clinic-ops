const CACHE_NAME = 'clinic-ops-shell-v4';
const SHELL_ASSETS = [
  '/css/app.css',
  '/js/app.js',
  '/vendor/tailwind-3.4.16.min.js',
  '/vendor/htmx-1.9.12.min.js',
  '/vendor/alpine-3.14.1.min.js',
  '/manifest.webmanifest',
  '/icons/icon-192.svg',
  '/icons/icon-512.svg'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) =>
      // Don't fail the install if a single asset is unreachable
      Promise.all(SHELL_ASSETS.map((u) => cache.add(u).catch(() => {})))
    )
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Network-first for static assets (with cache fallback when offline).
// This prevents the SW from getting stuck serving a stale/error response
// that was cached during a previous broken state.
self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (!(url.pathname.startsWith('/css/') || url.pathname.startsWith('/js/')
        || url.pathname.startsWith('/vendor/') || url.pathname.startsWith('/icons/'))) {
    return;
  }

  event.respondWith(
    fetch(req).then((res) => {
      // Only cache successful responses; never cache 3xx redirects.
      if (res.ok && res.type === 'basic') {
        const copy = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
      }
      return res;
    }).catch(() => caches.match(req))
  );
});
