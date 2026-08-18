package com.example.urlshortener.repository;

import com.example.urlshortener.model.ClickEvent;

import java.util.List;
import java.util.Map;

public interface ClickEventRepository {

    void record(ClickEvent event);

    /**
     * Most recent events for a short code, newest first, capped at {@code limit}.
     */
    List<ClickEvent> findRecent(String shortCode, int limit);

    long countFor(String shortCode);

    /**
     * Referrer -> count, for a given short code.
     */
    Map<String, Long> referrerBreakdown(String shortCode);

    /**
     * ISO-8601 date (yyyy-MM-dd, UTC) -> click count, for a given short code.
     */
    Map<String, Long> dailyBreakdown(String shortCode);
}
