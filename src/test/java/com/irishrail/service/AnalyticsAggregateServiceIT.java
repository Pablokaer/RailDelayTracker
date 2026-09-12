package com.irishrail.service;

import com.irishrail.model.DashboardSummary;
import com.irishrail.model.DelayCategory;
import com.irishrail.model.HourlyStats;
import com.irishrail.model.StationStats;
import com.irishrail.model.TripDelaySummary;
import com.irishrail.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roll-up, against a real PostgreSQL.
 *
 * <p>This is the code with the most leverage in the application and it had no test at all: three
 * multi-CTE UPSERTs whose output is durable and outlives the raw data it came from, so a wrong
 * aggregate is not recomputed away — it is simply wrong for as long as the history is kept.
 *
 * <p>The load-bearing assertion is {@link #incrementalRollUpMatchesAFullRefresh()}: the collector
 * only ever triggers the incremental path, while the startup backfill uses the full one, and
 * nothing previously checked that the two agree.
 */
class AnalyticsAggregateServiceIT extends PostgresIntegrationTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 3, 15);
    private static final String SCOPE = "CONNOLLY";

    @Autowired
    private AnalyticsAggregateService aggregates;

    @Autowired
    private JdbcTemplate jdbc;

    private long tripA;
    private long tripB;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE daily_station_route_metrics, daily_trip_metrics, daily_hourly_metrics");
        jdbc.execute("TRUNCATE trip_station_snapshot, trip RESTART IDENTITY CASCADE");

        tripA = insertTrip("A100", "15 Mar 2026", "Bray", "Howth");
        tripB = insertTrip("B200", "15 Mar 2026", "Malahide", "Greystones");

        // Trip A peaks at 12 minutes late — a "Medium Delay".
        insertSnapshot(tripA, "CNLLY", "Dublin Connolly", 0, at(8, 0));
        insertSnapshot(tripA, "CNLLY", "Dublin Connolly", 3, at(8, 10));
        insertSnapshot(tripA, "CNLLY", "Dublin Connolly", 12, at(8, 20));
        // Trip B never crosses the 5-minute threshold, so it counts as on time.
        insertSnapshot(tripB, "CNLLY", "Dublin Connolly", 0, at(9, 0));
        insertSnapshot(tripB, "CNLLY", "Dublin Connolly", 2, at(9, 30));
    }

    @Test
    void fullRefreshSummarisesEachTripByItsPeakDelay() {
        aggregates.refreshAll();

        DashboardSummary dashboard = aggregates.dashboard(SERVICE_DATE, SERVICE_DATE);
        assertThat(dashboard.getUniqueTrips()).isEqualTo(2);
        assertThat(dashboard.getDelayedTrips()).isEqualTo(1);
        assertThat(dashboard.getOnTimeTrips()).isEqualTo(1);
        assertThat(dashboard.getTotalSnapshots()).isEqualTo(5);
        assertThat(dashboard.getMaxDelay()).isEqualTo(12);
        // The average is over delayed trips only, not over every trip.
        assertThat(dashboard.getAverageDelay()).isEqualTo(12.0);
    }

    @Test
    void delayCategoriesUseTheBandsDeclaredOnTheEnum() {
        aggregates.refreshAll();

        Map<String, Long> categories = aggregates.delayCategories(SERVICE_DATE, SERVICE_DATE, null);
        assertThat(categories.get(DelayCategory.MEDIUM_DELAY.getDisplayLabel())).isEqualTo(1);
        assertThat(categories.get(DelayCategory.SMALL_DELAY.getDisplayLabel())).isZero();
        assertThat(categories.get(DelayCategory.BIG_DELAY.getDisplayLabel())).isZero();
        assertThat(categories.get(DelayCategory.EXTREME_DELAY.getDisplayLabel())).isZero();
        // Every delayed band is reported, so the UI legend and the stored columns cannot drift.
        assertThat(categories).hasSize(DelayCategory.delayedBands().size());
    }

    @Test
    void topDelaysReportsWhereAndWhenThePeakHappened() {
        aggregates.refreshAll();

        List<TripDelaySummary> top = aggregates.topDelays(SERVICE_DATE, SERVICE_DATE, null);
        assertThat(top).hasSize(1);
        assertThat(top.get(0).getTrainCode()).isEqualTo("A100");
        assertThat(top.get(0).getPeakDelayMinutes()).isEqualTo(12);
        assertThat(top.get(0).getStationFullName()).isEqualTo("Dublin Connolly");
        assertThat(top.get(0).getCapturedAt()).isEqualTo("08:20");
    }

    @Test
    void hourlyMetricsSplitSnapshotsByCaptureHour() {
        aggregates.refreshAll();

        List<HourlyStats> hourly = aggregates.hourly(SERVICE_DATE, SERVICE_DATE, null);
        Map<Integer, HourlyStats> byHour = hourly.stream()
                .collect(java.util.stream.Collectors.toMap(HourlyStats::getHour, h -> h));

        assertThat(byHour.get(8).getTotalCount()).isEqualTo(3);
        // delayed_snapshots counts any late_minutes > 0, which is a different question from the
        // 5-minute "delayed trip" threshold used everywhere else.
        assertThat(byHour.get(8).getDelayCount()).isEqualTo(2);
        assertThat(byHour.get(9).getTotalCount()).isEqualTo(2);
        assertThat(byHour.get(9).getDelayCount()).isEqualTo(1);
    }

    /**
     * {@code daily_station_route_metrics} carries a counter per delay band that no read path
     * currently selects, so nothing else would notice if the generated column list, the value
     * expressions and the ON CONFLICT assignment fell out of alignment — the INSERT would still
     * succeed, writing each band's count into the neighbouring band's column.
     */
    @Test
    void stationRouteBandCountersLandInTheColumnTheyBelongTo() {
        aggregates.refreshAll();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT small_delay_trips, medium_delay_trips, big_delay_trips, extreme_delay_trips
                FROM daily_station_route_metrics
                WHERE service_date = ? AND station_code = 'CNLLY' AND destination = 'Howth'
                """, java.sql.Date.valueOf(SERVICE_DATE));

        // Trip A peaked at 12 minutes: Medium Delay, and nothing else.
        assertThat(((Number) row.get("small_delay_trips")).longValue()).isZero();
        assertThat(((Number) row.get("medium_delay_trips")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("big_delay_trips")).longValue()).isZero();
        assertThat(((Number) row.get("extreme_delay_trips")).longValue()).isZero();
    }

    @Test
    void stationRankingAggregatesAcrossRoutes() {
        aggregates.refreshAll();

        List<StationStats> ranking = aggregates.stationRanking(SERVICE_DATE, SERVICE_DATE, false);
        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).getStationCode()).isEqualTo("CNLLY");
        assertThat(ranking.get(0).getTotalTrips()).isEqualTo(2);
        assertThat(ranking.get(0).getDelayedTrips()).isEqualTo(1);
    }

    /**
     * The collector never calls {@code refreshAll}: it reports the trips a cycle touched and the
     * timer rolls just those up, against a window restricted by trip id and by hour. If that
     * narrowing is wrong the aggregates are quietly partial, and no other test would notice.
     */
    @Test
    void incrementalRollUpMatchesAFullRefresh() {
        aggregates.refreshAll();

        // A late-running trip A, plus a trip that did not exist when the backfill ran.
        insertSnapshot(tripA, "CNLLY", "Dublin Connolly", 25, at(8, 30));
        long tripC = insertTrip("C300", "15 Mar 2026", "Bray", "Howth");
        insertSnapshot(tripC, "MHIDE", "Malahide", 47, at(10, 5));

        aggregates.markDirty(SERVICE_DATE, Set.of(tripA, tripC), at(8, 30));
        aggregates.flushDirty();

        DashboardSummary incremental = aggregates.dashboard(SERVICE_DATE, SERVICE_DATE);
        Map<String, Long> incrementalCategories = aggregates.delayCategories(SERVICE_DATE, SERVICE_DATE, null);
        List<StationStats> incrementalRanking = aggregates.stationRanking(SERVICE_DATE, SERVICE_DATE, false);
        List<HourlyStats> incrementalHourly = aggregates.hourly(SERVICE_DATE, SERVICE_DATE, null);

        // Now derive the same day from scratch and require an identical answer.
        aggregates.refreshAll();

        assertThat(incremental.getUniqueTrips())
                .isEqualTo(aggregates.dashboard(SERVICE_DATE, SERVICE_DATE).getUniqueTrips());
        assertThat(incremental.getDelayedTrips())
                .isEqualTo(aggregates.dashboard(SERVICE_DATE, SERVICE_DATE).getDelayedTrips());
        assertThat(incremental.getMaxDelay())
                .isEqualTo(aggregates.dashboard(SERVICE_DATE, SERVICE_DATE).getMaxDelay());
        assertThat(incremental.getTotalSnapshots())
                .isEqualTo(aggregates.dashboard(SERVICE_DATE, SERVICE_DATE).getTotalSnapshots());
        assertThat(incrementalCategories)
                .isEqualTo(aggregates.delayCategories(SERVICE_DATE, SERVICE_DATE, null));
        assertThat(incrementalHourly).usingRecursiveComparison()
                .isEqualTo(aggregates.hourly(SERVICE_DATE, SERVICE_DATE, null));
        assertThat(incrementalRanking).usingRecursiveComparison()
                .isEqualTo(aggregates.stationRanking(SERVICE_DATE, SERVICE_DATE, false));

        // And sanity-check that it actually observed the new data rather than agreeing on nothing.
        assertThat(incremental.getUniqueTrips()).isEqualTo(3);
        assertThat(incremental.getMaxDelay()).isEqualTo(47);
        assertThat(incrementalRanking).extracting(StationStats::getStationCode)
                .containsExactlyInAnyOrder("CNLLY", "MHIDE");
    }

    /**
     * Aggregates are kept long after the raw snapshots they came from are deleted; a refresh that
     * re-derived everything from raw would erase that history. This is the property that lets raw
     * retention be 30 days while the dashboards keep years.
     */
    @Test
    void refreshLeavesAggregatesWhoseRawDataIsGone() {
        aggregates.refreshAll();
        assertThat(aggregates.dashboard(SERVICE_DATE, SERVICE_DATE).getUniqueTrips()).isEqualTo(2);

        jdbc.update("DELETE FROM trip_station_snapshot");
        aggregates.refreshAll();

        assertThat(aggregates.dashboard(SERVICE_DATE, SERVICE_DATE).getUniqueTrips())
                .as("aggregate history must survive the trimming of raw snapshots")
                .isEqualTo(2);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static LocalDateTime at(int hour, int minute) {
        return SERVICE_DATE.atTime(hour, minute);
    }

    private long insertTrip(String trainCode, String trainDate, String origin, String destination) {
        return jdbc.queryForObject("""
                INSERT INTO trip (train_code, train_date, train_type, origin, destination, direction)
                VALUES (?, ?, 'Train', ?, ?, 'Northbound')
                RETURNING id
                """, Long.class, trainCode, trainDate, origin, destination);
    }

    private void insertSnapshot(long tripId, String stationCode, String stationName,
                                int lateMinutes, LocalDateTime capturedAt) {
        jdbc.update("""
                INSERT INTO trip_station_snapshot
                    (trip_id, station_code, station_full_name, service_scope,
                     sch_depart, sch_arrival, exp_depart, exp_arrival,
                     late_minutes, status, captured_at)
                VALUES (?, ?, ?, ?, '08:00', '08:00', '08:00', '08:00', ?, 'En Route', ?)
                """, tripId, stationCode, stationName, SCOPE, lateMinutes, capturedAt);
    }
}
