package com.irishrail.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.AnalyticsView;
import com.irishrail.model.DashboardSummary;
import com.irishrail.model.DelayCategory;
import com.irishrail.model.DestinationStats;
import com.irishrail.model.HourlyStats;
import com.irishrail.model.RecentDelayEntry;
import com.irishrail.model.RouteStats;
import com.irishrail.model.ServiceScope;
import com.irishrail.model.StationStats;
import com.irishrail.model.TripDelaySummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The single place the analytics dashboards are assembled.
 *
 * <p>Its other job is the payload cache. That was a bare {@code ConcurrentHashMap} on the
 * controller, keyed on the raw {@code from}/{@code to}/{@code stationCode}/{@code period} request
 * parameters and never evicted — so a crawler walking
 * {@code /api/analytics/overview?from=<anything>} grew it without bound, each entry holding a full
 * analytics payload. The key is now a parsed, validated record (so the key space is finite by
 * construction) and the cache is bounded and self-expiring.
 */
@Service
public class AnalyticsQueryService {

    private final DelayTrackingService delays;
    private final Cache<Request, AnalyticsView> cache;

    public AnalyticsQueryService(DelayTrackingService delays, IrishRailProperties properties) {
        this.delays = delays;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.analytics().overviewCacheMaxEntries())
                .expireAfterWrite(Duration.ofMillis(properties.analytics().overviewCacheMs()))
                .recordStats()
                .build();
    }

    /**
     * What to compute.
     *
     * @param from                 inclusive start, or null for "since the beginning"
     * @param to                   inclusive end, or null for "up to today"
     * @param stationCode          service scope, or null for the whole network
     * @param limitStationRanking  true for the live board's top 15, false for every station
     * @param includeRecentDelays  the overview's live feed; the live board does not show it, and
     *                             asking for it there would be a query nothing renders
     */
    public record Request(LocalDate from, LocalDate to, String stationCode,
                          boolean limitStationRanking, boolean includeRecentDelays) {

        public Request {
            // Normalised here so that "cnlly", "CNLLY" and "Cnlly" are one cache entry, and an
            // unrecognised scope collapses to the same key as no scope at all.
            stationCode = ServiceScope.fromOverviewCode(stationCode);
        }

        public boolean scoped() {
            return stationCode != null;
        }

        /**
         * The overview's date rules, in one place because the page and its JSON refresh both need
         * them: {@code period=all} drops the bounds entirely, an explicit {@code from}/{@code to}
         * wins, and with neither the answer is today.
         */
        public static Request overview(LocalDate from, LocalDate to, String stationCode,
                                       String period, boolean limitStationRanking,
                                       boolean includeRecentDelays) {
            boolean allTime = "all".equalsIgnoreCase(period);
            boolean explicit = from != null || to != null;
            LocalDate today = LocalDate.now();
            return new Request(
                    allTime ? null : (explicit ? from : today),
                    allTime ? null : (explicit ? to : today),
                    stationCode, limitStationRanking, includeRecentDelays);
        }
    }

    /** Cached for {@code irishrail.analytics.overview-cache-ms}. */
    public AnalyticsView get(Request request) {
        return cache.get(request, this::build);
    }

    /** Bypasses the cache. Used by tests and by any caller that must see the current roll-up. */
    public AnalyticsView build(Request r) {
        LocalDate from = r.from();
        LocalDate to = r.to();
        String scope = r.stationCode();

        DashboardSummary dashboard;
        List<StationStats> stationRank;
        List<HourlyStats> hourly;
        List<TripDelaySummary> top10;
        List<DestinationStats> destinations;
        List<RouteStats> routes;
        Map<String, Long> categories;

        if (r.scoped()) {
            dashboard = delays.getDashboardSummaryForStation(from, to, scope);
            stationRank = delays.getAllStationRankingForStation(from, to, scope);
            hourly = delays.getHourlyStatsForStation(from, to, scope);
            top10 = delays.getTop10LargestDelaysForStation(from, to, scope);
            destinations = delays.getTopDestinationsByDelayForStation(from, to, scope);
            routes = delays.getTopRoutesByDelayForStation(from, to, scope);
            categories = delays.getDelayCategoriesForStation(from, to, scope);
        } else {
            dashboard = delays.getDashboardSummary(from, to);
            stationRank = r.limitStationRanking()
                    ? delays.getStationRanking(from, to)
                    : delays.getAllStationRanking(from, to);
            hourly = delays.getHourlyStats(from, to);
            top10 = delays.getTop10LargestDelays(from, to);
            destinations = delays.getTopDestinationsByDelay(from, to);
            routes = delays.getTopRoutesByDelay(from, to);
            categories = delays.getDelayCategories(from, to);
        }

        List<RecentDelayEntry> recent = List.of();
        if (r.includeRecentDelays()) {
            recent = r.scoped()
                    ? delays.getRecentDelayedTripsForStation(scope)
                    : delays.getRecentDelayedTrips();
        }

        long maxCatCount = categories.values().stream().mapToLong(Long::longValue).max().orElse(1L);

        return new AnalyticsView(
                dashboard,
                stationRank,
                hourly.stream().map(HourlyStats::getHourLabel).toList(),
                hourly.stream().map(HourlyStats::getDelayProbability).toList(),
                hourly.stream().map(HourlyStats::getAvgDelay).toList(),
                top10,
                routes,
                recent,
                destinations,
                destinations.stream().map(DestinationStats::getDestination).toList(),
                destinations.stream().map(DestinationStats::getAvgDelay).toList(),
                destinations.stream().map(DestinationStats::getDelayCount).toList(),
                categories,
                maxCatCount,
                dashboard.getOnTimeTrips(),
                count(categories, DelayCategory.SMALL_DELAY),
                count(categories, DelayCategory.MEDIUM_DELAY),
                count(categories, DelayCategory.BIG_DELAY),
                count(categories, DelayCategory.EXTREME_DELAY),
                hourly);
    }

    private static long count(Map<String, Long> categories, DelayCategory category) {
        return categories.getOrDefault(category.getDisplayLabel(), 0L);
    }
}
