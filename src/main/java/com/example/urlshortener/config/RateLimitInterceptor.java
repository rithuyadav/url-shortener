package com.example.urlshortener.config;

import com.example.urlshortener.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies a {@link TokenBucketRateLimiter} keyed by client IP to whichever endpoint
 * it's registered against (see {@code WebConfig}). Kept generic/reusable rather than
 * one interceptor per endpoint, since the only thing that differs between the
 * "create" and "redirect" rate limits is which bucket backs them.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketRateLimiter limiter;
    private final boolean enabled;
    private final String restrictToMethod; // null = apply to all methods

    public RateLimitInterceptor(TokenBucketRateLimiter limiter, boolean enabled) {
        this(limiter, enabled, null);
    }

    public RateLimitInterceptor(TokenBucketRateLimiter limiter, boolean enabled, String restrictToMethod) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.restrictToMethod = restrictToMethod;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            return true;
        }
        if (restrictToMethod != null && !restrictToMethod.equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String key = clientKey(request);
        if (limiter.tryConsume(key)) {
            return true;
        }
        throw new RateLimitExceededException(limiter.estimateRetryAfterSeconds(key));
    }

    /**
     * Prefers {@code X-Forwarded-For} (first hop) when present, since this service is
     * expected to sit behind a load balancer/reverse proxy in any real deployment;
     * falls back to the direct remote address for local/dev runs. Note: trusting
     * X-Forwarded-For blindly is only safe because we assume a trusted proxy sets it
     * (see docs/ARCHITECTURE.md, "Rate limiting" security note) — a proxy-less
     * public deployment should not honor this header as-is.
     */
    private String clientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
