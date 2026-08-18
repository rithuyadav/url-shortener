package com.example.urlshortener.service;

import com.example.urlshortener.dto.AnalyticsResponse;

public interface AnalyticsService {

    /**
     * Fire-and-forget recording of a click. Must never throw back into the
     * redirect request thread — see {@link AnalyticsServiceImpl} for how
     * failures are contained.
     */
    void recordClickAsync(String shortCode, String referrer, String userAgent, String clientIp);

    AnalyticsResponse getAnalytics(String shortCode);

    /**
     * Total recorded clicks for a short code. This is the single source of truth for
     * "click count" everywhere it's surfaced (metadata responses, listings), rather
     * than duplicating a counter on {@code UrlMapping}. Because recording happens on
     * the async analytics path, this value is eventually consistent with respect to
     * a redirect that just happened moments ago.
     */
    long countClicks(String shortCode);
}
