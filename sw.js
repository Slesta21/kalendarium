/* Kalendarium Service Worker 202610192232: App offline verfügbar, Benachrichtigungen */
const CACHE = 'kalendarium-202610192232';
const SHELL = ['./', './index.html', './app.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  const u = new URL(e.request.url);
  /* Start sofort aus dem Speicher: Seite und Skript gehören zur selben Version. Neue Versionen kommen über ein neues sw.js */
  if (u.origin === location.origin && (e.request.mode === 'navigate' || u.pathname.endsWith('/app.js')) && e.request.cache !== 'no-store') {
    const key = e.request.mode === 'navigate' ? './index.html' : './app.js';
    e.respondWith(caches.open(CACHE).then(c => c.match(key)).then(r => r || fetch(e.request).then(res => { if (res.ok) { const c2 = res.clone(); caches.open(CACHE).then(x => x.put(key, c2)); } return res; })).catch(() => caches.match(key, {ignoreSearch:true})));
    return;
  }
  if (u.origin === location.origin) {
    e.respondWith(fetch(e.request, {cache:'no-store'}).then(r => { if (r.ok) { const c = r.clone(); caches.open(CACHE).then(x => x.put(e.request, c)); } return r; })
      .catch(() => caches.match(e.request, {ignoreSearch:true}).then(r => r || caches.match('./index.html'))));
  } else if (/fonts\.(googleapis|gstatic)\.com$/.test(u.hostname)) {
    e.respondWith(caches.match(e.request).then(r => r || fetch(e.request).then(res => { const c = res.clone(); caches.open(CACHE).then(x => x.put(e.request, c)); return res; })));
  }
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:'window', includeUncontrolled:true}).then(cs => cs.length ? cs[0].focus() : self.clients.openWindow('./')));
});
