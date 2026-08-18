package com.example.urlshortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated, bounded thread pool for async analytics recording (see
 * {@code AnalyticsServiceImpl}). Bounded deliberately: an unbounded queue in front
 * of a slow consumer is a classic way to turn a traffic spike into an OOM. The
 * {@link ThreadPoolExecutor.CallerRunsPolicy} degrades gracefully under overload —
 * back-pressure is applied to the caller (analytics recording briefly runs on the
 * calling thread) instead of throwing away work silently or crashing the process;
 * given analytics recording is already fire-and-forget from the redirect path's
 * point of view, this bound protects the JVM without ever blocking a redirect.
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "analyticsExecutor")
    public ThreadPoolTaskExecutor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Initialized analytics executor: core=4 max=16 queue=1000 rejection=CallerRuns");
        return executor;
    }
}
