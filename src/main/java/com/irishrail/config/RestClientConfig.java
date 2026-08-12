package com.irishrail.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Irish Rail is a third-party API we cannot control. Without explicit timeouts a slow
 * upstream pins the collector thread and every request thread waiting on it, so both are
 * mandatory here rather than left to the JDK default (infinite).
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate irishRailRestTemplate(
            RestTemplateBuilder builder,
            @Value("${irishrail.api.connect-timeout-ms:4000}") long connectTimeoutMs,
            @Value("${irishrail.api.read-timeout-ms:8000}") long readTimeoutMs) {

        return builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
