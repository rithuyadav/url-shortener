package com.example.urlshortener.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;

/**
 * End-to-end tests through the full Spring context + MockMvc, covering the
 * primary user journeys: create -> redirect -> inspect -> analyze -> delete,
 * plus the custom-alias and expiration/validation edge cases.
 *
 * <p>Rate limiting is disabled here (see {@code @TestPropertySource}) because this
 * class intentionally issues many requests in sequence to exercise the full
 * lifecycle; rate-limit behavior itself is covered separately and deliberately in
 * {@link RateLimitIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.rate-limit.enabled=false")
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullLifecycle_create_redirect_metadata_analytics_delete() throws Exception {
        String createBody = """
                {"longUrl": "https://example.com/some/deep/page?x=1"}
                """;

        String createResponseJson = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode", not(emptyString())))
                .andExpect(jsonPath("$.longUrl", is("https://example.com/some/deep/page?x=1")))
                .andExpect(jsonPath("$.clickCount", is(0)))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(createResponseJson);
        String shortCode = created.get("shortCode").asText();

        // Redirect follows the long URL and does not block on analytics recording.
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/some/deep/page?x=1"));

        mockMvc.perform(get("/api/v1/urls/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)));

        // Click recording is async; poll briefly instead of sleeping a fixed amount.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/urls/{shortCode}/analytics", shortCode))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalClicks", is(1))));

        mockMvc.perform(delete("/api/v1/urls/{shortCode}", shortCode))
                .andExpect(status().isNoContent());

        // Deleted links are Gone (410), not silently 404, and no longer redirect.
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone());
    }

    @Test
    void createWithCustomAlias_thenConflictOnReuse() throws Exception {
        String body = """
                {"longUrl": "https://example.com/a", "customAlias": "my-cool-alias"}
                """;

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode", is("my-cool-alias")));

        String conflictBody = """
                {"longUrl": "https://example.com/b", "customAlias": "my-cool-alias"}
                """;
        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
    }

    @Test
    void createWithInvalidUrl_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redirect_unknownShortCode_returns404() throws Exception {
        mockMvc.perform(get("/{shortCode}", "doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void createWithShortLivedTtl_expiresAndGoesAway() throws Exception {
        String body = """
                {"longUrl": "https://example.com/temp", "ttlSeconds": 1}
                """;
        String responseJson = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String shortCode = objectMapper.readTree(responseJson).get("shortCode").asText();

        // Immediately after creation the link is still valid.
        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isGone()));
    }

    @Test
    void listUrls_isPaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/urls")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"longUrl\": \"https://example.com/list-test-" + i + "\"}"));
        }

        mockMvc.perform(get("/api/v1/urls").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalItems", greaterThanOrEqualTo(3)));
    }

    @Test
    void healthEndpoint_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
