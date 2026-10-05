package com.irishrail.web;

import com.irishrail.config.IrishRailProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The throttle in front of the public, aggregation-backed {@code /api} endpoints. */
class RateLimitFilterTest {

    @Test
    void servesUpToTheBurstThenAnswers429() throws Exception {
        RateLimitFilter filter = filterAllowing(60, 3);

        assertThat(statusAfterCalls(filter, "1.2.3.4", 3)).isEqualTo(200);
        assertThat(callOnce(filter, "1.2.3.4").getStatus()).isEqualTo(429);
    }

    @Test
    void budgetsAreHeldPerClient() throws Exception {
        RateLimitFilter filter = filterAllowing(60, 2);

        statusAfterCalls(filter, "1.2.3.4", 2);
        assertThat(callOnce(filter, "1.2.3.4").getStatus()).isEqualTo(429);
        assertThat(callOnce(filter, "5.6.7.8").getStatus())
                .as("one noisy caller must not spend another caller's allowance")
                .isEqualTo(200);
    }

    @Test
    void rejectionCarriesRetryAfterAndAProblemBody() throws Exception {
        RateLimitFilter filter = filterAllowing(60, 1);
        callOnce(filter, "1.2.3.4");

        MockHttpServletResponse response = callOnce(filter, "1.2.3.4");
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void pagesAndStaticAssetsAreNotThrottled() {
        RateLimitFilter filter = filterAllowing(60, 1);
        assertThat(filter.shouldNotFilter(get("/overview", "1.2.3.4"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/css/app.css", "1.2.3.4"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/actuator/health", "1.2.3.4"))).isTrue();
    }

    /** The event stream is long-lived; its limit is the subscriber cap, not a request rate. */
    @Test
    void theEventStreamIsExemptButOtherApiRoutesAreNot() {
        RateLimitFilter filter = filterAllowing(60, 1);
        assertThat(filter.shouldNotFilter(get("/api/events", "1.2.3.4"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/trains", "1.2.3.4"))).isFalse();
    }

    @Test
    void disablingTheLimiterTurnsItIntoAPassThrough() {
        IrishRailProperties props = properties(new IrishRailProperties.RateLimit(false, 60, 1, 1000));
        RateLimitFilter filter = new RateLimitFilter(props, new SimpleMeterRegistry());
        assertThat(filter.shouldNotFilter(get("/api/trains", "1.2.3.4"))).isTrue();
    }

    /** The first hop of a client-supplied header groups callers; it is a hint, not an identity. */
    @Test
    void forwardedClientsAreBudgetedSeparately() throws Exception {
        RateLimitFilter filter = filterAllowing(60, 1);

        MockHttpServletRequest first = get("/api/trains", "10.0.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        assertThat(call(filter, first).getStatus()).isEqualTo(200);

        MockHttpServletRequest again = get("/api/trains", "10.0.0.1");
        again.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        assertThat(call(filter, again).getStatus()).isEqualTo(429);

        MockHttpServletRequest other = get("/api/trains", "10.0.0.1");
        other.addHeader("X-Forwarded-For", "203.0.113.8, 10.0.0.1");
        assertThat(call(filter, other).getStatus()).isEqualTo(200);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RateLimitFilter filterAllowing(int perMinute, int burst) {
        return new RateLimitFilter(
                properties(new IrishRailProperties.RateLimit(true, perMinute, burst, 1000)),
                new SimpleMeterRegistry());
    }

    private static IrishRailProperties properties(IrishRailProperties.RateLimit rateLimit) {
        return new IrishRailProperties(
                null, null, null, null, null, null, rateLimit,
                List.of("CNLLY"), null, null);
    }

    private static MockHttpServletRequest get(String uri, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static int statusAfterCalls(RateLimitFilter filter, String ip, int times) throws Exception {
        MockHttpServletResponse response = null;
        for (int i = 0; i < times; i++) response = callOnce(filter, ip);
        return response == null ? 0 : response.getStatus();
    }

    private static MockHttpServletResponse callOnce(RateLimitFilter filter, String ip) throws Exception {
        return call(filter, get("/api/trains", ip));
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }
}
