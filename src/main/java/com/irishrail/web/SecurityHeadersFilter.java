package com.irishrail.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Response security headers. The application served none.
 *
 * <p>Spring Security would bring these along, but it would also bring an authentication model this
 * application has no use for — every page is deliberately public — so the handful of headers that
 * actually apply are set directly.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * Vendor assets all come from cdn.jsdelivr.net (and carry SRI hashes); analytics comes from
     * Google's tag manager; map tiles are configurable and can point at any HTTPS tile server,
     * which is why {@code img-src} is the one permissive directive.
     *
     * <p>{@code script-src} keeps {@code 'unsafe-inline'} because every page inlines a
     * {@code th:inline="javascript"} block of server-rendered constants. Removing it means issuing
     * a per-request nonce and threading it through all fourteen script tags in the templates —
     * worth doing, but a change to every template rather than a header.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://www.googletagmanager.com",
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net",
            "font-src 'self' data: https://cdn.jsdelivr.net",
            "img-src 'self' data: https:",
            "connect-src 'self' https://www.google-analytics.com https://*.google-analytics.com https://*.analytics.google.com",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        // Stops a browser from second-guessing a declared content type, which is how a served
        // text file becomes executable script.
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("X-Frame-Options", "DENY");
        // Nothing here uses a camera, a microphone or a location; say so.
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");

        chain.doFilter(request, response);
    }
}
