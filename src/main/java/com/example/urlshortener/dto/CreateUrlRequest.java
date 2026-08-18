package com.example.urlshortener.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/urls}.
 *
 * <p>{@code longUrl} is validated for well-formedness (not just non-blank) in
 * {@code UrlService} rather than a regex here, because "is this a valid URL"
 * needs {@link java.net.URI} parsing semantics that a single annotation can't
 * express cleanly; the {@code @NotBlank} here is a fast, cheap first gate.
 */
public class CreateUrlRequest {

    @NotBlank(message = "longUrl must not be blank")
    private String longUrl;

    @Pattern(regexp = "^[a-zA-Z0-9_-]{3,20}$", message = "customAlias must be 3-20 characters of letters, digits, '-' or '_'")
    private String customAlias;

    @Min(value = 1, message = "ttlSeconds must be positive")
    private Long ttlSeconds;

    public CreateUrlRequest() {
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getCustomAlias() {
        return customAlias;
    }

    public void setCustomAlias(String customAlias) {
        this.customAlias = customAlias;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
