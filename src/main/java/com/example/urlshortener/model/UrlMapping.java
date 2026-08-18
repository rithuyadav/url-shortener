package com.example.urlshortener.model;

import java.time.Instant;

/**
 * Core domain entity: a mapping from a short code to a destination (long) URL.
 *
 * <p>Deliberately does <em>not</em> carry its own click-count field. An earlier
 * draft kept a counter here that was meant to be incremented from the async
 * analytics path, but nothing ever actually called it, which is exactly the kind
 * of silent-drift bug two independently-maintained counters (this one, and the
 * aggregate in {@code ClickEventRepository}) invite. Click counts are now sourced
 * solely from {@code AnalyticsService}/{@code ClickEventRepository} — see
 * docs/AI_TRACEABILITY.md for how this was caught in review. All fields here are
 * effectively immutable after creation except {@code active}, which is flipped by
 * delete/expire operations.
 */
public class UrlMapping {

    private final String shortCode;
    private final String longUrl;
    private final boolean customAlias;
    private final Instant createdAt;
    private final Instant expiresAt; // nullable: no expiration
    private volatile boolean active = true;

    public UrlMapping(String shortCode, String longUrl, boolean customAlias, Instant createdAt, Instant expiresAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.customAlias = customAlias;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * A link is usable if it hasn't been soft-deleted and hasn't passed its expiry.
     */
    public boolean isResolvable(Instant now) {
        return active && !isExpired(now);
    }
}
