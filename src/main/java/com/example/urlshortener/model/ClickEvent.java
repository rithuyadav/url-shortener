package com.example.urlshortener.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single recorded click against a short URL, used to power analytics
 * (time-series counts, referrer breakdown). Kept intentionally small; in a
 * production system this would be streamed to a proper analytics store
 * (Kafka -> warehouse) instead of held in-process.
 */
public class ClickEvent {

    private final String id;
    private final String shortCode;
    private final Instant timestamp;
    private final String referrer;
    private final String userAgent;
    private final String clientIpHash;

    public ClickEvent(String shortCode, Instant timestamp, String referrer, String userAgent, String clientIpHash) {
        this.id = UUID.randomUUID().toString();
        this.shortCode = shortCode;
        this.timestamp = timestamp;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.clientIpHash = clientIpHash;
    }

    public String getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getClientIpHash() {
        return clientIpHash;
    }
}
