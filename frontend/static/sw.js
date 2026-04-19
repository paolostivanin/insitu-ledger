const CACHE_NAME = 'insitu-v1';

self.addEventListener('install', (event) => {
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

// Drop cached /api/ responses on demand (logout, 401). Static assets are kept.
self.addEventListener('message', (event) => {
	if (event.data?.type !== 'clear-api-cache') return;
	event.waitUntil(
		caches.open(CACHE_NAME).then(async (cache) => {
			const keys = await cache.keys();
			await Promise.all(
				keys
					.filter((req) => new URL(req.url).pathname.startsWith('/api/'))
					.map((req) => cache.delete(req))
			);
		})
	);
});

self.addEventListener('fetch', (event) => {
	const url = new URL(event.request.url);

	// Never cache non-GET requests
	if (event.request.method !== 'GET') return;

	// Cache-first for hashed static assets
	if (url.pathname.startsWith('/_app/immutable/')) {
		event.respondWith(
			caches.match(event.request).then((cached) => {
				if (cached) return cached;
				return fetch(event.request).then((response) => {
					const clone = response.clone();
					caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
					return response;
				});
			})
		);
		return;
	}

	// Network-first for API GET requests (cache fallback for offline viewing)
	if (url.pathname.startsWith('/api/')) {
		event.respondWith(
			fetch(event.request)
				.then((response) => {
					const clone = response.clone();
					caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
					return response;
				})
				.catch(() => caches.match(event.request))
		);
		return;
	}

	// Stale-while-revalidate for shell pages
	event.respondWith(
		caches.match(event.request).then((cached) => {
			const fetchPromise = fetch(event.request)
				.then((response) => {
					const clone = response.clone();
					caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
					return response;
				})
				.catch(() => cached);
			return cached || fetchPromise;
		})
	);
});
