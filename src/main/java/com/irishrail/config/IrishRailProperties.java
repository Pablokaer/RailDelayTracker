package com.irishrail.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Every {@code irishrail.*} setting, in one validated tree.
 *
 * <p>These used to be ~30 {@code @Value} fields scattered over {@link com.irishrail.controller}
 * and four services: non-final, injected by field, and impossible to construct in a unit test
 * without a Spring context. Worse, a typo in a key silently fell back to the inline default, and a
 * nonsensical value (a negative pool size, a zero timeout) only surfaced as odd behaviour at
 * runtime. Constructor binding plus Bean Validation turns both of those into a failed boot.
 *
 * <p>Property keys are unchanged from the previous {@code @Value} annotations on purpose, so any
 * environment-variable override already in use keeps working.
 */
@ConfigurationProperties(prefix = "irishrail")
@Validated
public record IrishRailProperties(

        @Valid @DefaultValue Api api,
        @Valid @DefaultValue Collector collector,
        @Valid @DefaultValue Retention retention,
        @Valid @DefaultValue Analytics analytics,
        @Valid @DefaultValue MapView map,
        @Valid @DefaultValue Sse sse,
        @Valid @DefaultValue RateLimit rateLimit,

        /** Service tabs offered by the UI, e.g. {@code CNLLY,HSTON}. */
        @NotEmpty @DefaultValue({"CNLLY", "HSTON"}) List<String> trackedStationCodes,

        @Valid @DefaultValue Scope connolly,
        @Valid @DefaultValue Scope heuston) {

    /** Irish Rail realtime API endpoints, timeouts and the TTLs of the caches in front of them. */
    public record Api(
            @NotBlank @DefaultValue("https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=D")
            String allStationsUrl,

            @NotBlank @DefaultValue("https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=")
            String stationListBaseUrl,

            @NotBlank @DefaultValue("https://api.irishrail.ie/realtime/realtime.asmx/getStationDataByCodeXML_WithNumMins?NumMins=90&StationCode=")
            String stationDataBaseUrl,

            @NotBlank @DefaultValue("https://api.irishrail.ie/realtime/realtime.asmx/getCurrentTrainsXML")
            String currentTrainsUrl,

            @NotBlank @DefaultValue("https://api.irishrail.ie/realtime/realtime.asmx/getTrainMovementsXML")
            String trainMovementsUrl,

            @Min(1) @DefaultValue("30000") long routeCacheMs,
            @Min(1) @DefaultValue("4000") long connectTimeoutMs,
            @Min(1) @DefaultValue("8000") long readTimeoutMs,
            @Min(1) @DefaultValue("3600000") long stationCacheMs,
            @Min(1) @DefaultValue("35000") long boardCacheMs,
            @Min(1) @DefaultValue("10000") long trainPositionsRefreshMs,

            /** Ceiling on the departure-board cache. ~140 collected stations × 2 filter variants. */
            @Min(1) @DefaultValue("400") long maxCachedBoards,

            /** Ceiling on the per-train route cache. A busy day sees a few hundred trains. */
            @Min(1) @DefaultValue("400") long maxCachedRoutes) {}

    /** The delay collector's fan-out. */
    public record Collector(
            @Min(1) @DefaultValue("30000") long intervalMs,
            @Min(1) @DefaultValue("8") int threads,
            @Min(1) @DefaultValue("512") int queueCapacity,
            @Min(1) @DefaultValue("120") long cycleBudgetSeconds) {}

    /** Tiered data retention: raw snapshots are ~75× costlier per day than the aggregates. */
    public record Retention(
            @Min(1) @DefaultValue("30") int days,
            /** 0 or less keeps aggregate history forever. */
            @DefaultValue("0") int aggregateDays,
            @Min(0) @DefaultValue("45000") long startupDelayMs) {}

    public record Analytics(
            @Min(1) @DefaultValue("10000") long overviewCacheMs,
            /** Ceiling on the overview payload cache; the key space is bounded by validation too. */
            @Min(1) @DefaultValue("200") long overviewCacheMaxEntries,
            @Valid @DefaultValue Aggregates aggregates) {

        public record Aggregates(
                @DefaultValue("true") boolean backfillOnStartup,
                @Min(1) @DefaultValue("60000") long refreshMs) {}
    }

    /** Leaflet basemap settings, read by the map template. */
    public record MapView(
            @NotBlank @DefaultValue("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}")
            String tileUrl,
            @DefaultValue("") String tileAttribution,
            @DefaultValue("none") String tileFilter,
            @Min(1) @DefaultValue("18") int tileMaxZoom,
            @DefaultValue("") String railOverlayUrl,
            @DefaultValue("") String railOverlayAttribution,
            @Min(1) @DefaultValue("10000") long refreshMs) {}

    public record Sse(
            @Min(1) @DefaultValue("25000") long heartbeatMs,
            /** Hard ceiling on concurrent event-stream subscribers; further ones get a 503. */
            @Min(1) @DefaultValue("500") int maxClients) {}

    /**
     * Token-bucket throttle on {@code /api/**}. These endpoints are public and run aggregation
     * queries, so a single client could previously generate disproportionate load.
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @Min(1) @DefaultValue("120") int requestsPerMinute,
            @Min(1) @DefaultValue("60") int burst,
            @Min(1) @DefaultValue("10000") long maxTrackedClients) {}

    /** Station types scanned for one service scope, e.g. {@code D} or {@code M,S}. */
    public record Scope(@DefaultValue({}) List<String> collectionStationTypes) {}
}
