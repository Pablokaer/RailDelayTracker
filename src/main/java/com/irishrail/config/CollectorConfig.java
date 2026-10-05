package com.irishrail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The collector fans out one HTTP call per station (~130 stations per cycle). Done
 * sequentially that overruns the 30 s window, so station fetches run on this bounded pool.
 * The pool is deliberately small: it is polite to the upstream API and keeps the number of
 * in-flight sockets predictable.
 *
 * <p>It stays a platform-thread pool even though the application runs on virtual threads. The
 * bound is the point — it is what limits concurrent load on a third-party API, and it is matched
 * by the HTTP connection pool in {@link RestClientConfig}. Handing these tasks to unbounded
 * virtual threads would remove exactly the property the pool exists for.
 */
@Configuration
public class CollectorConfig {

    @Bean(name = "stationFetchExecutor")
    public ThreadPoolTaskExecutor stationFetchExecutor(IrishRailProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.collector().threads());
        executor.setMaxPoolSize(properties.collector().threads());
        executor.setQueueCapacity(properties.collector().queueCapacity());
        executor.setThreadNamePrefix("station-fetch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * Fans SSE broadcasts out off the collector thread.
     *
     * <p>{@code SnapshotEventService.broadcast()} used to be called synchronously at the end of
     * every collection cycle and iterate all subscribers inline, so one slow client stalled data
     * collection for everyone. A single worker keeps the fan-out ordered; the queue is short
     * because a broadcast is only a "something changed" tick and a backlog of those is redundant.
     */
    @Bean(name = "sseBroadcastExecutor")
    public ThreadPoolTaskExecutor sseBroadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("sse-broadcast-");
        // A dropped tick costs nothing: the next cycle sends another one 30 s later, and clients
        // re-read the current state rather than replaying events.
        executor.setRejectedExecutionHandler((r, e) -> { });
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
