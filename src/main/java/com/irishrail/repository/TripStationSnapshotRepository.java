package com.irishrail.repository;

import com.irishrail.model.DelayLimits;
import com.irishrail.model.TripStationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Raw-snapshot reads that the daily aggregates cannot answer: the live "recent delays" feed, the
 * per-train history opened from the map, and the retention sweep.
 *
 * <p>Every date-ranged analytics query used to live here as well (~30 native queries). They were
 * all superseded by {@code AnalyticsAggregateService} and are gone; each one cost a parse at boot
 * and none had a caller.
 *
 * <p>The delayed threshold is inlined as a literal rather than bound as a parameter on purpose:
 * {@code idx_tss_delayed_station_captured} is a partial index on {@code late_minutes >= 5}, and
 * the planner can only prove a generic (server-prepared) plan implies that predicate when the
 * constant is visible in the SQL text.
 */
public interface TripStationSnapshotRepository extends JpaRepository<TripStationSnapshot, Long> {

    String DELAYED = "" + DelayLimits.DELAYED_THRESHOLD_MINUTES;

    // ── recent delayed trips (one per trip, deduped) ─────────────────────────

    @Query(value = """
            SELECT sub.train_code, sub.station_full_name, sub.late_minutes, sub.captured_at,
                   sub.origin, sub.destination
            FROM (
                SELECT DISTINCT ON (s.trip_id)
                       t.train_code, s.station_full_name, s.late_minutes, s.captured_at,
                       t.origin, t.destination
                FROM trip_station_snapshot s
                JOIN trip t ON t.id = s.trip_id
                WHERE s.late_minutes >= """ + DELAYED + """

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
                WHERE s.late_minutes >= """ + DELAYED + """

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

    /**
     * {@code JOIN FETCH} because these entities are serialised to JSON after the transaction has
     * closed; with {@code open-in-view} off and the association lazy, a plain finder would throw
     * on the first {@code getTrip()}.
     */
    @Query("""
            SELECT s FROM TripStationSnapshot s JOIN FETCH s.trip
            WHERE s.lateMinutes > :minDelay AND s.lateMinutes <= :maxStatDelay
            ORDER BY s.capturedAt DESC
            LIMIT 50
            """)
    List<TripStationSnapshot> findRecentDelayedWithTrip(@Param("minDelay") int minDelay,
                                                        @Param("maxStatDelay") int maxStatDelay);

    // ── retention cleanup ─────────────────────────────────────────────────────

    @Modifying
    @Query("DELETE FROM TripStationSnapshot s WHERE s.capturedAt < :cutoff")
    int deleteByCapturedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    // ── per-train history (used by the live map) ──────────────────────────────
    // Irish Rail pads train codes ("A408 "), and stored rows keep the padding, so both
    // sides are trimmed. idx_trip_train_code_trimmed in DatabaseIndexInitializer covers
    // the resulting expression.

    @Query(value = """
            SELECT t.train_date,
                   s.station_full_name,
                   s.station_code,
                   s.sch_depart,
                   s.late_minutes,
                   s.captured_at
            FROM trip_station_snapshot s
            JOIN trip t ON t.id = s.trip_id
            WHERE UPPER(TRIM(t.train_code)) = UPPER(TRIM(:trainCode))
              AND s.late_minutes <= :maxDelay
            ORDER BY s.captured_at DESC
            LIMIT :maxRows
            """, nativeQuery = true)
    List<Object[]> findRecentSnapshotsByTrainCode(@Param("trainCode") String trainCode,
                                                  @Param("maxDelay") int maxDelay,
                                                  @Param("maxRows") int maxRows);

    @Query(value = """
            SELECT COUNT(DISTINCT t.train_date)                    AS days_tracked,
                   COUNT(*)                                        AS snapshots,
                   COALESCE(AVG(CAST(s.late_minutes AS FLOAT)), 0)  AS avg_delay,
                   COALESCE(MAX(s.late_minutes), 0)                AS max_delay,
                   SUM(CASE WHEN s.late_minutes >= :minDelay THEN 1 ELSE 0 END) AS delayed
            FROM trip_station_snapshot s
            JOIN trip t ON t.id = s.trip_id
            WHERE UPPER(TRIM(t.train_code)) = UPPER(TRIM(:trainCode))
              AND s.late_minutes <= :maxDelay
            """, nativeQuery = true)
    List<Object[]> findTrainCodeStats(@Param("trainCode") String trainCode,
                                      @Param("minDelay") int minDelay,
                                      @Param("maxDelay") int maxDelay);
}
