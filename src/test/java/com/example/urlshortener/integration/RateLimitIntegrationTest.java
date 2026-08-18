package com.example.urlshortener.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the create-endpoint rate limiter actually engages and returns a
 * spec-correct 429 + Retry-After. Uses an artificially tiny bucket
 * (capacity=2) via property override so the test is fast and deterministic,
 * rather than trying to exhaust the default production limit of 20/min.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.create.capacity=2",
        "app.rate-limit.create.refill-tokens=2",
        "app.rate-limit.create.refill-period-seconds=60"
})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BODY = "{\"longUrl\": \"https://example.com/rl-test\"}";

    @Test
    void createEndpoint_exceedsRateLimit_returns429WithRetryAfter() throws Exception {
        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        // Third request within the same minute exceeds the 2-token bucket.
        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
