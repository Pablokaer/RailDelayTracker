package com.irishrail.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.irishrail.config.IrishRailProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * A token bucket per client on {@code /api/**}.
 *
 * <p>These endpoints are public and several of them run aggregation queries. Nothing bounded how
 * fast one caller could ask for them, and the analytics endpoints in particular were cheap to
 * request and expensive to answer on a cache miss.
 *
 * <p>Deliberately no library: a bucket is two numbers, and Caffeine — already a dependency for the
 * caches — supplies the bounded, self-expiring per-client map that is the only hard part.
 *
 * <p>Note this is per instance, not per cluster. That is the right granularity for protecting
 * <em>this</em> process from a single noisy caller; it is not a billing-grade quota.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final IrishRailProperties.RateLimit config;
    private final Cache<String, Bucket> buckets;
    private final Counter rejected;

    public RateLimitFilter(IrishRailProperties properties, MeterRegistry meters) {
        this.config = properties.rateLimit();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(config.maxTrackedClients())
                // An idle client's bucket is always full, so forgetting it loses nothing.
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
        this.rejected = Counter.builder("irishrail.ratelimit.rejected").register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Pages, static assets and the actuator are out of scope; /api/events is a long-lived SSE
        // stream whose subscriber cap is the relevant limit, not a request rate.
        String path = request.getRequestURI();
        return !config.enabled() || !path.startsWith("/api/") || path.equals("/api/events");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Bucket bucket = buckets.get(clientKey(request), k -> new Bucket(config));
        if (!bucket.tryConsume()) {
            rejected.increment();
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                    "{\"title\":\"Too many requests\",\"status\":429,"
                    + "\"detail\":\"Rate limit exceeded; retry shortly.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * X-Forwarded-For is honoured because the production profile already runs behind a proxy
     * ({@code server.forward-headers-strategy=framework}). Only the first hop is read, and it is
     * length-capped: the header is client-supplied, so it is a grouping hint, not an identity.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /**
     * Refills continuously rather than on a fixed window boundary, so a caller cannot spend a full
     * allowance at 12:00:59 and another at 12:01:00.
     */
    private static final class Bucket {

        private final double capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefillMs;

        Bucket(IrishRailProperties.RateLimit config) {
            this.capacity = config.burst();
            this.refillPerMs = config.requestsPerMinute() / 60_000d;
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - lastRefillMs) * refillPerMs);
            lastRefillMs = now;
            if (tokens < 1d) return false;
            tokens -= 1d;
            return true;
        }
    }
}
