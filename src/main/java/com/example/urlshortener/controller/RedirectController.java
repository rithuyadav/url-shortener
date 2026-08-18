package com.example.urlshortener.controller;

import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.service.AnalyticsService;
import com.example.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The hot path: resolves a short code and issues an HTTP redirect.
 *
 * <p>Deliberately separate from {@link UrlController} (the CRUD surface) so this
 * single endpoint's latency and rate limit budget can be reasoned about in
 * isolation — this is the endpoint end users actually click, and it needs to stay
 * fast even while the management API is under load.
 *
 * <p>Uses 302 (Found / temporary redirect) rather than 301 (Moved Permanently).
 * A 301 would let browsers cache the redirect target indefinitely, which breaks
 * both link expiration/deletion (the browser would keep redirecting locally,
 * bypassing this service entirely) and click analytics (the redirect would stop
 * happening after the first visit per browser). 302 keeps every click flowing
 * through this service, which this prototype's analytics requirement depends on.
 */
@RestController
public class RedirectController {

    private final UrlService urlService;
    private final AnalyticsService analyticsService;

    public RedirectController(UrlService urlService, AnalyticsService analyticsService) {
        this.urlService = urlService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        UrlMapping mapping = urlService.resolve(shortCode);

        analyticsService.recordClickAsync(
                shortCode,
                request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT),
                request.getRemoteAddr());

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(mapping.getLongUrl()))
                .build();
    }
}
