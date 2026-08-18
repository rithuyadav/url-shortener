package com.example.urlshortener.cache;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.model.UrlMapping;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Simulated Redis-style cache layer.
 *
 * <p>We use Caffeine (an in-process, high-performance cache) configured with the
 * two properties that matter most for a Redis-style deployment: a time-to-live
 * eviction policy ({@code expireAfterWrite}) and a bounded maximum size with
 * approximate-LRU eviction ({@code maximumSize}). This gives the same *behavioral*
 * guarantees a redirect service would lean on a real Redis cluster for — bounded
 * memory, automatic staleness expiry, hit/miss observability — without requiring
 * external infrastructure to run this prototype.
 *
 * <p><b>Known limitation vs. real Redis:</b> this cache is local to a single JVM
 * instance. In a horizontally-scaled deployment, each instance would have its own
 * cache, so a write on instance A would not invalidate instance B's cached copy
 * until that entry's TTL naturally expires (bounded staleness, not unbounded). The
 * {@link UrlCache} interface boundary exists specifically so this can be swapped
 * for a real shared Redis without touching callers. See docs/ARCHITECTURE.md.
 */
@Component
public class CaffeineUrlCache implements UrlCache {

    private final Cache<String, UrlMapping> cache;
    private final long maxSize;

    public CaffeineUrlCache(AppProperties appProperties) {
        this.maxSize = appProperties.getCache().getMaxSize();
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(appProperties.getCache().getTtlMinutes()))
                .recordStats()
                .build();
    }

    @Override
    public Optional<UrlMapping> get(String shortCode) {
        return Optional.ofNullable(cache.getIfPresent(shortCode));
    }

    @Override
    public void put(UrlMapping mapping) {
        cache.put(mapping.getShortCode(), mapping);
    }

    @Override
    public void evict(String shortCode) {
        cache.invalidate(shortCode);
    }

    @Override
    public UrlCache.CacheStats stats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats s = cache.stats();
        return new UrlCache.CacheStats(s.hitCount(), s.missCount(), s.evictionCount(), cache.estimatedSize(), maxSize);
    }
}
