const CACHE_NAME = 'nwc-v1.5';
const ASSETS = [
    './', './index.html', './manifest.json',
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
    'https://unpkg.com/leaflet.heat/dist/leaflet-heat.js'
];

self.addEventListener('install', (e) => e.waitUntil(caches.open(CACHE_NAME).then((c) => c.addAll(ASSETS))));
self.addEventListener('activate', (e) => e.waitUntil(caches.keys().then((n) => Promise.all(n.map((c) => { if (c !== CACHE_NAME) return caches.delete(c); })))));

self.addEventListener('fetch', (e) => {
    const url = e.request.url;
    if (url.includes('cartocdn.com') || url.includes('arcgisonline.com') || url.includes('unpkg.com')) {
        e.respondWith(caches.match(e.request).then(res => res || fetch(e.request).then(netRes => caches.open(CACHE_NAME).then(cache => { cache.put(e.request, netRes.clone()); return netRes; }))));
        return; 
    }
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
});
