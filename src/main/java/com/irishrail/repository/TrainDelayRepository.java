package com.irishrail.repository;

import com.irishrail.model.TrainDelayRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TrainDelayRepository extends JpaRepository<TrainDelayRecord, Long> {

    // ── period count ──────────────────────────────────────────────────────────

    @Query("SELECT COUNT(r) FROM TrainDelayRecord r WHERE r.capturedAt >= :from AND r.capturedAt < :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── snapshot-level ────────────────────────────────────────────────────────

    @Query("SELECT MAX(r.lateMinutes) FROM TrainDelayRecord r WHERE r.capturedAt >= :from AND r.capturedAt < :to")
    Integer findMaxDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── trip-level counts ─────────────────────────────────────────────────────

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT DISTINCT train_code, station_code, train_date, sch_depart
                FROM train_delay_records
                WHERE captured_at >= :from AND captured_at < :to
            ) t
            """, nativeQuery = true)
    Long countUniqueTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM train_delay_records
                WHERE captured_at >= :from AND captured_at < :to
                GROUP BY train_code, station_code, train_date, sch_depart
                HAVING MAX(late_minutes) >= :minDelay
            ) t
            """, nativeQuery = true)
    Long countDelayedUniqueTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                 @Param("minDelay") int minDelay);

    @Query(value = """
            SELECT COALESCE(AVG(peak_delay), 0) FROM (
                SELECT MAX(late_minutes) AS peak_delay
                FROM train_delay_records
                WHERE captured_at >= :from AND captured_at < :to
                GROUP BY train_code, station_code, train_date, sch_depart
                HAVING MAX(late_minutes) >= :minDelay
            ) t
            """, nativeQuery = true)
    Double findAvgPeakDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                            @Param("minDelay") int minDelay);

    // ── station ranking — top 15 ──────────────────────────────────────────────

    @Query(value = """
            WITH trips AS (
                SELECT station_code, station_full_name,
                       train_code, train_date, sch_depart,
                       MAX(late_minutes) AS peak_delay
                FROM train_delay_records
                WHERE captured_at >= :from AND captured_at < :to
                GROUP BY station_code, station_full_name, train_code, train_date, sch_depart
            )
            SELECT station_code, station_full_name,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            LIMIT 15
            """, nativeQuery = true)
    List<Object[]> findStationStatsByTrips(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                           @Param("minDelay") int minDelay);

    // ── station ranking — all stations ────────────────────────────────────────

    @Query(value = """
            WITH trips AS (
                SELECT station_code, station_full_name,
                       train_code, train_date, sch_depart,
                       MAX(late_minutes) AS peak_delay
                FROM train_delay_records
                WHERE captured_at >= :from AND captured_at < :to
                GROUP BY station_code, station_full_name, train_code, train_date, sch_depart
            )
            SELECT station_code, station_full_name,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM trips
            GROUP BY station_code, station_full_name
            ORDER BY delayed_trips DESC, avg_delay DESC
            """, nativeQuery = true)
    List<Object[]> findAllStationStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("minDelay") int minDelay);

    // ── hourly analysis ───────────────────────────────────────────────────────

    @Query(value = """
            SELECT EXTRACT(HOUR FROM captured_at)                                        AS hour,
                   COUNT(*)                                                              AS total,
                   SUM(CASE WHEN late_minutes > 0 THEN 1 ELSE 0 END)                    AS delayed,
                   COALESCE(AVG(CASE WHEN late_minutes > 0
                                     THEN CAST(late_minutes AS FLOAT) END), 0)          AS avg_delay
            FROM train_delay_records
            WHERE captured_at >= :from AND captured_at < :to
            GROUP BY EXTRACT(HOUR FROM captured_at)
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> findHourlyStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── top 10 trips by peak delay ────────────────────────────────────────────

    @Query(value = """
            SELECT train_code, station_full_name, train_date, sch_depart, sch_arrival,
                   direction, origin, destination,
                   MAX(late_minutes)  AS peak_delay,
                   COUNT(*)           AS snapshot_count
            FROM train_delay_records
            WHERE captured_at >= :from AND captured_at < :to
            GROUP BY train_code, station_full_name, train_date, sch_depart, sch_arrival,
                     direction, origin, destination
            HAVING MAX(late_minutes) >= :minDelay
            ORDER BY peak_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTop10TripsByPeakDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             @Param("minDelay") int minDelay);

    // ── destinations ──────────────────────────────────────────────────────────

    @Query(value = """
            WITH trips AS (
                SELECT destination, train_code, station_code, train_date, sch_depart,
                       MAX(late_minutes) AS peak_delay
                FROM train_delay_records
                WHERE destination IS NOT NULL AND destination <> ''
                  AND captured_at >= :from AND captured_at < :to
                GROUP BY destination, train_code, station_code, train_date, sch_depart
            )
            SELECT destination,
                   COUNT(*)                                                              AS total_trips,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay
            FROM trips
            GROUP BY destination
            HAVING SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END) > 0
            ORDER BY avg_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopDestinationsByAvgDelay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                 @Param("minDelay") int minDelay);

    // ── delay categories — raw peak delays (classified in Java from DelayCategory enum) ──

    @Query(value = """
            SELECT MAX(late_minutes)
            FROM train_delay_records
            WHERE captured_at >= :from AND captured_at < :to
            GROUP BY train_code, station_code, train_date, sch_depart
            HAVING MAX(late_minutes) >= :minDelay
            """, nativeQuery = true)
    List<Object> findAllPeakDelaysAboveThreshold(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to,
                                                 @Param("minDelay") int minDelay);

    // ── daily delays ──────────────────────────────────────────────────────────

    @Query(value = """
            WITH delayed_trips AS (
                SELECT train_date,
                       train_code, station_code, sch_depart,
                       MIN(captured_at) AS first_seen
                FROM train_delay_records
                WHERE train_date IS NOT NULL AND train_date <> ''
                  AND captured_at >= :from AND captured_at < :to
                GROUP BY train_date, train_code, station_code, sch_depart
                HAVING MAX(late_minutes) >= :minDelay
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
            WITH trips AS (
                SELECT train_code, train_type,
                       station_code, train_date, sch_depart,
                       MAX(late_minutes) AS peak_delay
                FROM train_delay_records
                GROUP BY train_code, train_type, station_code, train_date, sch_depart
            )
            SELECT train_code,
                   SUM(CASE WHEN peak_delay >= :minDelay THEN 1 ELSE 0 END)             AS delayed_trips,
                   COALESCE(AVG(CASE WHEN peak_delay >= :minDelay
                                     THEN CAST(peak_delay AS FLOAT) END), 0)            AS avg_delay,
                   MAX(peak_delay)                                                      AS max_delay
            FROM trips
            GROUP BY train_code
            ORDER BY delayed_trips DESC, avg_delay DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findTopDelayedTrainsByTrips(Pageable pageable, @Param("minDelay") int minDelay);

    List<TrainDelayRecord> findTop50ByLateMinutesGreaterThanOrderByCapturedAtDesc(int lateMinutes);

    @Query(value = """
            SELECT train_date, COUNT(*) AS delayed_trip_count
            FROM (
                SELECT train_date, train_code, station_code, sch_depart
                FROM train_delay_records
                WHERE train_date IS NOT NULL AND train_date <> ''
                GROUP BY train_date, train_code, station_code, sch_depart
                HAVING MAX(late_minutes) > 0
            ) t
            GROUP BY train_date
            ORDER BY train_date DESC
            LIMIT 30
            """, nativeQuery = true)
    List<Object[]> findDailyDelaysSimple();

    // ── retention cleanup ─────────────────────────────────────────────────────

    @Modifying
    @Query("DELETE FROM TrainDelayRecord r WHERE r.capturedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
