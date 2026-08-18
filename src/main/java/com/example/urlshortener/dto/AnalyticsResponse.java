package com.example.urlshortener.dto;

import com.example.urlshortener.model.ClickEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class AnalyticsResponse {

    private String shortCode;
    private long totalClicks;
    private Map<String, Long> clicksByReferrer;
    private Map<String, Long> clicksByDay;
    private List<RecentClick> recentClicks;

    public static AnalyticsResponse of(String shortCode, long totalClicks, Map<String, Long> byReferrer,
                                        Map<String, Long> byDay, List<ClickEvent> recent) {
        AnalyticsResponse r = new AnalyticsResponse();
        r.shortCode = shortCode;
        r.totalClicks = totalClicks;
        r.clicksByReferrer = byReferrer;
        r.clicksByDay = byDay;
        r.recentClicks = recent.stream()
                .map(e -> new RecentClick(e.getTimestamp(), e.getReferrer(), e.getUserAgent()))
                .toList();
        return r;
    }

    public String getShortCode() {
        return shortCode;
    }

    public long getTotalClicks() {
        return totalClicks;
    }

    public Map<String, Long> getClicksByReferrer() {
        return clicksByReferrer;
    }

    public Map<String, Long> getClicksByDay() {
        return clicksByDay;
    }

    public List<RecentClick> getRecentClicks() {
        return recentClicks;
    }

    public record RecentClick(Instant timestamp, String referrer, String userAgent) {
    }
}
