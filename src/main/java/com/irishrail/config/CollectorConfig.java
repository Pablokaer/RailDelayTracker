package com.irishrail.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The collector fans out one HTTP call per station (~130 stations per cycle). Done
 * sequentially that overruns the 30 s window, so station fetches run on this bounded pool.
 * The pool is deliberately small: it is polite to the upstream API and keeps the number of
 * in-flight sockets predictable.
 */
@Configuration
public class CollectorConfig {

    @Bean(name = "stationFetchExecutor")
    public ThreadPoolTaskExecutor stationFetchExecutor(
            @Value("${irishrail.collector.threads:8}") int threads,
            @Value("${irishrail.collector.queue-capacity:512}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("station-fetch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
