package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.exception.ShortCodeGenerationException;
import com.example.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortCodeGeneratorTest {

    private UrlRepository urlRepository;
    private AppProperties appProperties;
    private ShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        appProperties = new AppProperties();
        appProperties.getShortCode().setLength(7);
        appProperties.getShortCode().setMaxGenerationAttempts(5);
        generator = new ShortCodeGenerator(urlRepository, appProperties);
    }

    @Test
    void generate_returnsCodeOfConfiguredLength_whenNoCollision() {
        when(urlRepository.existsByShortCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        String code = generator.generate();

        assertThat(code).hasSize(7);
    }

    @Test
    void generate_retriesOnCollisionThenSucceeds() {
        // First two attempts collide, third is free.
        when(urlRepository.existsByShortCode(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true, true, false);

        String code = generator.generate();

        assertThat(code).hasSize(7);
    }

    @Test
    void generate_throwsAfterExhaustingMaxAttempts() {
        when(urlRepository.existsByShortCode(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        assertThatThrownBy(() -> generator.generate())
                .isInstanceOf(ShortCodeGenerationException.class)
                .hasMessageContaining("5 attempts");
    }
}
