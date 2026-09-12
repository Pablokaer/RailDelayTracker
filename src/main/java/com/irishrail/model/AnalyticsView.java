package com.irishrail.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * One analytics answer, for one date range and scope.
 *
 * <p>This used to be built three times over: once into the model of {@code /get}, once into the
 * model of {@code /overview}, and once into a hand-assembled {@code LinkedHashMap} for
 * {@code /api/analytics/overview} — the same seven queries and the same category arithmetic in
 * three places, which is how a page and its own JSON refresh could disagree.
 *
 * <p>The component names and their order are the JSON contract consumed by {@code overview.js};
 * they match the old map key-for-key. {@link #hourly} is the one addition, for the server-rendered
 * table, and is excluded from the wire format.
 */
public record AnalyticsView(

        DashboardSummary dashboard,
        List<StationStats> stationRank,
        List<String> hourlyLabels,
        List<Double> hourlyDelayPcts,
        List<Double> hourlyAvgDelays,
        List<TripDelaySummary> top10Delays,
        List<RouteStats> routeRanking,
        List<RecentDelayEntry> recentDelays,
        List<DestinationStats> destinations,
        List<String> destLabels,
        List<Double> destAvgDelays,
        List<Long> destDelayCounts,
        Map<String, Long> categories,
        long maxCatCount,
        long catOnTime,
        long catSmall,
        long catMedium,
        long catBig,
        long catExtreme,

        /** Server-rendered hourly table; the JSON clients only ever used the three arrays above. */
        @JsonIgnore List<HourlyStats> hourly) {
}
