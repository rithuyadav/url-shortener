package com.example.urlshortener.controller;

import com.example.urlshortener.cache.UrlCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes cache hit/miss/eviction stats for observability. Useful during load
 * testing to confirm the cache layer is actually absorbing read traffic rather
 * than falling through to the repository on every redirect.
 */
@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private final UrlCache urlCache;

    public CacheController(UrlCache urlCache) {
        this.urlCache = urlCache;
    }

    @GetMapping("/stats")
    public UrlCache.CacheStats stats() {
        return urlCache.stats();
    }
}
