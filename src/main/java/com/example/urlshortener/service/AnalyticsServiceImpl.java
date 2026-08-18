package com.example.urlshortener.service;

import com.example.urlshortener.dto.AnalyticsResponse;
import com.example.urlshortener.model.ClickEvent;
import com.example.urlshortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Records click analytics off the redirect hot path.
 *
 * <p><b>Reliability decision:</b> {@link #recordClickAsync} runs on the
 * {@code analyticsExecutor} thread pool (see {@code AsyncConfig}), not the request
 * thread that served the redirect. A redirect must succeed even if analytics
 * recording is slow or fails; we intentionally swallow-and-log exceptions here
 * rather than propagate them, because a broken analytics pipeline must never turn
 * into a broken redirect. The trade-off is explicit: under sustained overload, some
 * click events can be dropped rather than backing up the executor's bounded queue
 * (see {@code AsyncConfig} rejection policy) — acceptable for analytics, would not
 * be acceptable for the URL mapping write path itself.
 *
 * <p>Client IPs are hashed (SHA-256, truncated) rather than stored raw, so recent-click
 * analytics don't retain PII beyond what's needed for coarse abuse/traffic signals.
 */
@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsServiceImpl.class);

    private final ClickEventRepository clickEventRepository;

    public AnalyticsServiceImpl(ClickEventRepository clickEventRepository) {
        this.clickEventRepository = clickEventRepository;
    }

    @Async("analyticsExecutor")
    @Override
    public void recordClickAsync(String shortCode, String referrer, String userAgent, String clientIp) {
        try {
            ClickEvent event = new ClickEvent(shortCode, Instant.now(), referrer, userAgent, hashIp(clientIp));
            clickEventRepository.record(event);
        } catch (Exception ex) {
            // Never let analytics failures surface to the caller; this method is invoked
            // fire-and-forget from the redirect path.
            log.warn("Failed to record click analytics for shortCode={}: {}", shortCode, ex.getMessage());
        }
    }

    @Override
    public AnalyticsResponse getAnalytics(String shortCode) {
        long total = clickEventRepository.countFor(shortCode);
        var byReferrer = clickEventRepository.referrerBreakdown(shortCode);
        var byDay = clickEventRepository.dailyBreakdown(shortCode);
        var recent = clickEventRepository.findRecent(shortCode, 50);
        return AnalyticsResponse.of(shortCode, total, byReferrer, byDay, recent);
    }

    @Override
    public long countClicks(String shortCode) {
        return clickEventRepository.countFor(shortCode);
    }

    private static String hashIp(String ip) {
        if (ip == null) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
