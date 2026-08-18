package com.example.urlshortener.service;

import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.model.UrlMapping;

import java.util.List;

public interface UrlService {

    UrlMapping createShortUrl(CreateUrlRequest request);

    /**
     * Resolves a short code to its {@link UrlMapping} for redirect purposes.
     * Throws if the code doesn't exist or is no longer active/expired.
     * Does not itself record analytics — callers own that decision.
     */
    UrlMapping resolve(String shortCode);

    UrlMapping getMetadata(String shortCode);

    void deleteUrl(String shortCode);

    List<UrlMapping> list(int page, int size);

    long count();
}
