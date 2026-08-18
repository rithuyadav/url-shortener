package com.example.urlshortener;

import com.example.urlshortener.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener prototype.
 *
 * <p>Design intent (see docs/ARCHITECTURE.md for full rationale):
 * <ul>
 *   <li>{@code @EnableAsync} powers fire-and-forget click analytics recording so the
 *       redirect hot-path is never blocked by analytics writes.</li>
 *   <li>{@code @EnableScheduling} powers a lightweight background sweep that retires
 *       expired short links so they stop resolving even before a read touches them.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableAsync
@EnableScheduling
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
