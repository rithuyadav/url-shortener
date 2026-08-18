package com.example.urlshortener.controller;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.dto.AnalyticsResponse;
import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.dto.PagedResponse;
import com.example.urlshortener.dto.UrlResponse;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.service.AnalyticsService;
import com.example.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Management API for short URLs: create, inspect, list, delete, and view analytics.
 * The actual redirect ({@code GET /{shortCode}}) lives in {@link RedirectController}
 * so it can be reasoned about and rate-limited independently of this CRUD surface.
 */
@RestController
@RequestMapping("/api/v1/urls")
@Validated
public class UrlController {

    private final UrlService urlService;
    private final AnalyticsService analyticsService;
    private final AppProperties appProperties;

    public UrlController(UrlService urlService, AnalyticsService analyticsService, AppProperties appProperties) {
        this.urlService = urlService;
        this.analyticsService = analyticsService;
        this.appProperties = appProperties;
    }

    @PostMapping
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        UrlMapping mapping = urlService.createShortUrl(request);
        // A brand-new mapping has zero clicks by construction; skip the (harmless but
        // pointless) analytics lookup on the creation response.
        UrlResponse body = UrlResponse.from(mapping, appProperties.getBaseUrl(), 0L);
        return ResponseEntity.created(URI.create(body.getShortUrl())).body(body);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<UrlResponse> getMetadata(@PathVariable String shortCode) {
        UrlMapping mapping = urlService.getMetadata(shortCode);
        long clicks = analyticsService.countClicks(shortCode);
        return ResponseEntity.ok(UrlResponse.from(mapping, appProperties.getBaseUrl(), clicks));
    }

    @GetMapping("/{shortCode}/analytics")
    public ResponseEntity<AnalyticsResponse> getAnalytics(@PathVariable String shortCode) {
        // Ensures the short code exists before returning analytics (404 instead of an
        // empty-but-200 analytics body for an unknown code).
        urlService.getMetadata(shortCode);
        return ResponseEntity.ok(analyticsService.getAnalytics(shortCode));
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> delete(@PathVariable String shortCode) {
        urlService.deleteUrl(shortCode);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<PagedResponse<UrlResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        List<UrlResponse> items = urlService.list(page, size).stream()
                .map(m -> UrlResponse.from(m, appProperties.getBaseUrl(), analyticsService.countClicks(m.getShortCode())))
                .toList();
        return ResponseEntity.ok(new PagedResponse<>(items, page, size, urlService.count()));
    }
}
