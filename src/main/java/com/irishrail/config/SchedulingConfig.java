package com.irishrail.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduling, made switchable.
 *
 * <p>{@code @EnableScheduling} was on the application class, so merely starting a Spring context
 * started the collector — which immediately calls the live Irish Rail API and writes to whatever
 * database is configured. That is why there was no test that loaded the context: there was no way
 * to load it harmlessly. Tests set {@code irishrail.scheduling.enabled=false}.
 *
 * <p>The {@link ThreadPoolTaskScheduler} is declared here rather than left to Boot's
 * auto-configuration, because that one only appears when scheduling is enabled and
 * {@code TrainDelayScheduler} injects a {@code TaskScheduler} for its post-startup retention sweep
 * regardless.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:6}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // The collector, the position refresher, the station directory, the SSE heartbeat and the
        // aggregate roll-up are separate tasks. At the framework default of 1, a slow collection
        // cycle starves all the others.
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "irishrail.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class TimersEnabled {
    }
}
