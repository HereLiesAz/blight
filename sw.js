const CACHE_NAME = 'nwc-v1.1';
const ASSETS = [
    './',
    './index.html',
    './manifest.json',
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
    'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
    'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
    'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png'
];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => {
            return cache.addAll(ASSETS);
        })
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames.map((cache) => {
                    if (cache !== CACHE_NAME) {
                        return caches.delete(cache);
                    }
                })
            );
        })
    );
});

self.addEventListener('fetch', (event) => {
    const url = event.request.url;

    if (url.includes('cartocdn.com') || url.includes('githubusercontent.com') || url.includes('iconify.design')) {
        event.respondWith(
            caches.match(event.request).then(res => 
                res || fetch(event.request).then(netRes => 
                    caches.open(CACHE_NAME).then(cache => { 
                        cache.put(event.request, netRes.clone()); 
                        return netRes; 
                    })
                )
            )
        );
        return; 
    }

    if (url.includes('index.html') || url === self.location.origin + '/') {
        event.respondWith(
            fetch(event.request).catch(() => caches.match(event.request))
        );
    } else {
        event.respondWith(
            caches.match(event.request).then((response) => {
                return response || fetch(event.request);
            })
        );
    }
});
