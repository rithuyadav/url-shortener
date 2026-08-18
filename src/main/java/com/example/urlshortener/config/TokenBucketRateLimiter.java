package com.example.urlshortener.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-key (typically per-client-IP) token bucket rate limiter.
 *
 * <p>Chosen over a fixed/sliding-window counter because it smooths bursts without
 * a hard reset boundary (no "everyone's quota resets at :00 and stampedes") and is
 * cheap to keep entirely in memory. This is a single-instance limiter — in a
 * horizontally-scaled deployment this would move to a shared store (Redis
 * {@code INCR} + TTL, or a token-bucket Lua script) so limits are enforced across
 * instances rather than per-instance; documented as a known limitation in
 * docs/ARCHITECTURE.md.
 *
 * <p>Buckets are created lazily per key and never explicitly evicted; for a
 * long-running production deployment with high key cardinality (e.g. rate limiting
 * by IP at internet scale) this map would need a bounded/expiring cache
 * (Caffeine, as used elsewhere in this codebase) instead of a plain
 * {@link ConcurrentHashMap} to avoid unbounded memory growth. Flagged as a known
 * limitation rather than fixed here, since the prototype's traffic profile doesn't
 * exercise it.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillTokensPerNano;
    private final ConcurrentHashMap<String, AtomicReference<Bucket>> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, long refillTokens, long refillPeriodSeconds) {
        this.capacity = capacity;
        this.refillTokensPerNano = (double) refillTokens / (refillPeriodSeconds * 1_000_000_000.0);
    }

    /**
     * @return true if the call is allowed (a token was consumed), false if the caller
     * should be rejected.
     */
    public boolean tryConsume(String key) {
        AtomicReference<Bucket> ref = buckets.computeIfAbsent(key,
                k -> new AtomicReference<>(new Bucket(capacity, System.nanoTime())));

        while (true) {
            Bucket current = ref.get();
            Bucket refilled = current.refill(refillTokensPerNano, capacity);
            if (refilled.tokens < 1) {
                ref.compareAndSet(current, refilled);
                return false;
            }
            Bucket next = refilled.consumeOne();
            if (ref.compareAndSet(current, next)) {
                return true;
            }
            // Lost the CAS race to a concurrent request for the same key; retry.
        }
    }

    /**
     * Rough estimate of seconds until at least one token is available, for a
     * {@code Retry-After} response header. Not exact under concurrent refill/consume,
     * which is an acceptable approximation for a client-facing hint.
     */
    public long estimateRetryAfterSeconds(String key) {
        AtomicReference<Bucket> ref = buckets.get(key);
        if (ref == null || refillTokensPerNano <= 0) {
            return 1;
        }
        Bucket b = ref.get();
        if (b.tokens >= 1) {
            return 0;
        }
        double tokensNeeded = 1 - b.tokens;
        double nanosNeeded = tokensNeeded / refillTokensPerNano;
        return Math.max(1, (long) Math.ceil(nanosNeeded / 1_000_000_000.0));
    }

    private record Bucket(double tokens, long lastRefillNanos) {
        Bucket refill(double refillTokensPerNano, long capacity) {
            long now = System.nanoTime();
            long elapsed = Math.max(0, now - lastRefillNanos);
            double replenished = Math.min(capacity, tokens + elapsed * refillTokensPerNano);
            return new Bucket(replenished, now);
        }

        Bucket consumeOne() {
            return new Bucket(tokens - 1, lastRefillNanos);
        }
    }
}
