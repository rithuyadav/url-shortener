package com.example.urlshortener.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    @Test
    void allowsRequestsUpToCapacity_thenRejects() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 3, 60);

        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isTrue();
        // Bucket started full at capacity=3; a 4th immediate request has no tokens left.
        assertThat(limiter.tryConsume("client-a")).isFalse();
    }

    @Test
    void tracksDifferentKeysIndependently() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 60);

        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isFalse();
        // A different key must not be affected by client-a's exhausted bucket.
        assertThat(limiter.tryConsume("client-b")).isTrue();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        // capacity=1, refill 5 tokens/sec -> after exhausting, waiting >200ms (1/5s)
        // must yield at least one more allowed call.
        TokenBucketRateLimiter fastRefill = new TokenBucketRateLimiter(1, 5, 1);
        assertThat(fastRefill.tryConsume("k")).isTrue();
        assertThat(fastRefill.tryConsume("k")).isFalse();

        Thread.sleep(300); // >= 1/5s needed for one token to refill

        assertThat(fastRefill.tryConsume("k")).isTrue();
    }

    @Test
    void estimateRetryAfterSeconds_isZeroWhenTokenAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 2, 60);
        limiter.tryConsume("k"); // consume one, one still available

        assertThat(limiter.estimateRetryAfterSeconds("k")).isEqualTo(0);
    }

    @Test
    void estimateRetryAfterSeconds_isPositiveWhenExhausted() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 60);
        limiter.tryConsume("k");
        limiter.tryConsume("k"); // exhausts and records refill state

        assertThat(limiter.estimateRetryAfterSeconds("k")).isGreaterThan(0);
    }

    @Test
    void unknownKey_estimateRetryAfterSeconds_defaultsToOne() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 60);

        assertThat(limiter.estimateRetryAfterSeconds("never-seen")).isEqualTo(1);
    }
}
