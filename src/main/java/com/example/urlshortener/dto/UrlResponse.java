package com.example.urlshortener.dto;

import com.example.urlshortener.model.UrlMapping;

import java.time.Instant;

public class UrlResponse {

    private String shortCode;
    private String shortUrl;
    private String longUrl;
    private boolean customAlias;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean active;
    private long clickCount;

    public static UrlResponse from(UrlMapping mapping, String baseUrl, long clickCount) {
        UrlResponse r = new UrlResponse();
        r.shortCode = mapping.getShortCode();
        r.shortUrl = baseUrl.replaceAll("/$", "") + "/" + mapping.getShortCode();
        r.longUrl = mapping.getLongUrl();
        r.customAlias = mapping.isCustomAlias();
        r.createdAt = mapping.getCreatedAt();
        r.expiresAt = mapping.getExpiresAt();
        r.active = mapping.isActive();
        r.clickCount = clickCount;
        return r;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getShortUrl() {
        return shortUrl;
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

    public long getClickCount() {
        return clickCount;
    }
}
