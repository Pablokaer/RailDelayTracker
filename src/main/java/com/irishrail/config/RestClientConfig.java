package com.irishrail.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Irish Rail is a third-party API we cannot control. Without explicit timeouts a slow
 * upstream pins the collector thread and every request thread waiting on it, so both are
 * mandatory here rather than left to the JDK default (infinite).
 *
 * <p>The client is a pooled Apache HttpClient rather than the JDK's {@code HttpURLConnection}:
 * the JDK keeps at most 5 idle connections per host, so with 8 collector threads a share of every
 * cycle's ~140 calls paid a fresh TLS handshake. The pool is sized to the collector plus a few
 * slots for page-driven requests (journey planner, route lookups) so they never queue behind it.
 */
@Configuration
public class RestClientConfig {

    @Bean(destroyMethod = "close")
    public CloseableHttpClient irishRailHttpClient(
            @Value("${irishrail.api.connect-timeout-ms:4000}") long connectTimeoutMs,
            @Value("${irishrail.api.read-timeout-ms:8000}") long readTimeoutMs,
            @Value("${irishrail.collector.threads:8}") int collectorThreads) {

        int poolSize = collectorThreads + 4;

        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(poolSize)
                .setMaxConnPerRoute(poolSize)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        // Upstream closes idle keep-alives quietly; validating after a pause avoids
                        // handing a dead socket to the collector.
                        .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                        .build())
                .build();

        return HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        // Waiting for a pooled slot is bounded too, so a stalled pool fails fast.
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .build())
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(60))
                .build();
    }

    @Bean
    public RestTemplate irishRailRestTemplate(CloseableHttpClient irishRailHttpClient) {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(irishRailHttpClient));
    }
}
