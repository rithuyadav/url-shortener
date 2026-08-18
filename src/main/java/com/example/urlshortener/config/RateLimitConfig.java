package com.example.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public TokenBucketRateLimiter createEndpointRateLimiter(AppProperties props) {
        AppProperties.RateLimit.Bucket b = props.getRateLimit().getCreate();
        return new TokenBucketRateLimiter(b.getCapacity(), b.getRefillTokens(), b.getRefillPeriodSeconds());
    }

    @Bean
    public TokenBucketRateLimiter redirectEndpointRateLimiter(AppProperties props) {
        AppProperties.RateLimit.Bucket b = props.getRateLimit().getRedirect();
        return new TokenBucketRateLimiter(b.getCapacity(), b.getRefillTokens(), b.getRefillPeriodSeconds());
    }
}
