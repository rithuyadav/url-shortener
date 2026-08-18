package com.example.urlshortener.service;

import com.example.urlshortener.cache.UrlCache;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background hygiene sweep that proactively deactivates expired links and evicts
 * them from cache.
 *
 * <p>This is a belt-and-suspenders reliability measure, not a correctness
 * dependency: {@code UrlServiceImpl#resolve} already lazily deactivates an expired
 * mapping the moment it's next looked up, and the cache's own TTL bounds how long a
 * stale cached entry can be served. Without this sweep, an expired link that no one
 * clicks would simply sit inactive-but-not-yet-marked in the repository forever,
 * which is harmless but makes {@code GET /api/v1/urls} list stale "still active"
 * entries until someone happens to hit them. The sweep just tightens that window.
 */
@Component
public class ExpiredLinkSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredLinkSweeper.class);

    private final UrlRepository urlRepository;
    private final UrlCache urlCache;

    public ExpiredLinkSweeper(UrlRepository urlRepository, UrlCache urlCache) {
        this.urlRepository = urlRepository;
        this.urlCache = urlCache;
    }

    @Scheduled(fixedDelayString = "${app.cleanup.fixed-delay-ms}")
    public void sweep() {
        List<UrlMapping> expired = urlRepository.findAllActiveExpired();
        for (UrlMapping mapping : expired) {
            mapping.deactivate();
            urlCache.evict(mapping.getShortCode());
        }
        if (!expired.isEmpty()) {
            log.info("Expired-link sweep deactivated {} link(s)", expired.size());
        }
    }
}
