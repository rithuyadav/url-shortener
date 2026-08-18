package com.example.urlshortener.repository;

import com.example.urlshortener.model.ClickEvent;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory click-event store.
 *
 * <p>Reliability decision: a naive implementation would append every {@link ClickEvent}
 * to an unbounded list per short code and recompute aggregates (totals, referrer
 * breakdown, daily breakdown) by scanning that list on every analytics read. Under a
 * viral link that would be an unbounded-memory and O(n)-per-read liability. Instead:
 * <ul>
 *   <li>Aggregates (total count, referrer counts, daily counts) are maintained
 *       incrementally with {@link LongAdder}, giving O(1) writes and O(1)/O(k) reads.</li>
 *   <li>Only a bounded window of the most recent raw events (see {@link #MAX_RECENT_EVENTS})
 *       is retained for the "recent activity" view, so memory is bounded per short code
 *       regardless of lifetime traffic.</li>
 * </ul>
 */
@Repository
public class InMemoryClickEventRepository implements ClickEventRepository {

    private static final int MAX_RECENT_EVENTS = 200;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final class Stats {
        final LongAdder total = new LongAdder();
        final ConcurrentHashMap<String, LongAdder> referrerCounts = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> dailyCounts = new ConcurrentHashMap<>();
        final ConcurrentLinkedDeque<ClickEvent> recentEvents = new ConcurrentLinkedDeque<>();
    }

    private final ConcurrentHashMap<String, Stats> statsByShortCode = new ConcurrentHashMap<>();

    @Override
    public void record(ClickEvent event) {
        Stats stats = statsByShortCode.computeIfAbsent(event.getShortCode(), k -> new Stats());
        stats.total.increment();

        String referrer = (event.getReferrer() == null || event.getReferrer().isBlank()) ? "direct" : event.getReferrer();
        stats.referrerCounts.computeIfAbsent(referrer, k -> new LongAdder()).increment();

        String day = DAY_FORMAT.format(event.getTimestamp());
        stats.dailyCounts.computeIfAbsent(day, k -> new LongAdder()).increment();

        stats.recentEvents.addFirst(event);
        while (stats.recentEvents.size() > MAX_RECENT_EVENTS) {
            stats.recentEvents.removeLast();
        }
    }

    @Override
    public List<ClickEvent> findRecent(String shortCode, int limit) {
        Stats stats = statsByShortCode.get(shortCode);
        if (stats == null) {
            return List.of();
        }
        return stats.recentEvents.stream().limit(limit).toList();
    }

    @Override
    public long countFor(String shortCode) {
        Stats stats = statsByShortCode.get(shortCode);
        return stats == null ? 0 : stats.total.sum();
    }

    @Override
    public Map<String, Long> referrerBreakdown(String shortCode) {
        Stats stats = statsByShortCode.get(shortCode);
        if (stats == null) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        stats.referrerCounts.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    @Override
    public Map<String, Long> dailyBreakdown(String shortCode) {
        Stats stats = statsByShortCode.get(shortCode);
        if (stats == null) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        stats.dailyCounts.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }
}
