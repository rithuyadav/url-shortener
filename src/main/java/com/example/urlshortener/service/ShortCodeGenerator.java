package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.exception.ShortCodeGenerationException;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.util.Base62;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generates unique short codes with collision detection.
 *
 * <p><b>Design decision (reliability):</b> codes are generated as random Base62
 * strings rather than a monotonically-increasing counter encoded to Base62. A
 * counter is simpler and collision-free by construction, but it makes short codes
 * sequentially guessable/enumerable (an attacker or scraper can walk every link in
 * the system) and it becomes a single point of write-contention as a shared counter.
 * Random generation avoids both, at the cost of needing explicit collision handling —
 * implemented here as a bounded retry loop against the repository's atomic
 * {@code putIfAbsent}-style insert. At 7 characters (62^7 ≈ 3.5 trillion codes) and a
 * repository size in the thousands-to-low-millions, collision probability per attempt
 * is negligible; the retry loop and the {@link ShortCodeGenerationException} escape
 * hatch exist so the system degrades loudly instead of silently overwriting a link if
 * that assumption is ever violated (e.g. a much smaller configured code length).
 */
@Component
public class ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeGenerator.class);

    private final UrlRepository urlRepository;
    private final AppProperties appProperties;

    public ShortCodeGenerator(UrlRepository urlRepository, AppProperties appProperties) {
        this.urlRepository = urlRepository;
        this.appProperties = appProperties;
    }

    /**
     * Returns a short code guaranteed (at the moment of return) not to already exist
     * in the repository. Does not itself reserve/save the code; callers must persist
     * atomically via {@code UrlRepository#saveIfAbsent} to close the check-then-act
     * race, and treat a false return from that call as "generate again".
     */
    public String generate() {
        int length = appProperties.getShortCode().getLength();
        int maxAttempts = appProperties.getShortCode().getMaxGenerationAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String candidate = Base62.randomString(length);
            if (!urlRepository.existsByShortCode(candidate)) {
                return candidate;
            }
            log.warn("Short code collision on attempt {}/{} (code={})", attempt, maxAttempts, candidate);
        }

        throw new ShortCodeGenerationException(
                "Failed to generate a unique short code after " + maxAttempts + " attempts");
    }
}
