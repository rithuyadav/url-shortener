package com.example.urlshortener.service;

import com.example.urlshortener.cache.UrlCache;
import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.exception.AliasAlreadyExistsException;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.UrlGoneException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Core orchestration for URL shortening.
 *
 * <p>Read path ({@link #resolve}) is cache-first: on a cache miss we fall back to
 * the repository and repopulate the cache, following the standard cache-aside
 * pattern. Write path ({@link #createShortUrl}) never populates the cache directly;
 * the first redirect naturally warms it. This keeps the cache as a pure derived
 * view of the repository, which simplifies reasoning about consistency.
 */
@Service
public class UrlServiceImpl implements UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlServiceImpl.class);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int MAX_URL_LENGTH = 2048;

    private final UrlRepository urlRepository;
    private final UrlCache urlCache;
    private final ShortCodeGenerator shortCodeGenerator;

    public UrlServiceImpl(UrlRepository urlRepository, UrlCache urlCache, ShortCodeGenerator shortCodeGenerator) {
        this.urlRepository = urlRepository;
        this.urlCache = urlCache;
        this.shortCodeGenerator = shortCodeGenerator;
    }

    @Override
    public UrlMapping createShortUrl(CreateUrlRequest request) {
        String longUrl = validateAndNormalize(request.getLongUrl());

        Instant now = Instant.now();
        Instant expiresAt = request.getTtlSeconds() != null ? now.plusSeconds(request.getTtlSeconds()) : null;

        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            String alias = request.getCustomAlias();
            UrlMapping mapping = new UrlMapping(alias, longUrl, true, now, expiresAt);
            if (!urlRepository.saveIfAbsent(mapping)) {
                throw new AliasAlreadyExistsException(alias);
            }
            log.info("Created short URL with custom alias: {} -> {}", alias, longUrl);
            return mapping;
        }

        // Bounded retry against the repository's atomic insert closes the
        // check-then-act race between ShortCodeGenerator's existence check and
        // the actual insert (two concurrent requests could otherwise generate
        // the same code and both believe it's free).
        int attempts = 0;
        int maxAttempts = 5;
        while (attempts < maxAttempts) {
            String code = shortCodeGenerator.generate();
            UrlMapping mapping = new UrlMapping(code, longUrl, false, now, expiresAt);
            if (urlRepository.saveIfAbsent(mapping)) {
                log.info("Created short URL: {} -> {}", code, longUrl);
                return mapping;
            }
            attempts++;
        }
        throw new IllegalStateException("Unable to allocate a unique short code after " + maxAttempts + " attempts");
    }

    @Override
    public UrlMapping resolve(String shortCode) {
        UrlMapping mapping = urlCache.get(shortCode).orElseGet(() -> {
            UrlMapping fromStore = urlRepository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));
            urlCache.put(fromStore);
            return fromStore;
        });

        Instant now = Instant.now();
        if (!mapping.isActive()) {
            throw new UrlGoneException(shortCode, "deleted");
        }
        if (mapping.isExpired(now)) {
            // Lazily settle the soft-delete + cache eviction so future lookups short-circuit
            // via findByShortCode -> isActive without depending on the background sweep.
            mapping.deactivate();
            urlCache.evict(shortCode);
            throw new UrlGoneException(shortCode, "expired");
        }
        return mapping;
    }

    @Override
    public UrlMapping getMetadata(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }

    @Override
    public void deleteUrl(String shortCode) {
        UrlMapping mapping = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        mapping.deactivate();
        urlCache.evict(shortCode);
        log.info("Deleted (soft) short URL: {}", shortCode);
    }

    @Override
    public List<UrlMapping> list(int page, int size) {
        return urlRepository.findAll(page * size, size);
    }

    @Override
    public long count() {
        return urlRepository.count();
    }

    /**
     * Validates that the submitted long URL is a well-formed, absolute http/https URL
     * and normalizes it (trims whitespace). Rejecting non-http(s) schemes (e.g.
     * {@code javascript:}, {@code file:}, {@code data:}) is a deliberate security
     * control: this service issues HTTP redirects, so accepting those schemes would
     * make it a generic redirector/XSS vector.
     */
    private String validateAndNormalize(String rawUrl) {
        if (rawUrl == null) {
            throw new InvalidUrlException("longUrl must not be null");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidUrlException("longUrl must not be blank");
        }
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("longUrl exceeds maximum length of " + MAX_URL_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("longUrl is not a valid URI: " + e.getMessage());
        }
        if (!uri.isAbsolute() || uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw new InvalidUrlException("longUrl must be an absolute http:// or https:// URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("longUrl must include a host");
        }
        return trimmed;
    }
}
