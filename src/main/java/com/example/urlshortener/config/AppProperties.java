package com.example.urlshortener.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding of the {@code app.*} configuration tree in application.yml.
 * Centralizing configuration here (rather than scattering {@code @Value} injections)
 * keeps tunables discoverable and fails fast at startup if misconfigured.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8080";

    private final ShortCode shortCode = new ShortCode();
    private final Cache cache = new Cache();
    private final RateLimit rateLimit = new RateLimit();
    private final Cleanup cleanup = new Cleanup();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public ShortCode getShortCode() {
        return shortCode;
    }

    public Cache getCache() {
        return cache;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public static class ShortCode {
        @Min(4)
        private int length = 7;
        @Min(1)
        private int maxGenerationAttempts = 5;

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public int getMaxGenerationAttempts() {
            return maxGenerationAttempts;
        }

        public void setMaxGenerationAttempts(int maxGenerationAttempts) {
            this.maxGenerationAttempts = maxGenerationAttempts;
        }
    }

    public static class Cache {
        @Min(1)
        private long maxSize = 10_000;
        @Min(1)
        private long ttlMinutes = 10;

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private final Bucket create = new Bucket(20, 20, 60);
        private final Bucket redirect = new Bucket(120, 120, 60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Bucket getCreate() {
            return create;
        }

        public Bucket getRedirect() {
            return redirect;
        }

        public static class Bucket {
            private long capacity;
            private long refillTokens;
            private long refillPeriodSeconds;

            public Bucket() {
            }

            public Bucket(long capacity, long refillTokens, long refillPeriodSeconds) {
                this.capacity = capacity;
                this.refillTokens = refillTokens;
                this.refillPeriodSeconds = refillPeriodSeconds;
            }

            public long getCapacity() {
                return capacity;
            }

            public void setCapacity(long capacity) {
                this.capacity = capacity;
            }

            public long getRefillTokens() {
                return refillTokens;
            }

            public void setRefillTokens(long refillTokens) {
                this.refillTokens = refillTokens;
            }

            public long getRefillPeriodSeconds() {
                return refillPeriodSeconds;
            }

            public void setRefillPeriodSeconds(long refillPeriodSeconds) {
                this.refillPeriodSeconds = refillPeriodSeconds;
            }
        }
    }

    public static class Cleanup {
        @Min(1000)
        private long fixedDelayMs = 60_000;

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
