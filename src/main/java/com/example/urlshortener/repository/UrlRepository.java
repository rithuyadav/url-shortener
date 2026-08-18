package com.example.urlshortener.repository;

import com.example.urlshortener.model.UrlMapping;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for {@link UrlMapping}. Kept as an interface so the
 * backing store (today: in-memory) can be swapped for a real database (Postgres,
 * DynamoDB, etc.) without touching service-layer code.
 */
public interface UrlRepository {

    /**
     * @return false if the short code already exists (caller must treat this as a collision).
     */
    boolean saveIfAbsent(UrlMapping mapping);

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    List<UrlMapping> findAll(int offset, int limit);

    long count();

    List<UrlMapping> findAllActiveExpired();
}
