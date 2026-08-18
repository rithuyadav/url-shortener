package com.example.urlshortener.repository;

import com.example.urlshortener.model.UrlMapping;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the "system of record" database.
 *
 * <p><b>Reliability note / known limitation:</b> this store is not durable across
 * process restarts and is not shared across instances. It is intentionally isolated
 * behind the {@link UrlRepository} interface so a follow-up task can swap in a real
 * database (see docs/ARCHITECTURE.md, "Persistence" trade-off) without touching
 * {@code UrlService} or the controllers.
 *
 * <p>{@code findAll} sorts on every call, which is O(n log n) per page request.
 * That is an acceptable trade-off for a prototype expected to hold at most a few
 * thousand entries in memory; a real datastore would push this down to an indexed
 * query instead.
 */
@Repository
public class InMemoryUrlRepository implements UrlRepository {

    private final ConcurrentHashMap<String, UrlMapping> byShortCode = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(UrlMapping mapping) {
        return byShortCode.putIfAbsent(mapping.getShortCode(), mapping) == null;
    }

    @Override
    public Optional<UrlMapping> findByShortCode(String shortCode) {
        return Optional.ofNullable(byShortCode.get(shortCode));
    }

    @Override
    public boolean existsByShortCode(String shortCode) {
        return byShortCode.containsKey(shortCode);
    }

    @Override
    public List<UrlMapping> findAll(int offset, int limit) {
        List<UrlMapping> all = new ArrayList<>(byShortCode.values());
        all.sort(Comparator.comparing(UrlMapping::getCreatedAt).reversed());
        if (offset >= all.size()) {
            return List.of();
        }
        int end = Math.min(all.size(), offset + limit);
        return all.subList(offset, end);
    }

    @Override
    public long count() {
        return byShortCode.size();
    }

    @Override
    public List<UrlMapping> findAllActiveExpired() {
        Instant now = Instant.now();
        List<UrlMapping> expired = new ArrayList<>();
        for (UrlMapping mapping : byShortCode.values()) {
            if (mapping.isActive() && mapping.isExpired(now)) {
                expired.add(mapping);
            }
        }
        return expired;
    }
}
