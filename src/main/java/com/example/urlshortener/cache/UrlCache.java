package com.example.urlshortener.cache;

import com.example.urlshortener.model.UrlMapping;

import java.util.Optional;

/**
 * Cache-aside abstraction for {@link UrlMapping} lookups, sitting in front of
 * {@code UrlRepository} on the hot redirect path.
 *
 * <p>This is deliberately an interface: the current implementation
 * ({@link CaffeineUrlCache}) simulates Redis semantics (TTL expiry, bounded size,
 * LRU-ish eviction) in-process so the prototype runs with zero external
 * infrastructure. Swapping in a real Redis client later (e.g. Spring Data Redis /
 * Lettuce) means implementing this same interface — the service layer and
 * controllers never change. See docs/ARCHITECTURE.md, "Cache layer" for the
 * full trade-off discussion.
 */
public interface UrlCache {

    Optional<UrlMapping> get(String shortCode);

    void put(UrlMapping mapping);

    void evict(String shortCode);

    CacheStats stats();

    record CacheStats(long hitCount, long missCount, long evictionCount, long currentSize, long estimatedMaxSize) {
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }
    }
}
