package com.example.urlshortener.exception;

/**
 * Thrown when a short code exists but is no longer resolvable
 * (expired or soft-deleted). Distinct from {@link UrlNotFoundException}
 * so clients/observability can tell "never existed" apart from "existed, now gone".
 */
public class UrlGoneException extends RuntimeException {
    public UrlGoneException(String shortCode, String reason) {
        super("Short code '" + shortCode + "' is no longer active (" + reason + ")");
    }
}
