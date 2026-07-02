package com.irishrail.service;

import com.irishrail.model.DashboardSummary;
import com.irishrail.model.DelayCategory;
import com.irishrail.model.DelayLimits;
import com.irishrail.model.DestinationStats;
import com.irishrail.model.RouteStats;
import com.irishrail.model.ServiceScope;
import com.irishrail.model.StationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsAggregateService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAggregateService.class);
    private static final int DELAYED_MIN = DelayCategory.delayedThreshold();

    private final NamedParameterJdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value("${irishrail.analytics.aggregates.backfill-on-startup:true}")
    private boolean backfillOnStartup;

    public AnalyticsAggregateService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    @Transactional
    public void refreshAll() {
        jdbc.update("DELETE FROM daily_station_route_metrics", params());
        int rows = insertAggregates(null, null);
        log.info("Analytics aggregates refreshed: {} daily station-route rows", rows);
    }

    @Transactional
    public void refreshDate(LocalDate date) {
        if (date == null) return;
        MapSqlParameterSource p = params()
                .addValue("serviceDate", Date.valueOf(date));
        jdbc.update("""
                DELETE FROM daily_station_route_metrics
                WHERE service_date = :serviceDate
                """, p);
        insertAggregates(date, date.plusDays(1));
    }

    @Transactional
    public int deleteBefore(LocalDate cutoffDate) {
        if (cutoffDate == null) return 0;
        return jdbc.update("""
                DELETE FROM daily_station_route_metrics
                WHERE service_date < :cutoffDate
                """, params().addValue("cutoffDate", Date.valueOf(cutoffDate)));
    }

    public DashboardSummary dashboard(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = rangeParams(from, to);
        return jdbc.queryForObject("""
                WITH trip_peaks AS (
                    SELECT s.trip_id,
                           COUNT(*) AS snapshot_count,
                           MAX(s.late_minutes) AS peak_delay
                    FROM trip_station_snapshot s
                    JOIN trip t ON t.id = s.trip_id
                    WHERE s.captured_at >= :fromDate AND s.captured_at < :toDate
                      AND s.late_minutes <= :maxStatDelay
                      AND COALESCE(s.service_scope,
                                   CASE WHEN UPPER(s.station_code) = 'HSTON'
                                          OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                          OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                        THEN 'HEUSTON'
                                        ELSE 'CONNOLLY' END) IN (:serviceScopes)
                    GROUP BY s.trip_id
                )
                SELECT COALESCE(SUM(snapshot_count), 0) AS total_snapshots,
                       COUNT(*) AS unique_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) AS delayed_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END) AS total_delay_minutes,
                       COALESCE(MAX(peak_delay), 0) AS max_delay
                FROM trip_peaks
                """, p, (rs, rowNum) -> {
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

    public DashboardSummary dashboardForStation(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to).addValue("serviceScope", serviceScope(stationCode));
        return jdbc.queryForObject("""
                WITH trip_peaks AS (
                    SELECT s.trip_id,
                           COUNT(*) AS snapshot_count,
                           MAX(s.late_minutes) AS peak_delay
                    FROM trip_station_snapshot s
                    JOIN trip t ON t.id = s.trip_id
                    WHERE s.captured_at >= :fromDate AND s.captured_at < :toDate
                      AND s.late_minutes <= :maxStatDelay
                      AND COALESCE(s.service_scope,
                                   CASE WHEN UPPER(s.station_code) = 'HSTON'
                                          OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                          OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                        THEN 'HEUSTON'
                                        ELSE 'CONNOLLY' END) = :serviceScope
                    GROUP BY s.trip_id
                )
                SELECT COALESCE(SUM(snapshot_count), 0) AS total_snapshots,
                       COUNT(*) AS unique_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) AS delayed_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END) AS total_delay_minutes,
                       COALESCE(MAX(peak_delay), 0) AS max_delay
                FROM trip_peaks
                """, p, (rs, rowNum) -> {
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

    public List<StationStats> stationRanking(LocalDate from, LocalDate to, boolean limit) {
        String limitSql = limit ? " LIMIT 15" : "";
        return jdbc.query("""
                SELECT station_code,
                       MAX(station_full_name) AS station_full_name,
                       COALESCE(SUM(unique_trips), 0) AS total_trips,
                       COALESCE(SUM(delayed_trips), 0) AS delayed_trips,
                       COALESCE(SUM(total_delay_minutes), 0) AS total_delay_minutes
                FROM daily_station_route_metrics
                WHERE service_date >= :fromDate AND service_date < :toDate
                  AND service_scope IN (:serviceScopes)
                GROUP BY station_code
                ORDER BY delayed_trips DESC,
                         CASE WHEN SUM(delayed_trips) > 0
                              THEN SUM(total_delay_minutes)::float / SUM(delayed_trips)
                              ELSE 0 END DESC
                """ + limitSql, rangeParams(from, to), (rs, rowNum) -> {
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

    public List<StationStats> stationRankingForScope(LocalDate from, LocalDate to, String overviewCode, boolean limit) {
        String limitSql = limit ? " LIMIT 15" : "";
        MapSqlParameterSource p = rangeParams(from, to)
                .addValue("serviceScope", serviceScope(overviewCode));
        return jdbc.query("""
                SELECT station_code,
                       MAX(station_full_name) AS station_full_name,
                       COALESCE(SUM(unique_trips), 0) AS total_trips,
                       COALESCE(SUM(delayed_trips), 0) AS delayed_trips,
                       COALESCE(SUM(total_delay_minutes), 0) AS total_delay_minutes
                FROM daily_station_route_metrics
                WHERE service_date >= :fromDate AND service_date < :toDate
                  AND service_scope = :serviceScope
                GROUP BY station_code
                ORDER BY delayed_trips DESC,
                         CASE WHEN SUM(delayed_trips) > 0
                              THEN SUM(total_delay_minutes)::float / SUM(delayed_trips)
                              ELSE 0 END DESC
                """ + limitSql, p, (rs, rowNum) -> {
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

    public List<DestinationStats> destinations(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String stationFilter = "";
        if (stationCode != null && !stationCode.isBlank()) {
            stationFilter = "\n                      AND service_scope = :serviceScope\n";
            p.addValue("serviceScope", serviceScope(stationCode));
        }
        return jdbc.query("""
                WITH scoped_trips AS (
                    SELECT service_scope,
                           destination,
                           trip_id,
                           MAX(peak_delay) AS peak_delay
                    FROM (
                        SELECT COALESCE(s.service_scope,
                                        CASE WHEN UPPER(s.station_code) = 'HSTON'
                                               OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                               OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                             THEN 'HEUSTON'
                                             ELSE 'CONNOLLY' END) AS service_scope,
                               COALESCE(NULLIF(t.destination, ''), 'Unknown') AS destination,
                               s.trip_id,
                               MAX(s.late_minutes) AS peak_delay
                        FROM trip_station_snapshot s
                        JOIN trip t ON t.id = s.trip_id
                        WHERE s.captured_at >= :fromDate AND s.captured_at < :toDate
                          AND s.late_minutes <= :maxStatDelay
                        GROUP BY COALESCE(s.service_scope,
                                          CASE WHEN UPPER(s.station_code) = 'HSTON'
                                                 OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                                 OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                               THEN 'HEUSTON'
                                               ELSE 'CONNOLLY' END),
                                 COALESCE(NULLIF(t.destination, ''), 'Unknown'),
                                 s.trip_id
                    ) scoped_trips
                    WHERE service_scope IN (:serviceScopes)
                """ + stationFilter + """
                    GROUP BY service_scope, destination, trip_id
                ),
                trip_peaks AS (
                    SELECT destination,
                           trip_id,
                           MAX(peak_delay) AS peak_delay
                    FROM scoped_trips
                    GROUP BY destination, trip_id
                )
                SELECT destination,
                       COUNT(*) AS total_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) AS delayed_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END) AS total_delay_minutes
                FROM trip_peaks
                GROUP BY destination
                HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
                ORDER BY CASE WHEN SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
                              THEN SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)::float
                                   / SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)
                              ELSE 0 END DESC
                LIMIT 10
                """, p, (rs, rowNum) -> {
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

    public List<RouteStats> routes(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String stationFilter = "";
        if (stationCode != null && !stationCode.isBlank()) {
            stationFilter = "\n                      AND service_scope = :serviceScope\n";
            p.addValue("serviceScope", serviceScope(stationCode));
        }
        return jdbc.query("""
                WITH scoped_trips AS (
                    SELECT service_scope,
                           origin,
                           destination,
                           trip_id,
                           MAX(peak_delay) AS peak_delay
                    FROM (
                        SELECT COALESCE(s.service_scope,
                                        CASE WHEN UPPER(s.station_code) = 'HSTON'
                                               OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                               OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                             THEN 'HEUSTON'
                                             ELSE 'CONNOLLY' END) AS service_scope,
                               COALESCE(NULLIF(t.origin, ''), 'Unknown') AS origin,
                               COALESCE(NULLIF(t.destination, ''), 'Unknown') AS destination,
                               s.trip_id,
                               MAX(s.late_minutes) AS peak_delay
                        FROM trip_station_snapshot s
                        JOIN trip t ON t.id = s.trip_id
                        WHERE s.captured_at >= :fromDate AND s.captured_at < :toDate
                          AND s.late_minutes <= :maxStatDelay
                        GROUP BY COALESCE(s.service_scope,
                                          CASE WHEN UPPER(s.station_code) = 'HSTON'
                                                 OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                                 OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                               THEN 'HEUSTON'
                                               ELSE 'CONNOLLY' END),
                                 COALESCE(NULLIF(t.origin, ''), 'Unknown'),
                                 COALESCE(NULLIF(t.destination, ''), 'Unknown'),
                                 s.trip_id
                    ) scoped_trips
                    WHERE service_scope IN (:serviceScopes)
                """ + stationFilter + """
                    GROUP BY service_scope, origin, destination, trip_id
                ),
                trip_peaks AS (
                    SELECT origin,
                           destination,
                           trip_id,
                           MAX(peak_delay) AS peak_delay
                    FROM scoped_trips
                    GROUP BY origin, destination, trip_id
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
                """, p, (rs, rowNum) -> {
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

    public Map<String, Long> delayCategories(LocalDate from, LocalDate to, String stationCode) {
        MapSqlParameterSource p = rangeParams(from, to);
        String stationFilter = "";
        if (stationCode != null && !stationCode.isBlank()) {
            stationFilter = "\n                  AND service_scope = :serviceScope\n";
            p.addValue("serviceScope", serviceScope(stationCode));
        }
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(DelayCategory.SMALL_DELAY.getDisplayLabel(), 0L);
        result.put(DelayCategory.MEDIUM_DELAY.getDisplayLabel(), 0L);
        result.put(DelayCategory.BIG_DELAY.getDisplayLabel(), 0L);
        result.put(DelayCategory.EXTREME_DELAY.getDisplayLabel(), 0L);

        Map<String, Object> row = jdbc.queryForMap("""
                WITH scoped_trips AS (
                    SELECT COALESCE(s.service_scope,
                                    CASE WHEN UPPER(s.station_code) = 'HSTON'
                                           OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                           OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                         THEN 'HEUSTON'
                                         ELSE 'CONNOLLY' END) AS service_scope,
                           s.trip_id,
                           MAX(s.late_minutes) AS peak_delay
                    FROM trip_station_snapshot s
                    JOIN trip t ON t.id = s.trip_id
                    WHERE s.captured_at >= :fromDate AND s.captured_at < :toDate
                      AND s.late_minutes <= :maxStatDelay
                    GROUP BY COALESCE(s.service_scope,
                                      CASE WHEN UPPER(s.station_code) = 'HSTON'
                                             OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                             OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                           THEN 'HEUSTON'
                                           ELSE 'CONNOLLY' END),
                             s.trip_id
                ),
                trip_peaks AS (
                    SELECT trip_id,
                           MAX(peak_delay) AS peak_delay
                    FROM scoped_trips
                    WHERE service_scope IN (:serviceScopes)
                """ + stationFilter + """
                    GROUP BY trip_id
                )
                SELECT COALESCE(SUM(CASE WHEN peak_delay BETWEEN 5 AND 9 THEN 1 ELSE 0 END), 0) AS small_delay_trips,
                       COALESCE(SUM(CASE WHEN peak_delay BETWEEN 10 AND 19 THEN 1 ELSE 0 END), 0) AS medium_delay_trips,
                       COALESCE(SUM(CASE WHEN peak_delay BETWEEN 20 AND 39 THEN 1 ELSE 0 END), 0) AS big_delay_trips,
                       COALESCE(SUM(CASE WHEN peak_delay >= 40 THEN 1 ELSE 0 END), 0) AS extreme_delay_trips
                FROM trip_peaks
                """, p);
        result.put(DelayCategory.SMALL_DELAY.getDisplayLabel(), ((Number) row.get("small_delay_trips")).longValue());
        result.put(DelayCategory.MEDIUM_DELAY.getDisplayLabel(), ((Number) row.get("medium_delay_trips")).longValue());
        result.put(DelayCategory.BIG_DELAY.getDisplayLabel(), ((Number) row.get("big_delay_trips")).longValue());
        result.put(DelayCategory.EXTREME_DELAY.getDisplayLabel(), ((Number) row.get("extreme_delay_trips")).longValue());
        return result;
    }

    private void ensureSchema() {
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS daily_station_route_metrics");
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
                CREATE INDEX IF NOT EXISTS idx_dsrm_scope_date_station
                ON daily_station_route_metrics (service_scope, service_date, station_code)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE INDEX IF NOT EXISTS idx_dsrm_date_scope
                ON daily_station_route_metrics (service_date, service_scope)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE INDEX IF NOT EXISTS idx_dsrm_scope_route_date
                ON daily_station_route_metrics (service_scope, origin, destination, service_date)
                """);
    }

    private int insertAggregates(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = params()
                .addValue("minDelay", DELAYED_MIN)
                .addValue("maxStatDelay", DelayLimits.MAX_STAT_DELAY_MINUTES);

        String dateFilter = "";
        if (from != null && to != null) {
            dateFilter = """
                      AND s.captured_at >= :fromTs
                      AND s.captured_at < :toTs
                    """;
            p.addValue("fromTs", from.atStartOfDay());
            p.addValue("toTs", to.atStartOfDay());
        }

        return jdbc.update("""
                INSERT INTO daily_station_route_metrics (
                    service_date, service_scope, station_code, station_full_name, origin, destination,
                    total_snapshots, unique_trips, delayed_trips, on_time_trips,
                    average_delay_minutes, max_delay_minutes, total_delay_minutes,
                    small_delay_trips, medium_delay_trips, big_delay_trips, extreme_delay_trips,
                    updated_at
                )
                WITH trip_peaks AS (
                    SELECT CAST(s.captured_at AS date) AS service_date,
                           COALESCE(s.service_scope,
                                    CASE WHEN UPPER(s.station_code) = 'HSTON'
                                           OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                           OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                         THEN 'HEUSTON'
                                         ELSE 'CONNOLLY' END) AS service_scope,
                           UPPER(s.station_code) AS station_code,
                           MAX(s.station_full_name) AS station_full_name,
                           COALESCE(NULLIF(t.origin, ''), 'Unknown') AS origin,
                           COALESCE(NULLIF(t.destination, ''), 'Unknown') AS destination,
                           s.trip_id,
                           COUNT(*) AS snapshot_count,
                           MAX(s.late_minutes) AS peak_delay
                    FROM trip_station_snapshot s
                    JOIN trip t ON t.id = s.trip_id
                    WHERE 1 = 1
                      AND s.late_minutes <= :maxStatDelay
                """ + dateFilter + """
                    GROUP BY CAST(s.captured_at AS date),
                             COALESCE(s.service_scope,
                                      CASE WHEN UPPER(s.station_code) = 'HSTON'
                                             OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                             OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                                           THEN 'HEUSTON'
                                           ELSE 'CONNOLLY' END),
                             UPPER(s.station_code),
                             COALESCE(NULLIF(t.origin, ''), 'Unknown'),
                             COALESCE(NULLIF(t.destination, ''), 'Unknown'),
                             s.trip_id
                )
                SELECT service_date,
                       service_scope,
                       station_code,
                       MAX(station_full_name) AS station_full_name,
                       origin,
                       destination,
                       SUM(snapshot_count) AS total_snapshots,
                       COUNT(*) AS unique_trips,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) AS delayed_trips,
                       SUM(CASE WHEN peak_delay < :minDelay THEN 1 ELSE 0 END) AS on_time_trips,
                       COALESCE(AVG(CASE WHEN peak_delay >= :minDelay THEN peak_delay END), 0) AS average_delay_minutes,
                       COALESCE(MAX(peak_delay), 0) AS max_delay_minutes,
                       SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END) AS total_delay_minutes,
                       SUM(CASE WHEN peak_delay BETWEEN 5 AND 9 THEN 1 ELSE 0 END) AS small_delay_trips,
                       SUM(CASE WHEN peak_delay BETWEEN 10 AND 19 THEN 1 ELSE 0 END) AS medium_delay_trips,
                       SUM(CASE WHEN peak_delay BETWEEN 20 AND 39 THEN 1 ELSE 0 END) AS big_delay_trips,
                       SUM(CASE WHEN peak_delay >= 40 THEN 1 ELSE 0 END) AS extreme_delay_trips,
                       NOW() AS updated_at
                FROM trip_peaks
                GROUP BY service_date, service_scope, station_code, origin, destination
                """, p);
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

    private String serviceScope(String overviewCode) {
        String scope = ServiceScope.fromOverviewCode(overviewCode);
        return scope != null ? scope : ServiceScope.CONNOLLY;
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }
}
