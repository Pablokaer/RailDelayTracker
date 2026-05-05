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

    // ── snapshot-level max ────────────────────────────────────────────────────

    @Query("SELECT MAX(s.lateMinutes) FROM TripStationSnapshot s WHERE s.capturedAt >= :from AND s.capturedAt < :to")
    Integer findMaxDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── trip-level counts ─────────────────────────────────────────────────────

    @Query(value = """
            SELECT COUNT(DISTINCT s.trip_id)
            FROM trip_station_snapshot s
            WHERE s.captured_at >= :from AND s.captured_at < :to
            """, nativeQuery = true)
    Long countUniqueTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

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
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM station_trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findStationStatsByTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
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
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM station_trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            """, nativeQuery = true)
    List<Object[]> findAllStationStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
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

    // ── top 10 trips by peak delay ────────────────────────────────────────────

    @Query(value = """
            SELECT t.train_code, s.station_full_name, t.train_date,
                   s.sch_depart, s.sch_arrival,
                   t.direction, t.origin, t.destination,
                   MAX(s.late_minutes)  AS peak_delay,
                   COUNT(*)             AS snapshot_count
            FROM trip_station_snapshot s
            JOIN trip t ON t.id = s.trip_id
            WHERE s.captured_at >= :from AND s.captured_at < :to
            GROUP BY t.train_code, s.station_full_name, t.train_date,
                     s.sch_depart, s.sch_arrival, t.direction, t.origin, t.destination
            HAVING MAX(s.late_minutes) >= :minDelay
            ORDER BY peak_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             @Param("minDelay") int minDelay);

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

    // ── daily delays ──────────────────────────────────────────────────────────

    @Query(value = """
            WITH delayed_trips AS (
                SELECT t.train_date, s.trip_id,
                       MIN(s.captured_at) AS first_seen
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE t.train_date IS NOT NULL AND t.train_date <> ''
                  AND s.captured_at >= :from AND s.captured_at < :to
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
                                          @Param("minDelay") int minDelay);

    // ── legacy REST API ───────────────────────────────────────────────────────

    @Query(value = """
            WITH trip_stats AS (
                SELECT t.id, t.train_code, t.train_type,
                       MAX(s.late_minutes) AS peak_delay
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
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
    List<Object[]> findTopDelayedTrainsByTrips(@Param("minDelay") int minDelay);

    List<TripStationSnapshot> findTop50ByLateMinutesGreaterThanOrderByCapturedAtDesc(int lateMinutes);

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
            SELECT t.train_code, s.station_full_name, t.train_date,
                   s.sch_depart, s.sch_arrival,
                   t.direction, t.origin, t.destination,
                   MAX(s.late_minutes)  AS peak_delay,
                   COUNT(*)             AS snapshot_count
            FROM trip_station_snapshot s
            JOIN trip t ON t.id = s.trip_id
            WHERE s.captured_at >= :from AND s.captured_at < :to
              AND UPPER(s.station_code) = UPPER(:stationCode)
            GROUP BY t.train_code, s.station_full_name, t.train_date,
                     s.sch_depart, s.sch_arrival, t.direction, t.origin, t.destination
            HAVING MAX(s.late_minutes) >= :minDelay
            ORDER BY peak_delay DESC
            LIMIT 10
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
