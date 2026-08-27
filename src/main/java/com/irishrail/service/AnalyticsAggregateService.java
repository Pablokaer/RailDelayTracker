package com.irishrail.service;

import com.irishrail.model.DashboardSummary;
import com.irishrail.model.DelayCategory;
import com.irishrail.model.DelayLimits;
import com.irishrail.model.DestinationStats;
import com.irishrail.model.HourlyStats;
import com.irishrail.model.RouteStats;
import com.irishrail.model.ServiceScope;
import com.irishrail.model.StationStats;
import com.irishrail.model.TripDelaySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Rolls raw snapshots up into three daily aggregate tables and answers every date-ranged analytics
 * question from them.
 *
 * <h2>Why this exists</h2>
 * Measured on a live database: raw snapshots cost ~278 bytes each and arrive at ~120k/day
 * (~33 MB/day), while the three tables below cost ~440 kB/day in total. Serving analytics from the
 * aggregates is what lets raw retention drop to weeks while the dashboards keep years of history.
 *
 * <p>Two things previously prevented that:
 * <ul>
 *   <li>{@code ensureSchema} dropped {@code daily_station_route_metrics} on every boot and
 *       {@code refreshAll} rebuilt it from raw — so aggregate history could never outlive the raw
 *       window. A restart after trimming raw data would have silently erased the older history.</li>
 *   <li>Only the station ranking actually read the aggregate. The dashboard summary, destinations,
 *       routes, delay categories, hourly analysis and top-10 all scanned raw snapshots, so they
 *       would all have become limited to the raw window too.</li>
 * </ul>
 *
 * <h2>Grain</h2>
 * <ul>
 *   <li>{@code daily_station_route_metrics} — date × scope × station × route. Station ranking.</li>
 *   <li>{@code daily_trip_metrics} — date × scope × trip (~700 rows/day). Dashboard, categories,
 *       destinations, routes, top-10 and daily delays. Carries each trip's peak delay and where it
 *       happened.</li>
 *   <li>{@code daily_hourly_metrics} — date × scope × hour (48 rows/day). Hourly analysis.</li>
 * </ul>
 *
 * <p>All three are written with UPSERTs keyed on their grain, so re-running a date is idempotent and
 * never touches another date's rows.
 *
 * <p><strong>Schema changes need care:</strong> these tables are now durable, so adding a column
 * means an {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} in {@link #ensureSchema()} rather than
 * relying on a drop-and-rebuild.
 */
@Service
public class AnalyticsAggregateService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAggregateService.class);
    private static final int DELAYED_MIN = DelayCategory.delayedThreshold();
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Resolves a snapshot's service scope identically in every rollup, falling back to the
     * origin/destination heuristic for rows written before {@code service_scope} was populated.
     */
    private static final String SCOPE_EXPR =
            "COALESCE(s.service_scope, CASE WHEN UPPER(s.station_code) = 'HSTON'"
            + " OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'"
            + " OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'"
            + " THEN 'HEUSTON' ELSE 'CONNOLLY' END)";

    private static final String ALL_SCOPES_FILTER = "service_scope IN (:serviceScopes)";
    private static final String ONE_SCOPE_FILTER = "service_scope = :serviceScope";

    /**
     * Serialises every aggregate write. The startup backfill and the collector's per-date refresh
     * run on different threads and UPSERT the same tables; because each acquires row locks in its
     * own insertion order, running them concurrently deadlocked and killed the application during
     * boot. A transaction-scoped advisory lock orders them and is released automatically on commit
     * or rollback — and unlike a JVM lock it also holds if more than one instance is running.
     */
    private static final long AGGREGATE_LOCK_KEY = 827_411_300_1L;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Used instead of {@code @Transactional} because these methods are called from
     * {@link #run(ApplicationArguments)} on the same bean: self-invocation bypasses the Spring
     * proxy, so the annotation never took effect and the refresh was not actually atomic.
     */
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Value("${irishrail.analytics.aggregates.backfill-on-startup:true}")
    private boolean backfillOnStartup;

    public AnalyticsAggregateService(NamedParameterJdbcTemplate jdbc,
                                     PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureSchema();
        if (backfillOnStartup) {
            refreshAll();
        }
    }

    public List<String> serviceScopes() {
        return List.of(ServiceScope.CONNOLLY, ServiceScope.HEUSTON);
    }

    // ── write ─────────────────────────────────────────────────────────────────

    /**
     * Re-derives every date still present in raw snapshots. Aggregate rows for dates whose raw data
     * has already been trimmed are left untouched — that is the whole point of keeping them.
     */
    public void refreshAll() {
        transactions.executeWithoutResult(status -> {
            lockAggregates();

            List<LocalDate> dates = jdbc.queryForList("""
                    SELECT DISTINCT CAST(captured_at AS date) AS service_date
                    FROM trip_station_snapshot
                    ORDER BY service_date
                    """, params(), LocalDate.class);

            if (dates.isEmpty()) {
                log.info("Analytics aggregates: no raw snapshots to roll up");
                return;
            }

            int stationRows = insertStationRouteAggregates(Window.all());
            int tripRows = insertTripAggregates(Window.all());
            int hourRows = insertHourlyAggregates(Window.all());
            log.info("Analytics aggregates refreshed from {} day(s) of raw data ({} to {}): "
                            + "{} station-route rows, {} trip rows, {} hourly rows",
                    dates.size(), dates.get(0), dates.get(dates.size() - 1),
                    stationRows, tripRows, hourRows);
        });
    }

    // ── incremental roll-up ───────────────────────────────────────────────────
    //
    // The collector used to re-derive the whole current day after every cycle: three INSERT …
    // SELECTs over every snapshot captured so far, 2 880 times a day, each joining trip on a nested
    // loop. That single pattern accounted for 658 million trip_pkey lookups in six weeks and grew
    // linearly through the day. Cycles now only report which trips they touched; a timer rolls
    // those up, restricted to the aggregate rows those trips can possibly have changed.

    /** Trips changed since the last roll-up, per service date. Guarded by {@link #dirtyLock}. */
    private static final class DirtyDay {
        final Set<Long> tripIds = new HashSet<>();
        LocalDateTime earliest;
    }

    private final Object dirtyLock = new Object();
    private final Map<LocalDate, DirtyDay> dirty = new HashMap<>();

    /** Called by the collector after each persisted batch. Cheap: a set union under a lock. */
    public void markDirty(LocalDate date, Set<Long> tripIds, LocalDateTime capturedAt) {
        if (date == null || tripIds == null || tripIds.isEmpty()) return;
        synchronized (dirtyLock) {
            DirtyDay day = dirty.computeIfAbsent(date, k -> new DirtyDay());
            day.tripIds.addAll(tripIds);
            if (day.earliest == null || capturedAt.isBefore(day.earliest)) day.earliest = capturedAt;
        }
    }

    @Scheduled(fixedDelayString = "${irishrail.analytics.aggregates.refresh-ms:60000}")
    public void flushDirty() {
        Map<LocalDate, DirtyDay> work;
        synchronized (dirtyLock) {
            if (dirty.isEmpty()) return;
            work = new HashMap<>(dirty);
            dirty.clear();
        }
        for (Map.Entry<LocalDate, DirtyDay> entry : work.entrySet()) {
            try {
                if (!refreshIncremental(entry.getKey(), entry.getValue())) requeue(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                log.warn("Aggregate roll-up for {} failed, will retry: {}", entry.getKey(), e.getMessage());
                requeue(entry.getKey(), entry.getValue());
            }
        }
    }

    /** @return false when another writer (the startup backfill) holds the lock; nothing was lost. */
    private boolean refreshIncremental(LocalDate date, DirtyDay day) {
        long startedAt = System.nanoTime();
        Boolean done = transactions.execute(status -> {
            if (!tryLockAggregates()) return false;
            Window window = new Window(date, date.plusDays(1),
                    Set.copyOf(day.tripIds), day.earliest.truncatedTo(ChronoUnit.HOURS));
            int stationRows = insertStationRouteAggregates(window);
            int tripRows = insertTripAggregates(window);
            int hourRows = insertHourlyAggregates(window);
            log.debug("Aggregates {}: {} trips -> {} station-route, {} trip, {} hourly rows in {} ms",
                    date, day.tripIds.size(), stationRows, tripRows, hourRows,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private void requeue(LocalDate date, DirtyDay day) {
        markDirty(date, day.tripIds, day.earliest);
    }

    /**
     * What one roll-up pass reads. {@link #all()} is the full backfill; the incremental form limits
     * each aggregate to the rows a set of changed trips can have altered:
     * <ul>
     *   <li>trip metrics are keyed by trip, so {@code trip_id IN (…)} is exact;</li>
     *   <li>station-route metrics sum across trips, so every (station, origin, destination) group any
     *       changed trip appears in is recomputed in full — a superset, never a partial total;</li>
     *   <li>hourly metrics are recomputed from the hour of the earliest changed row onwards.</li>
     * </ul>
     */
    private record Window(LocalDate from, LocalDate to, Set<Long> tripIds, LocalDateTime hourStart) {
        static Window all() { return new Window(null, null, null, null); }

        boolean incremental() { return tripIds != null && !tripIds.isEmpty(); }

        String dateFilter() {
            return from == null || to == null ? "" : " AND s.captured_at >= :fromTs AND s.captured_at < :toTs";
        }

        String tripFilter() {
            return incremental() ? " AND s.trip_id IN (:tripIds)" : "";
        }

        String stationRouteFilter() {
            if (!incremental()) return "";
            // Leading newline: this follows dateFilter(), whose last token is a bind parameter.
            return """

                     AND (UPPER(s.station_code),
                          COALESCE(NULLIF(t.origin, ''), 'Unknown'),
                          COALESCE(NULLIF(t.destination, ''), 'Unknown')) IN (
                        SELECT UPPER(s2.station_code),
                               COALESCE(NULLIF(t2.origin, ''), 'Unknown'),
                               COALESCE(NULLIF(t2.destination, ''), 'Unknown')
                        FROM trip_station_snapshot s2
                        JOIN trip t2 ON t2.id = s2.trip_id
                        WHERE s2.trip_id IN (:tripIds)
                          AND s2.captured_at >= :fromTs AND s2.captured_at < :toTs)""";
        }

        String hourFilter() {
            return hourStart == null ? "" : " AND s.captured_at >= :hourStart";
        }
    }

    /** Trims aggregate history, on its own cutoff independent of raw retention. */
    public int deleteBefore(LocalDate cutoffDate) {
        if (cutoffDate == null) return 0;
        return transactions.execute(status -> {
            lockAggregates();
            MapSqlParameterSource p = params().addValue("cutoffDate", Date.valueOf(cutoffDate));
            int rows = 0;
            for (String table : List.of("daily_station_route_metrics", "daily_trip_metrics", "daily_hourly_metrics")) {
                rows += jdbc.update("DELETE FROM " + table + " WHERE service_date < :cutoffDate", p);
            }
            return rows;
        });
    }

    /** Blocks until no other aggregate write is in flight. Released when this transaction ends. */
    private void lockAggregates() {
        jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(" + AGGREGATE_LOCK_KEY + ")");
    }

    private boolean tryLockAggregates() {
        Boolean acquired = jdbc.getJdbcTemplate().queryForObject(
                "SELECT pg_try_advisory_xact_lock(" + AGGREGATE_LOCK_KEY + ")", Boolean.class);
        return Boolean.TRUE.equals(acquired);
    }

    // ── read: dashboard summary ───────────────────────────────────────────────

    public DashboardSummary dashboard(LocalDate from, LocalDate to) {
        return dashboardInternal(rangeParams(from, to), ALL_SCOPES_FILTER);
    }

    public DashboardSummary dashboardForStation(LocalDate from, LocalDate to, String stationCode) {
        return dashboardInternal(
                rangeParams(from, to).addValue("serviceScope", serviceScope(stationCode)),
                ONE_SCOPE_FILTER);
    }

    private DashboardSummary dashboardInternal(MapSqlParameterSource p, String scopeFilter) {
        String sql = """
                WITH trip_peaks AS (
                    SELECT trip_id,
                           MAX(peak_delay) AS peak_delay,
                           SUM(snapshot_count) AS snapshot_count
                    FROM daily_trip_metrics
                    WHERE service_date >= :fromDate AND service_date < :toDate
                      AND {scopeFilter}
                    GROUP BY trip_id
                )
                SELECT COALESCE(SUM(snapshot_count), 0) AS total_snapshots,
                       COUNT(*) AS unique_trips,
                       COALESCE(SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END), 0) AS delayed_trips,
                       COALESCE(SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END), 0) AS total_delay_minutes,
                       COALESCE(MAX(peak_delay), 0) AS max_delay
                FROM trip_peaks
                """.replace("{scopeFilter}", scopeFilter);

        return jdbc.queryForObject(sql, p, (rs, rowNum) -> {
            long delayed = rs.getLong("delayed_trips");
            double avg = delayed > 0 ? rs.getDouble("total_delay_minutes") / delayed : 0.0;
            return new DashboardSummary(
                    rs.getLong("total_snapshots"),
                    rs.getLong("unique_trips"),
                    delayed,
                    avg,
                    rs.getInt("max_delay")
            );
        });
    }

    // ── read: station ranking ─────────────────────────────────────────────────

    public List<StationStats> stationRanking(LocalDate from, LocalDate to, boolean limit) {
        return stationRankingInternal(rangeParams(from, to), ALL_SCOPES_FILTER, limit);
    }

    public List<StationStats> stationRankingForScope(LocalDate from, LocalDate to, String overviewCode, boolean limit) {
        return stationRankingInternal(
                rangeParams(from, to).addValue("serviceScope", serviceScope(overviewCode)),
                ONE_SCOPE_FILTER, limit);
    }

    private List<StationStats> stationRankingInternal(MapSqlParameterSource p, String scopeFilter, boolean limit) {
        String sql = """
                SELECT station_code,
                       MAX(station_full_name) AS station_full_name,
                       COALESCE(SUM(unique_trips), 0) AS total_trips,
                       COALESCE(SUM(delayed_trips), 0) AS delayed_trips,
                       COALESCE(SUM(total_delay_minutes), 0) AS total_delay_minutes
                FROM daily_station_route_metrics
                WHERE service_date >= :fromDate AND service_date < :toDate
                  AND {scopeFilter}
                GROUP BY station_code
                ORDER BY delayed_trips DESC,
                         CASE WHEN SUM(delayed_trips) > 0
                              THEN SUM(total_delay_minutes)::float / SUM(delayed_trips)
                              ELSE 0 END DESC,
                         station_code
                {limit}
                """
                .replace("{scopeFilter}", scopeFilter)
                .replace("{limit}", limit ? "LIMIT 15" : "");

        return jdbc.query(sql, p, (rs, rowNum) -> {
            long delayed = rs.getLong("delayed_trips");
            double avg = delayed > 0 ? rs.getDouble("total_delay_minutes") / delayed : 0.0;
            return new StationStats(
                    rs.getString("station_code"),
                    rs.getString("station_full_name"),
                    rs.getLong("total_trips"),
                    delayed,
                    avg,
                    rs.getLong("total_delay_minutes")
            );
        });
    }

    // ── read: hourly ──────────────────────────────────────────────────────────

    public List<HourlyStats> hourly(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String sql = """
                SELECT hour_of_day AS hour,
                       COALESCE(SUM(snapshot_count), 0) AS total,
                       COALESCE(SUM(delayed_snapshots), 0) AS delayed,
                       CASE WHEN SUM(delayed_snapshots) > 0
                            THEN SUM(total_delay_minutes)::float / SUM(delayed_snapshots)
                            ELSE 0 END AS avg_delay
                FROM daily_hourly_metrics
                WHERE service_date >= :fromDate AND service_date < :toDate
                  AND {scopeFilter}
                GROUP BY hour_of_day
                ORDER BY hour_of_day
                """.replace("{scopeFilter}", scopeFilter(p, stationCode));

        return jdbc.query(sql, p, (rs, rowNum) -> new HourlyStats(
                rs.getInt("hour"),
                rs.getLong("total"),
                rs.getLong("delayed"),
                rs.getDouble("avg_delay")));
    }

    // ── read: top 10 trips by peak delay ──────────────────────────────────────

    public List<TripDelaySummary> topDelays(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String sql = """
                WITH scoped AS (
                    SELECT *
                    FROM daily_trip_metrics
                    WHERE service_date >= :fromDate AND service_date < :toDate
                      AND {scopeFilter}
                ),
                trip_peaks AS (
                    SELECT trip_id,
                           MAX(peak_delay) AS peak_delay,
                           SUM(snapshot_count) AS snapshot_count
                    FROM scoped
                    GROUP BY trip_id
                    HAVING MAX(peak_delay) >= :minDelay
                ),
                ranked AS (
                    SELECT trip_id, peak_delay, snapshot_count
                    FROM trip_peaks
                    ORDER BY peak_delay DESC, trip_id
                    LIMIT 10
                ),
                peak_rows AS (
                    SELECT DISTINCT ON (s.trip_id)
                           s.trip_id, s.train_code, s.train_date, s.direction,
                           s.origin, s.destination,
                           s.peak_station_name, s.peak_sch_depart, s.peak_sch_arrival,
                           s.peak_captured_at
                    FROM scoped s
                    JOIN ranked r ON r.trip_id = s.trip_id AND r.peak_delay = s.peak_delay
                    ORDER BY s.trip_id, s.peak_captured_at ASC
                )
                SELECT pr.train_code, pr.peak_station_name, pr.train_date,
                       pr.peak_sch_depart, pr.peak_sch_arrival,
                       pr.direction, pr.origin, pr.destination,
                       r.peak_delay, r.snapshot_count, pr.peak_captured_at
                FROM ranked r
                JOIN peak_rows pr ON pr.trip_id = r.trip_id
                ORDER BY r.peak_delay DESC, r.trip_id
                """.replace("{scopeFilter}", scopeFilter(p, stationCode));

        return jdbc.query(sql, p, (rs, rowNum) -> new TripDelaySummary(
                rs.getString("train_code"),
                rs.getString("peak_station_name"),
                rs.getString("train_date"),
                rs.getString("peak_sch_depart"),
                rs.getString("peak_sch_arrival"),
                rs.getString("direction"),
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getInt("peak_delay"),
                rs.getLong("snapshot_count"),
                formatTime(rs.getTimestamp("peak_captured_at"))));
    }

    // ── read: destinations ────────────────────────────────────────────────────

    public List<DestinationStats> destinations(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String sql = """
                WITH trip_peaks AS (
                    SELECT trip_id,
                           MAX(destination) AS destination,
                           MAX(peak_delay) AS peak_delay
                    FROM daily_trip_metrics
                    WHERE service_date >= :fromDate AND service_date < :toDate
                      AND {scopeFilter}
                    GROUP BY trip_id
                )
                SELECT destination,
                       COUNT(*) AS total_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) AS delayed_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END) AS total_delay_minutes
                FROM trip_peaks
                GROUP BY destination
                HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
                ORDER BY SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)::float
                         / SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) DESC
                LIMIT 10
                """.replace("{scopeFilter}", scopeFilter(p, stationCode));

        return jdbc.query(sql, p, (rs, rowNum) -> {
            long delayed = rs.getLong("delayed_trips");
            double avg = delayed > 0 ? rs.getDouble("total_delay_minutes") / delayed : 0.0;
            return new DestinationStats(
                    rs.getString("destination"),
                    rs.getLong("total_trips"),
                    delayed,
                    avg
            );
        });
    }

    // ── read: routes ──────────────────────────────────────────────────────────

    public List<RouteStats> routes(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String sql = """
                WITH trip_peaks AS (
                    SELECT trip_id,
                           MAX(origin) AS origin,
                           MAX(destination) AS destination,
                           MAX(peak_delay) AS peak_delay
                    FROM daily_trip_metrics
                    WHERE service_date >= :fromDate AND service_date < :toDate
                      AND {scopeFilter}
                    GROUP BY trip_id
                )
                SELECT origin,
                       destination,
                       COUNT(*) AS total_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) AS delayed_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END) AS total_delay_minutes
                FROM trip_peaks
                GROUP BY origin, destination
                HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
                ORDER BY total_delay_minutes DESC
                LIMIT 15
                """.replace("{scopeFilter}", scopeFilter(p, stationCode));

        return jdbc.query(sql, p, (rs, rowNum) -> {
            long delayed = rs.getLong("delayed_trips");
            double avg = delayed > 0 ? rs.getDouble("total_delay_minutes") / delayed : 0.0;
            return new RouteStats(
                    rs.getString("origin"),
                    rs.getString("destination"),
                    rs.getLong("total_trips"),
                    delayed,
                    avg,
                    rs.getLong("total_delay_minutes")
            );
        });
    }

    // ── read: delay categories ────────────────────────────────────────────────

    public Map<String, Long> delayCategories(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String sql = """
                WITH trip_peaks AS (
                    SELECT trip_id, MAX(peak_delay) AS peak_delay
                    FROM daily_trip_metrics
                    WHERE service_date >= :fromDate AND service_date < :toDate
                      AND {scopeFilter}
                    GROUP BY trip_id
                )
                SELECT COALESCE(SUM(CASE WHEN peak_delay BETWEEN 5 AND 9 THEN 1 ELSE 0 END), 0) AS small_delay_trips,
                       COALESCE(SUM(CASE WHEN peak_delay BETWEEN 10 AND 19 THEN 1 ELSE 0 END), 0) AS medium_delay_trips,
                       COALESCE(SUM(CASE WHEN peak_delay BETWEEN 20 AND 39 THEN 1 ELSE 0 END), 0) AS big_delay_trips,
                       COALESCE(SUM(CASE WHEN peak_delay >= 40 THEN 1 ELSE 0 END), 0) AS extreme_delay_trips
                FROM trip_peaks
                """.replace("{scopeFilter}", scopeFilter(p, stationCode));

        Map<String, Object> row = jdbc.queryForMap(sql, p);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(DelayCategory.SMALL_DELAY.getDisplayLabel(), ((Number) row.get("small_delay_trips")).longValue());
        result.put(DelayCategory.MEDIUM_DELAY.getDisplayLabel(), ((Number) row.get("medium_delay_trips")).longValue());
        result.put(DelayCategory.BIG_DELAY.getDisplayLabel(), ((Number) row.get("big_delay_trips")).longValue());
        result.put(DelayCategory.EXTREME_DELAY.getDisplayLabel(), ((Number) row.get("extreme_delay_trips")).longValue());
        return result;
    }

    // ── read: delayed trips per service date ──────────────────────────────────

    public Map<String, Long> dailyDelays(LocalDate from, LocalDate to) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("""
                WITH trip_peaks AS (
                    SELECT trip_id,
                           MAX(train_date) AS train_date,
                           MAX(peak_delay) AS peak_delay,
                           MIN(first_captured_at) AS first_seen
                    FROM daily_trip_metrics
                    WHERE service_date >= :fromDate AND service_date < :toDate
                      AND service_scope IN (:serviceScopes)
                      AND train_date IS NOT NULL AND train_date <> ''
                    GROUP BY trip_id
                )
                SELECT train_date,
                       COUNT(*) AS delayed_trip_count
                FROM trip_peaks
                WHERE peak_delay >= :minDelay
                GROUP BY train_date
                ORDER BY MIN(first_seen) DESC
                LIMIT 30
                """, rangeParams(from, to),
                rs -> { result.put(rs.getString("train_date"), rs.getLong("delayed_trip_count")); });
        return result;
    }

    // ── schema ────────────────────────────────────────────────────────────────

    private void ensureSchema() {
        // Deliberately no DROP TABLE: these tables outlive the raw snapshots they came from.
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS daily_station_route_metrics (
                    service_date date NOT NULL,
                    service_scope varchar(32) NOT NULL,
                    station_code varchar(32) NOT NULL,
                    station_full_name varchar(255),
                    origin varchar(255) NOT NULL,
                    destination varchar(255) NOT NULL,
                    total_snapshots bigint NOT NULL,
                    unique_trips bigint NOT NULL,
                    delayed_trips bigint NOT NULL,
                    on_time_trips bigint NOT NULL,
                    average_delay_minutes double precision NOT NULL,
                    max_delay_minutes integer NOT NULL,
                    total_delay_minutes bigint NOT NULL,
                    small_delay_trips bigint NOT NULL,
                    medium_delay_trips bigint NOT NULL,
                    big_delay_trips bigint NOT NULL,
                    extreme_delay_trips bigint NOT NULL,
                    updated_at timestamp NOT NULL,
                    PRIMARY KEY (service_date, service_scope, station_code, origin, destination)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS daily_trip_metrics (
                    service_date date NOT NULL,
                    service_scope varchar(32) NOT NULL,
                    trip_id bigint NOT NULL,
                    train_code varchar(32),
                    train_date varchar(32),
                    direction varchar(64),
                    origin varchar(255) NOT NULL,
                    destination varchar(255) NOT NULL,
                    peak_delay integer NOT NULL,
                    snapshot_count bigint NOT NULL,
                    first_captured_at timestamp,
                    peak_station_code varchar(32),
                    peak_station_name varchar(255),
                    peak_sch_depart varchar(16),
                    peak_sch_arrival varchar(16),
                    peak_captured_at timestamp,
                    updated_at timestamp NOT NULL,
                    PRIMARY KEY (service_date, service_scope, trip_id)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS daily_hourly_metrics (
                    service_date date NOT NULL,
                    service_scope varchar(32) NOT NULL,
                    hour_of_day smallint NOT NULL,
                    snapshot_count bigint NOT NULL,
                    delayed_snapshots bigint NOT NULL,
                    total_delay_minutes bigint NOT NULL,
                    updated_at timestamp NOT NULL,
                    PRIMARY KEY (service_date, service_scope, hour_of_day)
                )
                """);

        for (String indexSql : List.of(
                "CREATE INDEX IF NOT EXISTS idx_dsrm_scope_date_station ON daily_station_route_metrics (service_scope, service_date, station_code)",
                "CREATE INDEX IF NOT EXISTS idx_dsrm_date_scope ON daily_station_route_metrics (service_date, service_scope)",
                "CREATE INDEX IF NOT EXISTS idx_dtm_date_scope ON daily_trip_metrics (service_date, service_scope)",
                "CREATE INDEX IF NOT EXISTS idx_dtm_scope_peak ON daily_trip_metrics (service_scope, peak_delay DESC)",
                "CREATE INDEX IF NOT EXISTS idx_dtm_trip ON daily_trip_metrics (trip_id)",
                "CREATE INDEX IF NOT EXISTS idx_dhm_date_scope ON daily_hourly_metrics (service_date, service_scope)")) {
            jdbc.getJdbcTemplate().execute(indexSql);
        }

        // routes() reads daily_trip_metrics now, so this index is pure write overhead.
        jdbc.getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_dsrm_scope_route_date");
    }

    // ── aggregate builders ────────────────────────────────────────────────────

    private int insertStationRouteAggregates(Window w) {
        String sql = """
                INSERT INTO daily_station_route_metrics (
                    service_date, service_scope, station_code, station_full_name, origin, destination,
                    total_snapshots, unique_trips, delayed_trips, on_time_trips,
                    average_delay_minutes, max_delay_minutes, total_delay_minutes,
                    small_delay_trips, medium_delay_trips, big_delay_trips, extreme_delay_trips,
                    updated_at
                )
                WITH trip_peaks AS (
                    SELECT CAST(s.captured_at AS date) AS service_date,
                           {scope} AS service_scope,
                           UPPER(s.station_code) AS station_code,
                           MAX(s.station_full_name) AS station_full_name,
                           COALESCE(NULLIF(t.origin, ''), 'Unknown') AS origin,
                           COALESCE(NULLIF(t.destination, ''), 'Unknown') AS destination,
                           s.trip_id,
                           COUNT(*) AS snapshot_count,
                           MAX(s.late_minutes) AS peak_delay
                    FROM trip_station_snapshot s
                    JOIN trip t ON t.id = s.trip_id
                    WHERE s.late_minutes <= :maxStatDelay
                      {dateFilter}
                    GROUP BY CAST(s.captured_at AS date),
                             {scope},
                             UPPER(s.station_code),
                             COALESCE(NULLIF(t.origin, ''), 'Unknown'),
                             COALESCE(NULLIF(t.destination, ''), 'Unknown'),
                             s.trip_id
                )
                SELECT service_date, service_scope, station_code,
                       MAX(station_full_name), origin, destination,
                       SUM(snapshot_count), COUNT(*),
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END),
                       SUM(CASE WHEN peak_delay < :minDelay THEN 1 ELSE 0 END),
                       COALESCE(AVG(CASE WHEN peak_delay >= :minDelay THEN peak_delay END), 0),
                       COALESCE(MAX(peak_delay), 0),
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END),
                       SUM(CASE WHEN peak_delay BETWEEN 5 AND 9 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN peak_delay BETWEEN 10 AND 19 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN peak_delay BETWEEN 20 AND 39 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN peak_delay >= 40 THEN 1 ELSE 0 END),
                       NOW()
                FROM trip_peaks
                GROUP BY service_date, service_scope, station_code, origin, destination
                ON CONFLICT (service_date, service_scope, station_code, origin, destination)
                DO UPDATE SET
                    station_full_name = EXCLUDED.station_full_name,
                    total_snapshots = EXCLUDED.total_snapshots,
                    unique_trips = EXCLUDED.unique_trips,
                    delayed_trips = EXCLUDED.delayed_trips,
                    on_time_trips = EXCLUDED.on_time_trips,
                    average_delay_minutes = EXCLUDED.average_delay_minutes,
                    max_delay_minutes = EXCLUDED.max_delay_minutes,
                    total_delay_minutes = EXCLUDED.total_delay_minutes,
                    small_delay_trips = EXCLUDED.small_delay_trips,
                    medium_delay_trips = EXCLUDED.medium_delay_trips,
                    big_delay_trips = EXCLUDED.big_delay_trips,
                    extreme_delay_trips = EXCLUDED.extreme_delay_trips,
                    updated_at = EXCLUDED.updated_at
                """
                .replace("{scope}", SCOPE_EXPR)
                .replace("{dateFilter}", w.dateFilter() + w.stationRouteFilter());

        return jdbc.update(sql, aggregateParams(w));
    }

    private int insertTripAggregates(Window w) {
        String sql = """
                INSERT INTO daily_trip_metrics (
                    service_date, service_scope, trip_id, train_code, train_date, direction,
                    origin, destination, peak_delay, snapshot_count, first_captured_at,
                    peak_station_code, peak_station_name, peak_sch_depart, peak_sch_arrival,
                    peak_captured_at, updated_at
                )
                WITH scoped AS (
                    SELECT CAST(s.captured_at AS date) AS service_date,
                           {scope} AS service_scope,
                           s.trip_id, s.station_code, s.station_full_name,
                           s.sch_depart, s.sch_arrival, s.late_minutes, s.captured_at
                    FROM trip_station_snapshot s
                    JOIN trip t ON t.id = s.trip_id
                    WHERE s.late_minutes <= :maxStatDelay
                      {dateFilter}
                ),
                peaks AS (
                    SELECT service_date, service_scope, trip_id,
                           MAX(late_minutes) AS peak_delay,
                           COUNT(*) AS snapshot_count,
                           MIN(captured_at) AS first_captured_at
                    FROM scoped
                    GROUP BY service_date, service_scope, trip_id
                ),
                peak_rows AS (
                    SELECT DISTINCT ON (sc.service_date, sc.service_scope, sc.trip_id)
                           sc.service_date, sc.service_scope, sc.trip_id,
                           sc.station_code, sc.station_full_name,
                           sc.sch_depart, sc.sch_arrival, sc.captured_at
                    FROM scoped sc
                    JOIN peaks pk ON pk.service_date = sc.service_date
                                 AND pk.service_scope = sc.service_scope
                                 AND pk.trip_id = sc.trip_id
                                 AND pk.peak_delay = sc.late_minutes
                    ORDER BY sc.service_date, sc.service_scope, sc.trip_id, sc.captured_at ASC
                )
                SELECT pk.service_date, pk.service_scope, pk.trip_id,
                       tr.train_code, tr.train_date, tr.direction,
                       COALESCE(NULLIF(tr.origin, ''), 'Unknown'),
                       COALESCE(NULLIF(tr.destination, ''), 'Unknown'),
                       pk.peak_delay, pk.snapshot_count, pk.first_captured_at,
                       pr.station_code, pr.station_full_name, pr.sch_depart, pr.sch_arrival,
                       pr.captured_at, NOW()
                FROM peaks pk
                JOIN trip tr ON tr.id = pk.trip_id
                JOIN peak_rows pr ON pr.service_date = pk.service_date
                                 AND pr.service_scope = pk.service_scope
                                 AND pr.trip_id = pk.trip_id
                ON CONFLICT (service_date, service_scope, trip_id)
                DO UPDATE SET
                    train_code = EXCLUDED.train_code,
                    train_date = EXCLUDED.train_date,
                    direction = EXCLUDED.direction,
                    origin = EXCLUDED.origin,
                    destination = EXCLUDED.destination,
                    peak_delay = EXCLUDED.peak_delay,
                    snapshot_count = EXCLUDED.snapshot_count,
                    first_captured_at = EXCLUDED.first_captured_at,
                    peak_station_code = EXCLUDED.peak_station_code,
                    peak_station_name = EXCLUDED.peak_station_name,
                    peak_sch_depart = EXCLUDED.peak_sch_depart,
                    peak_sch_arrival = EXCLUDED.peak_sch_arrival,
                    peak_captured_at = EXCLUDED.peak_captured_at,
                    updated_at = EXCLUDED.updated_at
                """
                .replace("{scope}", SCOPE_EXPR)
                .replace("{dateFilter}", w.dateFilter() + w.tripFilter());

        return jdbc.update(sql, aggregateParams(w));
    }

    private int insertHourlyAggregates(Window w) {
        String sql = """
                INSERT INTO daily_hourly_metrics (
                    service_date, service_scope, hour_of_day,
                    snapshot_count, delayed_snapshots, total_delay_minutes, updated_at
                )
                SELECT CAST(s.captured_at AS date),
                       {scope},
                       CAST(EXTRACT(HOUR FROM s.captured_at) AS smallint),
                       COUNT(*),
                       SUM(CASE WHEN s.late_minutes > 0 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN s.late_minutes > 0 THEN s.late_minutes ELSE 0 END),
                       NOW()
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes <= :maxStatDelay
                  {dateFilter}
                GROUP BY CAST(s.captured_at AS date),
                         {scope},
                         CAST(EXTRACT(HOUR FROM s.captured_at) AS smallint)
                ON CONFLICT (service_date, service_scope, hour_of_day)
                DO UPDATE SET
                    snapshot_count = EXCLUDED.snapshot_count,
                    delayed_snapshots = EXCLUDED.delayed_snapshots,
                    total_delay_minutes = EXCLUDED.total_delay_minutes,
                    updated_at = EXCLUDED.updated_at
                """
                .replace("{scope}", SCOPE_EXPR)
                .replace("{dateFilter}", w.dateFilter() + w.hourFilter());

        return jdbc.update(sql, aggregateParams(w));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Adds the scope parameter when a station is given, and returns the matching filter. */
    private String scopeFilter(MapSqlParameterSource p, String stationCode) {
        if (stationCode == null || stationCode.isBlank()) return ALL_SCOPES_FILTER;
        p.addValue("serviceScope", serviceScope(stationCode));
        return ONE_SCOPE_FILTER;
    }

    private MapSqlParameterSource aggregateParams(Window w) {
        MapSqlParameterSource p = params()
                .addValue("minDelay", DELAYED_MIN)
                .addValue("maxStatDelay", DelayLimits.MAX_STAT_DELAY_MINUTES);
        if (w.from() != null && w.to() != null) {
            p.addValue("fromTs", w.from().atStartOfDay());
            p.addValue("toTs", w.to().atStartOfDay());
        }
        if (w.incremental()) p.addValue("tripIds", w.tripIds());
        if (w.hourStart() != null) p.addValue("hourStart", w.hourStart());
        return p;
    }

    private MapSqlParameterSource rangeParams(LocalDate from, LocalDate to) {
        LocalDate fromDate = from != null ? from : LocalDate.of(2000, 1, 1);
        LocalDate toDate = to != null ? to.plusDays(1) : LocalDate.now().plusDays(1);
        return params()
                .addValue("fromDate", Date.valueOf(fromDate))
                .addValue("toDate", Date.valueOf(toDate))
                .addValue("serviceScopes", serviceScopes())
                .addValue("minDelay", DELAYED_MIN)
                .addValue("maxStatDelay", DelayLimits.MAX_STAT_DELAY_MINUTES);
    }

    private static String formatTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().format(HH_MM);
    }

    private String serviceScope(String overviewCode) {
        String scope = ServiceScope.fromOverviewCode(overviewCode);
        return scope != null ? scope : ServiceScope.CONNOLLY;
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }
}
