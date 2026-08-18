package com.example.urlshortener.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;
    private final TokenBucketRateLimiter createEndpointRateLimiter;
    private final TokenBucketRateLimiter redirectEndpointRateLimiter;

    // Explicit @Qualifier (rather than relying on parameter-name-to-bean-name matching,
    // which depends on the -parameters compiler flag) so this resolves deterministically
    // regardless of build configuration: two beans of the same type (TokenBucketRateLimiter)
    // are declared in RateLimitConfig, and Spring cannot disambiguate them by type alone.
    public WebConfig(AppProperties appProperties,
                      @Qualifier("createEndpointRateLimiter") TokenBucketRateLimiter createEndpointRateLimiter,
                      @Qualifier("redirectEndpointRateLimiter") TokenBucketRateLimiter redirectEndpointRateLimiter) {
        this.appProperties = appProperties;
        this.createEndpointRateLimiter = createEndpointRateLimiter;
        this.redirectEndpointRateLimiter = redirectEndpointRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        boolean enabled = appProperties.getRateLimit().isEnabled();

        registry.addInterceptor(new RateLimitInterceptor(createEndpointRateLimiter, enabled, "POST"))
                .addPathPatterns("/api/v1/urls")
                .order(1);

        registry.addInterceptor(new RateLimitInterceptor(redirectEndpointRateLimiter, enabled))
                .addPathPatterns("/*")
                .excludePathPatterns("/api/**", "/actuator/**")
                .order(1);
    }
}
