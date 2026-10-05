package com.irishrail.service;

import com.irishrail.model.*;
import com.irishrail.repository.TripRepository;
import com.irishrail.repository.TripStationSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DelayTrackingService {

    private static final int DELAYED_MIN    = DelayCategory.delayedThreshold();
    private static final int MAX_STAT_DELAY = DelayLimits.MAX_STAT_DELAY_MINUTES;

    /** A trip key is train code + service date, so the cache grows by a few hundred entries a day. */
    private static final int TRIP_CACHE_MAX = 20_000;

    private static final String INSERT_SNAPSHOT = """
            INSERT INTO trip_station_snapshot
                (captured_at, exp_arrival, exp_depart, late_minutes, sch_arrival, sch_depart,
                 service_scope, station_code, station_full_name, status, trip_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final int[] INSERT_TYPES = {
            Types.TIMESTAMP, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT };

    private final TripRepository                tripRepository;
    private final TripStationSnapshotRepository snapshotRepository;
    private final AnalyticsAggregateService     analyticsAggregateService;
    private final JdbcTemplate                  jdbc;

    /** trainCode|trainDate → trip id. Trips are immutable once created, so this never goes stale. */
    private final ConcurrentHashMap<String, Long> tripIds = new ConcurrentHashMap<>();

    public DelayTrackingService(TripRepository tripRepository,
                                TripStationSnapshotRepository snapshotRepository,
                                AnalyticsAggregateService analyticsAggregateService,
                                JdbcTemplate jdbc) {
        this.tripRepository     = tripRepository;
        this.snapshotRepository = snapshotRepository;
        this.analyticsAggregateService = analyticsAggregateService;
        this.jdbc = jdbc;
    }

    /** What one collection cycle wrote: the trips touched and the timestamp stamped on every row. */
    public record SavedBatch(Set<Long> tripIds, LocalDateTime capturedAt) {
        public static SavedBatch empty(LocalDateTime at) { return new SavedBatch(Set.of(), at); }
    }

    // ── write ─────────────────────────────────────────────────────────────────

    /**
     * Persists one cycle's changed departures. Previously every train cost a trip lookup plus an
     * entity insert — two round-trips each, ~240k a day. Trip ids are now resolved from an
     * in-memory cache (one {@code IN} query for the misses) and the snapshots go in as a single
     * JDBC batch. {@code IDENTITY} generation would have silently disabled Hibernate's batching,
     * which is why this bypasses the entity for the insert.
     */
    @Transactional
    public SavedBatch saveAll(List<TrainInfo> trains, String serviceScope) {
        LocalDateTime now = LocalDateTime.now();
        if (trains == null || trains.isEmpty()) return SavedBatch.empty(now);

        Map<String, Long> resolved = resolveTripIds(trains);

        Timestamp capturedAt = Timestamp.valueOf(now);
        List<Object[]> rows = new ArrayList<>(trains.size());
        Set<Long> touched = new HashSet<>();
        for (TrainInfo t : trains) {
            Long tripId = resolved.get(tripKey(t.getTrainCode(), t.getTrainDate()));
            if (tripId == null) continue;
            touched.add(tripId);
            rows.add(new Object[] {
                    capturedAt, t.getExpArrival(), t.getExpDepart(), t.getLate(),
                    t.getSchArrival(), t.getSchDepart(), serviceScope,
                    t.getStationCode(), t.getStationFullName(), t.getStatus(), tripId });
        }
        if (!rows.isEmpty()) jdbc.batchUpdate(INSERT_SNAPSHOT, rows, INSERT_TYPES);
        return new SavedBatch(touched, now);
    }

    private Map<String, Long> resolveTripIds(List<TrainInfo> trains) {
        Map<String, TrainInfo> wanted = new LinkedHashMap<>();
        for (TrainInfo t : trains) wanted.putIfAbsent(tripKey(t.getTrainCode(), t.getTrainDate()), t);

        Map<String, Long> resolved = new HashMap<>();
        List<TrainInfo> missing = new ArrayList<>();
        for (Map.Entry<String, TrainInfo> e : wanted.entrySet()) {
            Long cached = tripIds.get(e.getKey());
            if (cached != null) resolved.put(e.getKey(), cached);
            else missing.add(e.getValue());
        }
        if (missing.isEmpty()) return resolved;

        // One query for every cache miss in the batch.
        StringBuilder sql = new StringBuilder("SELECT id, train_code, train_date FROM trip WHERE (train_code, train_date) IN (");
        List<Object> args = new ArrayList<>(missing.size() * 2);
        for (int i = 0; i < missing.size(); i++) {
            sql.append(i == 0 ? "(?, ?)" : ", (?, ?)");
            args.add(missing.get(i).getTrainCode());
            args.add(missing.get(i).getTrainDate());
        }
        sql.append(')');
        jdbc.query(sql.toString(), rs -> {
            String key = tripKey(rs.getString("train_code"), rs.getString("train_date"));
            long id = rs.getLong("id");
            resolved.put(key, id);
            remember(key, id);
        }, args.toArray());

        // Whatever is still unknown is genuinely a new trip (first sighting of the day).
        for (TrainInfo t : missing) {
            String key = tripKey(t.getTrainCode(), t.getTrainDate());
            if (resolved.containsKey(key)) continue;
            Trip trip = new Trip();
            trip.setTrainCode(t.getTrainCode());
            trip.setTrainDate(t.getTrainDate());
            trip.setTrainType(t.getTrainType());
            trip.setOrigin(t.getOrigin());
            trip.setDestination(t.getDestination());
            trip.setDirection(t.getDirection());
            Long id = tripRepository.save(trip).getId();
            resolved.put(key, id);
            remember(key, id);
        }
        return resolved;
    }

    private void remember(String key, long id) {
        if (tripIds.size() >= TRIP_CACHE_MAX) tripIds.clear();
        tripIds.put(key, id);
    }

    /** Called by the nightly retention sweep: trips older than the raw window no longer exist. */
    public void clearTripCache() {
        tripIds.clear();
    }

    private static String tripKey(String trainCode, String trainDate) {
        return trainCode + "|" + trainDate;
    }

    // ── dashboard summary ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummary(LocalDate from, LocalDate to) {
        return analyticsAggregateService.dashboard(from, to);
    }

    // ── station ranking — top 15 ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StationStats> getStationRanking(LocalDate from, LocalDate to) {
        return analyticsAggregateService.stationRanking(from, to, true);
    }

    // ── station ranking — all stations ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StationStats> getAllStationRanking(LocalDate from, LocalDate to) {
        return analyticsAggregateService.stationRanking(from, to, false);
    }

    @Transactional(readOnly = true)
    public List<StationStats> getAllStationRankingForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.stationRankingForScope(from, to, stationCode, false);
    }

    // ── hourly analysis ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<HourlyStats> getHourlyStats(LocalDate from, LocalDate to) {
        return analyticsAggregateService.hourly(from, to, null);
    }

    // ── top 10 trips by peak delay ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TripDelaySummary> getTop10LargestDelays(LocalDate from, LocalDate to) {
        return analyticsAggregateService.topDelays(from, to, null);
    }

    // ── destinations ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DestinationStats> getTopDestinationsByDelay(LocalDate from, LocalDate to) {
        return analyticsAggregateService.destinations(from, to, null);
    }

    // ── delay categories ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Long> getDelayCategories(LocalDate from, LocalDate to) {
        return analyticsAggregateService.delayCategories(from, to, null);
    }

    // ── daily delays ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Long> getDailyDelays(LocalDate from, LocalDate to) {
        return analyticsAggregateService.dailyDelays(from, to);
    }

    // ── station-filtered analytics ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummaryForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.dashboardForStation(from, to, stationCode);
    }

    @Transactional(readOnly = true)
    public List<HourlyStats> getHourlyStatsForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.hourly(from, to, stationCode);
    }

    @Transactional(readOnly = true)
    public List<TripDelaySummary> getTop10LargestDelaysForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.topDelays(from, to, stationCode);
    }

    @Transactional(readOnly = true)
    public List<DestinationStats> getTopDestinationsByDelayForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.destinations(from, to, stationCode);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getDelayCategoriesForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.delayCategories(from, to, stationCode);
    }

    // ── recent delayed trips (one per trip, deduped) ─────────────────────────

    @Transactional(readOnly = true)
    public List<RecentDelayEntry> getRecentDelayedTrips() {
        return snapshotRepository.findTop5RecentDelaysPerTripForScopes(analyticsAggregateService.serviceScopes(), MAX_STAT_DELAY).stream()
                .map(this::toRecentDelayEntry)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RecentDelayEntry> getRecentDelayedTripsForStation(String stationCode) {
        return snapshotRepository.findTop5RecentDelaysPerTripForScope(serviceScope(stationCode), MAX_STAT_DELAY).stream()
                .map(this::toRecentDelayEntry)
                .collect(Collectors.toList());
    }

    private RecentDelayEntry toRecentDelayEntry(Object[] r) {
        return new RecentDelayEntry(
                (String) r[0],
                (String) r[1],
                ((Number) r[2]).intValue(),
                formatCapturedAt(r[3]),
                (String) r[4],
                (String) r[5]);
    }

    // ── legacy REST API ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TrainDelaySummary> getTopDelayedTrains(int limit) {
        return snapshotRepository.findTopDelayedTrainsByTrips(DELAYED_MIN, MAX_STAT_DELAY)
                .stream()
                .map(r -> new TrainDelaySummary(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).doubleValue(),
                        ((Number) r[3]).intValue()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TripStationSnapshot> getRecentDelays() {
        return snapshotRepository.findRecentDelayedWithTrip(DELAYED_MIN - 1, MAX_STAT_DELAY);
    }

    // ── route ranking ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RouteStats> getTopRoutesByDelay(LocalDate from, LocalDate to) {
        return analyticsAggregateService.routes(from, to, null);
    }

    @Transactional(readOnly = true)
    public List<RouteStats> getTopRoutesByDelayForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.routes(from, to, stationCode);
    }

    // ── per-train history ─────────────────────────────────────────────────────

    /** Recorded delay history for a single train code, as surfaced from the live map. */
    @Transactional(readOnly = true)
    public TrainHistory getTrainHistory(String trainCode, int maxRows) {
        if (trainCode == null || trainCode.isBlank()) return TrainHistory.empty(trainCode);
        String code = trainCode.trim();

        List<TrainHistory.Entry> recent = snapshotRepository
                .findRecentSnapshotsByTrainCode(code, MAX_STAT_DELAY, maxRows).stream()
                .map(r -> {
                    int late = ((Number) r[4]).intValue();
                    return new TrainHistory.Entry(
                            (String) r[0],
                            (String) r[1],
                            (String) r[2],
                            (String) r[3],
                            late,
                            formatCapturedAt(r[5]),
                            DelayCategory.of(Math.max(0, late)).getTextColor());
                })
                .collect(Collectors.toList());

        List<Object[]> stats = snapshotRepository.findTrainCodeStats(code, DELAYED_MIN, MAX_STAT_DELAY);
        if (stats.isEmpty() || stats.get(0)[1] == null || ((Number) stats.get(0)[1]).longValue() == 0L) {
            return new TrainHistory(code, 0L, 0L, 0d, 0, 0d, recent);
        }

        Object[] row = stats.get(0);
        long days       = ((Number) row[0]).longValue();
        long snapshots  = ((Number) row[1]).longValue();
        double avgDelay = ((Number) row[2]).doubleValue();
        int maxDelay    = ((Number) row[3]).intValue();
        long delayed    = row[4] == null ? 0L : ((Number) row[4]).longValue();

        return new TrainHistory(
                code, days, snapshots,
                Math.round(avgDelay * 10d) / 10d,
                maxDelay,
                Math.round(delayed * 1000d / snapshots) / 10d,
                recent);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private String formatCapturedAt(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Timestamp) return ((Timestamp) raw).toLocalDateTime().format(HH_MM);
        if (raw instanceof LocalDateTime) return ((LocalDateTime) raw).format(HH_MM);
        return raw.toString();
    }

    private String serviceScope(String overviewCode) {
        String scope = ServiceScope.fromOverviewCode(overviewCode);
        return scope != null ? scope : ServiceScope.CONNOLLY;
    }

}
