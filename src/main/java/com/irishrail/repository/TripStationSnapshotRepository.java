package com.irishrail.repository;

import com.irishrail.model.TripStationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TripStationSnapshotRepository extends JpaRepository<TripStationSnapshot, Long> {

    // ── period count ──────────────────────────────────────────────────────────

    @Query("SELECT COUNT(s) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(s) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to AND UPPER(s.stationCode) IN (:stationCodes)")
    long countByPeriodForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("stationCodes") List<String> stationCodes);

    // ── snapshot-level max ────────────────────────────────────────────────────

    @Query("SELECT MAX(s.lateMinutes) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to")
    Integer findMaxDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT MAX(s.lateMinutes) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to AND UPPER(s.stationCode) IN (:stationCodes)")
    Integer findMaxDelayForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("stationCodes") List<String> stationCodes);

    // ── trip-level counts ─────────────────────────────────────────────────────

    @Query(value = """
            SELECT COUNT(DISTINCT s.trip_id)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
            """, nativeQuery = true)
    Long countUniqueTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT COUNT(DISTINCT s.trip_id)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) IN (:stationCodes)
            """, nativeQuery = true)
    Long countUniqueTripsForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                     @Param("stationCodes") List<String> stationCodes);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT s.trip_id
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                GROUP BY s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            ) sub
            """, nativeQuery = true)
    Long countDelayedUniqueTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                 @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT s.trip_id
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            ) sub
            """, nativeQuery = true)
    Long countDelayedUniqueTripsForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                            @Param("stationCodes") List<String> stationCodes,
                                            @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT COALESCE(AVG(peak_delay), 0) FROM (
                SELECT MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                GROUP BY s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            ) sub
            """, nativeQuery = true)
    Double findAvgPeakDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                            @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT COALESCE(AVG(peak_delay), 0) FROM (
                SELECT MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            ) sub
            """, nativeQuery = true)
    Double findAvgPeakDelayForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("stationCodes") List<String> stationCodes,
                                       @Param("minDelay") int minDelay);

    // ── station ranking — top 15 ──────────────────────────────────────────────

    @Query(value = """
            WITH station_trips AS (
                SELECT s.station_code, s.station_full_name, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                GROUP BY s.station_code, s.station_full_name, s.trip_id
            )
            SELECT station_code, station_full_name,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM station_trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findStationStatsByTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                           @Param("minDelay") int minDelay);

    @Query(value = """
            WITH station_trips AS (
                SELECT s.station_code, s.station_full_name, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY s.station_code, s.station_full_name, s.trip_id
            )
            SELECT station_code, station_full_name,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM station_trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findStationStatsByTripsForStations(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to,
                                                      @Param("stationCodes") List<String> stationCodes,
                                                      @Param("minDelay") int minDelay);

    // ── station ranking — all stations ────────────────────────────────────────

    @Query(value = """
            WITH station_trips AS (
                SELECT s.station_code, s.station_full_name, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                GROUP BY s.station_code, s.station_full_name, s.trip_id
            )
            SELECT station_code, station_full_name,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM station_trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            """, nativeQuery = true)
    List<Object[]> findAllStationStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("minDelay") int minDelay);

    @Query(value = """
            WITH station_trips AS (
                SELECT s.station_code, s.station_full_name, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY s.station_code, s.station_full_name, s.trip_id
            )
            SELECT station_code, station_full_name,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM station_trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            """, nativeQuery = true)
    List<Object[]> findAllStationStatsForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                  @Param("stationCodes") List<String> stationCodes,
                                                  @Param("minDelay") int minDelay);

    // ── hourly analysis ───────────────────────────────────────────────────────

    @Query(value = """
            SELECT EXTRACT(HOUR FROM s.captured_at)                                     AS hour,
                   COUNT(*)                                                              AS total,
                   SUM(CASE WHEN s.late_minutes > 0 THEN 1 ELSE 0 END)                 AS delayed,
                   COALESCE(AVG(CASE WHEN s.late_minutes > 0
                                     THEN CAST(s.late_minutes AS FLOAT) END), 0)       AS avg_delay
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
            GROUP BY EXTRACT(HOUR FROM s.captured_at)
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> findHourlyStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM s.captured_at)                                     AS hour,
                   COUNT(*)                                                              AS total,
                   SUM(CASE WHEN s.late_minutes > 0 THEN 1 ELSE 0 END)                 AS delayed,
                   COALESCE(AVG(CASE WHEN s.late_minutes > 0
                                     THEN CAST(s.late_minutes AS FLOAT) END), 0)       AS avg_delay
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) IN (:stationCodes)
            GROUP BY EXTRACT(HOUR FROM s.captured_at)
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> findHourlyStatsForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                              @Param("stationCodes") List<String> stationCodes);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM s.captured_at)                                     AS hour,
                   COUNT(*)                                                              AS total,
                   SUM(CASE WHEN s.late_minutes > 0 THEN 1 ELSE 0 END)                 AS delayed,
                   COALESCE(AVG(CASE WHEN s.late_minutes > 0
                                     THEN CAST(s.late_minutes AS FLOAT) END), 0)       AS avg_delay
            FROM trip_station_snapshot s
            JOIN trip t ON t.id = s.trip_id
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND s.late_minutes <= :maxStatDelay
              AND COALESCE(s.service_scope,
                    CASE WHEN UPPER(s.station_code) = 'HSTON'
                           OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                           OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                         THEN 'HEUSTON' ELSE 'CONNOLLY' END) IN (:serviceScopes)
            GROUP BY EXTRACT(HOUR FROM s.captured_at)
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> findHourlyStatsForScopes(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to,
                                            @Param("serviceScopes") List<String> serviceScopes,
                                            @Param("maxStatDelay") int maxStatDelay);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM s.captured_at)                                     AS hour,
                   COUNT(*)                                                              AS total,
                   SUM(CASE WHEN s.late_minutes > 0 THEN 1 ELSE 0 END)                 AS delayed,
                   COALESCE(AVG(CASE WHEN s.late_minutes > 0
                                     THEN CAST(s.late_minutes AS FLOAT) END), 0)       AS avg_delay
            FROM trip_station_snapshot s
            JOIN trip t ON t.id = s.trip_id
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND s.late_minutes <= :maxStatDelay
              AND COALESCE(s.service_scope,
                    CASE WHEN UPPER(s.station_code) = 'HSTON'
                           OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                           OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                         THEN 'HEUSTON' ELSE 'CONNOLLY' END) = :serviceScope
            GROUP BY EXTRACT(HOUR FROM s.captured_at)
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> findHourlyStatsForScope(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("serviceScope") String serviceScope,
                                           @Param("maxStatDelay") int maxStatDelay);

    // ── top 10 trips by peak delay ────────────────────────────────────────────

    @Query(value = """
            WITH trip_peaks AS (
                SELECT t.id AS trip_id, t.train_code, t.train_date,
                       t.direction, t.origin, t.destination,
                       MAX(s.late_minutes) AS peak_delay,
                       COUNT(*)            AS snapshot_count
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.captured_at >= :from AND s.captured_at < :to
                GROUP BY t.id, t.train_code, t.train_date, t.direction, t.origin, t.destination
                HAVING MAX(s.late_minutes) >= :minDelay
                ORDER BY peak_delay DESC
                LIMIT 10
            ),
            peak_snaps AS (
                SELECT DISTINCT ON (s.trip_id)
                       s.trip_id, s.station_full_name, s.sch_depart, s.sch_arrival, s.captured_at
                FROM trip_station_snapshot s
                JOIN trip_peaks tp ON tp.trip_id = s.trip_id
                WHERE s.late_minutes = tp.peak_delay
                  AND s.captured_at >= :from AND s.captured_at < :to
                ORDER BY s.trip_id, s.captured_at ASC
            )
            SELECT tp.train_code, ps.station_full_name, tp.train_date,
                   ps.sch_depart, ps.sch_arrival,
                   tp.direction, tp.origin, tp.destination,
                   tp.peak_delay, tp.snapshot_count,
                   ps.captured_at AS peak_captured_at
            FROM trip_peaks tp
            JOIN peak_snaps ps ON ps.trip_id = tp.trip_id
            ORDER BY tp.peak_delay DESC
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             @Param("minDelay") int minDelay);

    @Query(value = """
            WITH trip_peaks AS (
                SELECT t.id AS trip_id, t.train_code, t.train_date,
                       t.direction, t.origin, t.destination,
                       MAX(s.late_minutes) AS peak_delay,
                       COUNT(*)            AS snapshot_count
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY t.id, t.train_code, t.train_date, t.direction, t.origin, t.destination
                HAVING MAX(s.late_minutes) >= :minDelay
                ORDER BY peak_delay DESC
                LIMIT 10
            ),
            peak_snaps AS (
                SELECT DISTINCT ON (s.trip_id)
                       s.trip_id, s.station_full_name, s.sch_depart, s.sch_arrival, s.captured_at
                FROM trip_station_snapshot s
                JOIN trip_peaks tp ON tp.trip_id = s.trip_id
                WHERE s.late_minutes = tp.peak_delay
                  AND s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                ORDER BY s.trip_id, s.captured_at ASC
            )
            SELECT tp.train_code, ps.station_full_name, tp.train_date,
                   ps.sch_depart, ps.sch_arrival,
                   tp.direction, tp.origin, tp.destination,
                   tp.peak_delay, tp.snapshot_count,
                   ps.captured_at AS peak_captured_at
            FROM trip_peaks tp
            JOIN peak_snaps ps ON ps.trip_id = tp.trip_id
            ORDER BY tp.peak_delay DESC
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelayForStations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                        @Param("stationCodes") List<String> stationCodes,
                                                        @Param("minDelay") int minDelay);

    @Query(value = """
            WITH scoped_snapshots AS (
                SELECT s.*,
                       COALESCE(s.service_scope,
                         CASE WHEN UPPER(s.station_code) = 'HSTON'
                                OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                              THEN 'HEUSTON' ELSE 'CONNOLLY' END) AS resolved_scope
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND s.late_minutes <= :maxStatDelay
            ),
            trip_peaks AS (
                SELECT t.id AS trip_id, t.train_code, t.train_date,
                       t.direction, t.origin, t.destination,
                       MAX(s.late_minutes) AS peak_delay,
                       COUNT(*)            AS snapshot_count
                FROM scoped_snapshots s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.resolved_scope IN (:serviceScopes)
                GROUP BY t.id, t.train_code, t.train_date, t.direction, t.origin, t.destination
                HAVING MAX(s.late_minutes) >= :minDelay
                ORDER BY peak_delay DESC
                LIMIT 10
            ),
            peak_snaps AS (
                SELECT DISTINCT ON (s.trip_id)
                       s.trip_id, s.station_full_name, s.sch_depart, s.sch_arrival, s.captured_at
                FROM scoped_snapshots s
                JOIN trip_peaks tp ON tp.trip_id = s.trip_id
                WHERE s.late_minutes = tp.peak_delay
                  AND s.resolved_scope IN (:serviceScopes)
                ORDER BY s.trip_id, s.captured_at ASC
            )
            SELECT tp.train_code, ps.station_full_name, tp.train_date,
                   ps.sch_depart, ps.sch_arrival,
                   tp.direction, tp.origin, tp.destination,
                   tp.peak_delay, tp.snapshot_count,
                   ps.captured_at AS peak_captured_at
            FROM trip_peaks tp
            JOIN peak_snaps ps ON ps.trip_id = tp.trip_id
            ORDER BY tp.peak_delay DESC
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelayForScopes(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to,
                                                      @Param("serviceScopes") List<String> serviceScopes,
                                                      @Param("minDelay") int minDelay,
                                                      @Param("maxStatDelay") int maxStatDelay);

    @Query(value = """
            WITH scoped_snapshots AS (
                SELECT s.*,
                       COALESCE(s.service_scope,
                         CASE WHEN UPPER(s.station_code) = 'HSTON'
                                OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                                OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                              THEN 'HEUSTON' ELSE 'CONNOLLY' END) AS resolved_scope
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND s.late_minutes <= :maxStatDelay
            ),
            trip_peaks AS (
                SELECT t.id AS trip_id, t.train_code, t.train_date,
                       t.direction, t.origin, t.destination,
                       MAX(s.late_minutes) AS peak_delay,
                       COUNT(*)            AS snapshot_count
                FROM scoped_snapshots s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.resolved_scope = :serviceScope
                GROUP BY t.id, t.train_code, t.train_date, t.direction, t.origin, t.destination
                HAVING MAX(s.late_minutes) >= :minDelay
                ORDER BY peak_delay DESC
                LIMIT 10
            ),
            peak_snaps AS (
                SELECT DISTINCT ON (s.trip_id)
                       s.trip_id, s.station_full_name, s.sch_depart, s.sch_arrival, s.captured_at
                FROM scoped_snapshots s
                JOIN trip_peaks tp ON tp.trip_id = s.trip_id
                WHERE s.late_minutes = tp.peak_delay
                  AND s.resolved_scope = :serviceScope
                ORDER BY s.trip_id, s.captured_at ASC
            )
            SELECT tp.train_code, ps.station_full_name, tp.train_date,
                   ps.sch_depart, ps.sch_arrival,
                   tp.direction, tp.origin, tp.destination,
                   tp.peak_delay, tp.snapshot_count,
                   ps.captured_at AS peak_captured_at
            FROM trip_peaks tp
            JOIN peak_snaps ps ON ps.trip_id = tp.trip_id
            ORDER BY tp.peak_delay DESC
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelayForScope(@Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to,
                                                     @Param("serviceScope") String serviceScope,
                                                     @Param("minDelay") int minDelay,
                                                     @Param("maxStatDelay") int maxStatDelay);

    // ── top 5 most recently captured delayed trips (one per trip) ─────────────

    @Query(value = """
            SELECT sub.train_code, sub.station_full_name, sub.late_minutes, sub.captured_at,
                   sub.origin, sub.destination
            FROM (
                SELECT DISTINCT ON (s.trip_id)
                       t.train_code, s.station_full_name, s.late_minutes, s.captured_at,
                       t.origin, t.destination
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes >= :minDelay
                ORDER BY s.trip_id, s.captured_at DESC
            ) sub
            ORDER BY sub.captured_at DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findTop5RecentDelaysPerTrip(@Param("minDelay") int minDelay);

    @Query(value = """
            SELECT sub.train_code, sub.station_full_name, sub.late_minutes, sub.captured_at,
                   sub.origin, sub.destination
            FROM (
                SELECT DISTINCT ON (s.trip_id)
                       t.train_code, s.station_full_name, s.late_minutes, s.captured_at,
                       t.origin, t.destination
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes >= :minDelay
                  AND UPPER(s.station_code) = UPPER(:stationCode)
                ORDER BY s.trip_id, s.captured_at DESC
            ) sub
            ORDER BY sub.captured_at DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findTop5RecentDelaysPerTripForStation(@Param("stationCode") String stationCode,
                                                         @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT sub.train_code, sub.station_full_name, sub.late_minutes, sub.captured_at,
                   sub.origin, sub.destination
            FROM (
                SELECT DISTINCT ON (s.trip_id)
                       t.train_code, s.station_full_name, s.late_minutes, s.captured_at,
                       t.origin, t.destination
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes >= :minDelay
                  AND UPPER(s.station_code) IN (:stationCodes)
                ORDER BY s.trip_id, s.captured_at DESC
            ) sub
            ORDER BY sub.captured_at DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findTop5RecentDelaysPerTripForStations(@Param("stationCodes") List<String> stationCodes,
                                                          @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT sub.train_code, sub.station_full_name, sub.late_minutes, sub.captured_at,
                   sub.origin, sub.destination
            FROM (
                SELECT DISTINCT ON (s.trip_id)
                       t.train_code, s.station_full_name, s.late_minutes, s.captured_at,
                       t.origin, t.destination
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes >= :minDelay
                  AND s.late_minutes <= :maxStatDelay
                  AND COALESCE(s.service_scope,
                        CASE WHEN UPPER(s.station_code) = 'HSTON'
                               OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                               OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                             THEN 'HEUSTON' ELSE 'CONNOLLY' END) IN (:serviceScopes)
                ORDER BY s.trip_id, s.captured_at DESC
            ) sub
            ORDER BY sub.captured_at DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findTop5RecentDelaysPerTripForScopes(@Param("serviceScopes") List<String> serviceScopes,
                                                        @Param("minDelay") int minDelay,
                                                        @Param("maxStatDelay") int maxStatDelay);

    @Query(value = """
            SELECT sub.train_code, sub.station_full_name, sub.late_minutes, sub.captured_at,
                   sub.origin, sub.destination
            FROM (
                SELECT DISTINCT ON (s.trip_id)
                       t.train_code, s.station_full_name, s.late_minutes, s.captured_at,
                       t.origin, t.destination
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes >= :minDelay
                  AND s.late_minutes <= :maxStatDelay
                  AND COALESCE(s.service_scope,
                        CASE WHEN UPPER(s.station_code) = 'HSTON'
                               OR LOWER(COALESCE(t.origin, '')) LIKE '%heuston%'
                               OR LOWER(COALESCE(t.destination, '')) LIKE '%heuston%'
                             THEN 'HEUSTON' ELSE 'CONNOLLY' END) = :serviceScope
                ORDER BY s.trip_id, s.captured_at DESC
            ) sub
            ORDER BY sub.captured_at DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findTop5RecentDelaysPerTripForScope(@Param("serviceScope") String serviceScope,
                                                       @Param("minDelay") int minDelay,
                                                       @Param("maxStatDelay") int maxStatDelay);

    // ── destinations ──────────────────────────────────────────────────────────

    @Query(value = """
            WITH trip_delays AS (
                SELECT t.destination, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.destination IS NOT NULL AND t.destination <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                GROUP BY t.destination, s.trip_id
            )
            SELECT destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM trip_delays
            GROUP BY destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY avg_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopDestinationsByAvgDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                 @Param("minDelay") int minDelay);

    @Query(value = """
            WITH trip_delays AS (
                SELECT t.destination, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.destination IS NOT NULL AND t.destination <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY t.destination, s.trip_id
            )
            SELECT destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM trip_delays
            GROUP BY destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY avg_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopDestinationsByAvgDelayForStations(@Param("from") LocalDateTime from,
                                                            @Param("to") LocalDateTime to,
                                                            @Param("stationCodes") List<String> stationCodes,
                                                            @Param("minDelay") int minDelay);

    // ── route ranking ─────────────────────────────────────────────────────────

    @Query(value = """
            WITH route_delays AS (
                SELECT t.origin, t.destination, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.origin IS NOT NULL AND t.origin <> ''
                  AND t.destination IS NOT NULL AND t.destination <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                GROUP BY t.origin, t.destination, s.trip_id
            )
            SELECT origin, destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM route_delays
            GROUP BY origin, destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY total_acc_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findTopRoutesByDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                        @Param("minDelay") int minDelay);

    @Query(value = """
            WITH route_delays AS (
                SELECT t.origin, t.destination, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.origin IS NOT NULL AND t.origin <> ''
                  AND t.destination IS NOT NULL AND t.destination <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) IN (:stationCodes)
                GROUP BY t.origin, t.destination, s.trip_id
            )
            SELECT origin, destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM route_delays
            GROUP BY origin, destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY total_acc_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findTopRoutesByDelayForStations(@Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to,
                                                   @Param("stationCodes") List<String> stationCodes,
                                                   @Param("minDelay") int minDelay);

    @Query(value = """
            WITH route_delays AS (
                SELECT t.origin, t.destination, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.origin IS NOT NULL AND t.origin <> ''
                  AND t.destination IS NOT NULL AND t.destination <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) = UPPER(:stationCode)
                GROUP BY t.origin, t.destination, s.trip_id
            )
            SELECT origin, destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN peak_delay ELSE 0 END)    AS total_acc_delay
            FROM route_delays
            GROUP BY origin, destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY total_acc_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findTopRoutesByDelayForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                  @Param("stationCode") String stationCode,
                                                  @Param("minDelay") int minDelay);

    // ── delay categories ──────────────────────────────────────────────────────

    @Query(value = """
            SELECT MAX(s.late_minutes)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
            GROUP BY s.trip_id
            HAVING MAX(s.late_minutes) >= :minDelay
            """, nativeQuery = true)
    List<Object> findAllPeakDelaysAboveThreshold(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to,
                                                 @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT MAX(s.late_minutes)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) IN (:stationCodes)
            GROUP BY s.trip_id
            HAVING MAX(s.late_minutes) >= :minDelay
            """, nativeQuery = true)
    List<Object> findAllPeakDelaysAboveThresholdForStations(@Param("from") LocalDateTime from,
                                                            @Param("to") LocalDateTime to,
                                                            @Param("stationCodes") List<String> stationCodes,
                                                            @Param("minDelay") int minDelay);

    // ── daily delays ──────────────────────────────────────────────────────────

    @Query(value = """
            WITH delayed_trips AS (
                SELECT t.train_date, s.trip_id,
                       MIN(s.captured_at) AS first_seen
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.train_date IS NOT NULL AND t.train_date <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                  AND s.late_minutes <= :maxStatDelay
                GROUP BY t.train_date, s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            )
            SELECT train_date,
                   COUNT(*)         AS delayed_trip_count,
                   MIN(first_seen)  AS date_seen
            FROM delayed_trips
            GROUP BY train_date
            ORDER BY date_seen DESC
            LIMIT 30
            """, nativeQuery = true)
    List<Object[]> findDailyDelaysByTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                          @Param("minDelay") int minDelay,
                                          @Param("maxStatDelay") int maxStatDelay);

    // ── legacy REST API ───────────────────────────────────────────────────────

    @Query(value = """
            WITH trip_stats AS (
                SELECT t.id, t.train_code, t.train_type,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes <= :maxStatDelay
                GROUP BY t.id, t.train_code, t.train_type
            )
            SELECT train_code,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   MAX(peak_delay)                                                      AS max_delay
            FROM trip_stats
            GROUP BY train_code
            ORDER BY delayed_trips DESC, avg_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopDelayedTrainsByTrips(@Param("minDelay") int minDelay,
                                               @Param("maxStatDelay") int maxStatDelay);

    List<TripStationSnapshot> findTop50ByLateMinutesGreaterThanAndLateMinutesLessThanEqualOrderByCapturedAtDesc(
            int minDelay, int maxStatDelay);

    // ── retention cleanup ─────────────────────────────────────────────────────

    @Modifying
    @Query("DELETE FROM TripStationSnapshot s WHERE s.capturedAt < :cutoff")
    int deleteByCapturedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    // ── station-filtered queries ──────────────────────────────────────────────

    @Query("SELECT COUNT(s) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to AND UPPER(s.stationCode) = UPPER(:stationCode)")
    long countByPeriodAndStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                 @Param("stationCode") String stationCode);

    @Query("SELECT MAX(s.lateMinutes) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to AND UPPER(s.stationCode) = UPPER(:stationCode)")
    Integer findMaxDelayForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                   @Param("stationCode") String stationCode);

    @Query(value = """
            SELECT COUNT(DISTINCT s.trip_id)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) = UPPER(:stationCode)
            """, nativeQuery = true)
    Long countUniqueTripsForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("stationCode") String stationCode);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT s.trip_id
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) = UPPER(:stationCode)
                GROUP BY s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            ) sub
            """, nativeQuery = true)
    Long countDelayedUniqueTripsForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                           @Param("stationCode") String stationCode, @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT COALESCE(AVG(peak_delay), 0) FROM (
                SELECT MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) = UPPER(:stationCode)
                GROUP BY s.trip_id
                HAVING MAX(s.late_minutes) >= :minDelay
            ) sub
            """, nativeQuery = true)
    Double findAvgPeakDelayForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                      @Param("stationCode") String stationCode, @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM s.captured_at)                                     AS hour,
                   COUNT(*)                                                              AS total,
                   SUM(CASE WHEN s.late_minutes > 0 THEN 1 ELSE 0 END)                 AS delayed,
                   COALESCE(AVG(CASE WHEN s.late_minutes > 0
                                     THEN CAST(s.late_minutes AS FLOAT) END), 0)       AS avg_delay
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) = UPPER(:stationCode)
            GROUP BY EXTRACT(HOUR FROM s.captured_at)
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> findHourlyStatsForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             @Param("stationCode") String stationCode);

    @Query(value = """
            WITH trip_peaks AS (
                SELECT t.id AS trip_id, t.train_code, t.train_date,
                       t.direction, t.origin, t.destination,
                       MAX(s.late_minutes) AS peak_delay,
                       COUNT(*)            AS snapshot_count
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) = UPPER(:stationCode)
                GROUP BY t.id, t.train_code, t.train_date, t.direction, t.origin, t.destination
                HAVING MAX(s.late_minutes) >= :minDelay
                ORDER BY peak_delay DESC
                LIMIT 10
            ),
            peak_snaps AS (
                SELECT DISTINCT ON (s.trip_id)
                       s.trip_id, s.station_full_name, s.sch_depart, s.sch_arrival, s.captured_at
                FROM trip_station_snapshot s
                JOIN trip_peaks tp ON tp.trip_id = s.trip_id
                WHERE s.late_minutes = tp.peak_delay
                  AND s.captured_at >= :from AND s.captured_at < :to
                ORDER BY s.trip_id, s.captured_at ASC
            )
            SELECT tp.train_code, ps.station_full_name, tp.train_date,
                   ps.sch_depart, ps.sch_arrival,
                   tp.direction, tp.origin, tp.destination,
                   tp.peak_delay, tp.snapshot_count,
                   ps.captured_at AS peak_captured_at
            FROM trip_peaks tp
            JOIN peak_snaps ps ON ps.trip_id = tp.trip_id
            ORDER BY tp.peak_delay DESC
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelayForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                       @Param("stationCode") String stationCode,
                                                       @Param("minDelay") int minDelay);

    @Query(value = """
            WITH trip_delays AS (
                SELECT t.destination, s.trip_id,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.destination IS NOT NULL AND t.destination <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
                  AND UPPER(s.station_code) = UPPER(:stationCode)
                GROUP BY t.destination, s.trip_id
            )
            SELECT destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM trip_delays
            GROUP BY destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY avg_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopDestinationsByAvgDelayForStation(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                           @Param("stationCode") String stationCode,
                                                           @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT MAX(s.late_minutes)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) = UPPER(:stationCode)
            GROUP BY s.trip_id
            HAVING MAX(s.late_minutes) >= :minDelay
            """, nativeQuery = true)
    List<Object> findAllPeakDelaysAboveThresholdForStation(@Param("from") LocalDateTime from,
                                                           @Param("to") LocalDateTime to,
                                                           @Param("stationCode") String stationCode,
                                                           @Param("minDelay") int minDelay);
}
