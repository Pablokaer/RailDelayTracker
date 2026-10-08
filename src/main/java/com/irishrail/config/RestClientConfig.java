package com.irishrail.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

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
    public CloseableHttpClient irishRailHttpClient(IrishRailProperties properties) {
        long connectTimeoutMs = properties.api().connectTimeoutMs();
        long readTimeoutMs = properties.api().readTimeoutMs();
        int poolSize = properties.collector().threads() + 4;

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

    /**
     * {@code RestTemplate} is in maintenance mode; {@code RestClient} is its synchronous successor
     * and wraps the same pooled request factory, so the connection pool and the timeouts above are
     * unchanged.
     */
    @Bean
    public RestClient irishRailRestClient(CloseableHttpClient irishRailHttpClient) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(irishRailHttpClient))
                .build();
    }

    /**
     * One shared mapper instead of three.
     *
     * <p>{@code IrishRailService}, {@code TrainPositionService} and {@code TrainRouteService} each
     * built their own {@code new XmlMapper()}. It is thread-safe once configured and expensive to
     * construct (it builds and caches a serializer graph per type), so the copies bought nothing
     * and warmed their caches separately.
     */
    @Bean
    public XmlMapper irishRailXmlMapper() {
        return new XmlMapper();
    }

    /**
     * The mapper behind the app's own JSON endpoints.
     *
     * <p>{@code XmlMapper} is an {@code ObjectMapper}, so declaring it above made Boot's default
     * mapper back off and every {@code /api} response was serialized as XML under a JSON content
     * type, which broke the journey planner and the live map in the browser.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.createXmlMapper(false).build();
    }
}
