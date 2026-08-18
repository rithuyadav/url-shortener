package com.example.urlshortener.service;

import com.example.urlshortener.cache.UrlCache;
import com.example.urlshortener.dto.CreateUrlRequest;
import com.example.urlshortener.exception.AliasAlreadyExistsException;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.UrlGoneException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UrlServiceImplTest {

    private UrlRepository urlRepository;
    private UrlCache urlCache;
    private ShortCodeGenerator shortCodeGenerator;
    private UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        urlCache = mock(UrlCache.class);
        shortCodeGenerator = mock(ShortCodeGenerator.class);
        service = new UrlServiceImpl(urlRepository, urlCache, shortCodeGenerator);
    }

    private CreateUrlRequest req(String longUrl) {
        CreateUrlRequest r = new CreateUrlRequest();
        r.setLongUrl(longUrl);
        return r;
    }

    // ---- createShortUrl ----------------------------------------------------

    @Test
    void createShortUrl_generatesCodeAndPersists() {
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(urlRepository.saveIfAbsent(any())).thenReturn(true);

        UrlMapping result = service.createShortUrl(req("https://example.com/page"));

        assertThat(result.getShortCode()).isEqualTo("abc1234");
        assertThat(result.getLongUrl()).isEqualTo("https://example.com/page");
        assertThat(result.isCustomAlias()).isFalse();
        verify(urlRepository).saveIfAbsent(any());
    }

    @Test
    void createShortUrl_withCustomAlias_usesAliasAsCode() {
        CreateUrlRequest r = req("https://example.com");
        r.setCustomAlias("my-alias");
        when(urlRepository.saveIfAbsent(any())).thenReturn(true);

        UrlMapping result = service.createShortUrl(r);

        assertThat(result.getShortCode()).isEqualTo("my-alias");
        assertThat(result.isCustomAlias()).isTrue();
        verifyNoInteractions(shortCodeGenerator);
    }

    @Test
    void createShortUrl_withTakenAlias_throwsAliasAlreadyExistsException() {
        CreateUrlRequest r = req("https://example.com");
        r.setCustomAlias("taken");
        when(urlRepository.saveIfAbsent(any())).thenReturn(false);

        assertThatThrownBy(() -> service.createShortUrl(r))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void createShortUrl_withTtl_setsExpiresAtInTheFuture() {
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(urlRepository.saveIfAbsent(any())).thenReturn(true);
        CreateUrlRequest r = req("https://example.com");
        r.setTtlSeconds(3600L);

        UrlMapping result = service.createShortUrl(r);

        assertThat(result.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void createShortUrl_withoutTtl_neverExpires() {
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(urlRepository.saveIfAbsent(any())).thenReturn(true);

        UrlMapping result = service.createShortUrl(req("https://example.com"));

        assertThat(result.getExpiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "javascript:alert(1)", "ftp://example.com/file", "file:///etc/passwd"})
    void createShortUrl_rejectsInvalidOrDisallowedSchemeUrls(String badUrl) {
        assertThatThrownBy(() -> service.createShortUrl(req(badUrl)))
                .isInstanceOf(InvalidUrlException.class);
        verifyNoInteractions(urlRepository);
    }

    @Test
    void createShortUrl_rejectsNullLongUrl() {
        assertThatThrownBy(() -> service.createShortUrl(req(null)))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void createShortUrl_acceptsHttpAndHttps() {
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(urlRepository.saveIfAbsent(any())).thenReturn(true);

        assertThat(service.createShortUrl(req("http://example.com")).getLongUrl()).isEqualTo("http://example.com");
        assertThat(service.createShortUrl(req("https://example.com")).getLongUrl()).isEqualTo("https://example.com");
    }

    // ---- resolve -------------------------------------------------------------

    @Test
    void resolve_cacheHit_returnsWithoutTouchingRepository() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", false, Instant.now(), null);
        when(urlCache.get("abc1234")).thenReturn(Optional.of(mapping));

        UrlMapping result = service.resolve("abc1234");

        assertThat(result).isSameAs(mapping);
        verify(urlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void resolve_cacheMiss_fallsBackToRepositoryAndWarmsCache() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", false, Instant.now(), null);
        when(urlCache.get("abc1234")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        UrlMapping result = service.resolve("abc1234");

        assertThat(result).isSameAs(mapping);
        verify(urlCache).put(mapping);
    }

    @Test
    void resolve_unknownCode_throwsUrlNotFoundException() {
        when(urlCache.get("missing")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("missing"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolve_expiredMapping_throwsUrlGoneException_andDeactivatesAndEvicts() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", false,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        when(urlCache.get("abc1234")).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOf(UrlGoneException.class)
                .hasMessageContaining("expired");

        assertThat(mapping.isActive()).isFalse();
        verify(urlCache).evict("abc1234");
    }

    @Test
    void resolve_deletedMapping_throwsUrlGoneException() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", false, Instant.now(), null);
        mapping.deactivate();
        when(urlCache.get("abc1234")).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOf(UrlGoneException.class)
                .hasMessageContaining("deleted");
    }

    // ---- deleteUrl -------------------------------------------------------------

    @Test
    void deleteUrl_deactivatesAndEvictsFromCache() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", false, Instant.now(), null);
        when(urlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        service.deleteUrl("abc1234");

        assertThat(mapping.isActive()).isFalse();
        verify(urlCache).evict("abc1234");
    }

    @Test
    void deleteUrl_unknownCode_throwsUrlNotFoundException() {
        when(urlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUrl("missing"))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
