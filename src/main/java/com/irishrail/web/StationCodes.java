package com.irishrail.web;

import com.irishrail.service.StationDirectory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a {@code stationCode} request parameter into one we are willing to act on.
 *
 * <p>It previously went straight from the query string into the upstream URL and into a cache key.
 * That gave three problems at once: the key space of an in-memory cache was controlled by the
 * caller, an unknown code still cost a call to Irish Rail, and characters such as {@code &} landed
 * unescaped in the upstream query string.
 */
@Component
public class StationCodes {

    /** Irish Rail codes are short alphanumeric tokens: CNLLY, HSTON, MHIDE, DUBLINCONNOLLY. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Z0-9]{2,12}$");

    private final StationDirectory directory;

    public StationCodes(StationDirectory directory) {
        this.directory = directory;
    }

    /** Normalised code, or empty when it is malformed or names no station on the network. */
    public Optional<String> resolve(String raw) {
        if (raw == null) return Optional.empty();
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!WELL_FORMED.matcher(code).matches()) return Optional.empty();
        if (!directory.isKnownCode(code)) return Optional.empty();
        return Optional.of(code);
    }

    /**
     * For JSON endpoints, where a bad code is a client error worth reporting.
     *
     * @throws InvalidRequestException rendered as a 400 by {@link ApiExceptionHandler}
     */
    public String require(String parameterName, String raw) {
        return resolve(raw).orElseThrow(() -> new InvalidRequestException(
                "'" + parameterName + "' is not a known Irish Rail station code"));
    }

    /**
     * For pages, where a 400 would replace the dashboard with an error screen. An unusable code
     * falls back to the default station — but never reaches the cache or the upstream API.
     */
    public String orDefault(String raw, String fallback) {
        return resolve(raw).orElse(fallback);
    }
}
