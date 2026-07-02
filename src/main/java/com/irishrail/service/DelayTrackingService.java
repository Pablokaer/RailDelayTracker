package com.irishrail.service;

import com.irishrail.model.*;
import com.irishrail.repository.TripRepository;
import com.irishrail.repository.TripStationSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DelayTrackingService {

    private static final LocalDateTime EPOCH       = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final int           DELAYED_MIN = DelayCategory.delayedThreshold();
    private static final int           MAX_STAT_DELAY = DelayLimits.MAX_STAT_DELAY_MINUTES;

    private final TripRepository                tripRepository;
    private final TripStationSnapshotRepository snapshotRepository;
    private final AnalyticsAggregateService     analyticsAggregateService;

    public DelayTrackingService(TripRepository tripRepository,
                                TripStationSnapshotRepository snapshotRepository,
                                AnalyticsAggregateService analyticsAggregateService) {
        this.tripRepository     = tripRepository;
        this.snapshotRepository = snapshotRepository;
        this.analyticsAggregateService = analyticsAggregateService;
    }

    // ── write ─────────────────────────────────────────────────────────────────

    @Transactional
    public void saveAll(List<TrainInfo> trains, String serviceScope) {
        if (trains == null || trains.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (TrainInfo t : trains) {
            Trip trip = tripRepository.findByTrainCodeAndTrainDate(t.getTrainCode(), t.getTrainDate())
                    .orElseGet(() -> {
                        Trip newTrip = new Trip();
                        newTrip.setTrainCode(t.getTrainCode());
                        newTrip.setTrainDate(t.getTrainDate());
                        newTrip.setTrainType(t.getTrainType());
                        newTrip.setOrigin(t.getOrigin());
                        newTrip.setDestination(t.getDestination());
                        newTrip.setDirection(t.getDirection());
                        return tripRepository.save(newTrip);
                    });

            TripStationSnapshot snapshot = new TripStationSnapshot();
            snapshot.setTrip(trip);
            snapshot.setStationCode(t.getStationCode());
            snapshot.setStationFullName(t.getStationFullName());
            snapshot.setServiceScope(serviceScope);
            snapshot.setSchDepart(t.getSchDepart());
            snapshot.setSchArrival(t.getSchArrival());
            snapshot.setExpDepart(t.getExpDepart());
            snapshot.setExpArrival(t.getExpArrival());
            snapshot.setLateMinutes(t.getLate());
            snapshot.setStatus(t.getStatus());
            snapshot.setCapturedAt(now);
            snapshotRepository.save(snapshot);
        }
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
        return snapshotRepository.findHourlyStatsForScopes(resolveFrom(from), resolveTo(to), analyticsAggregateService.serviceScopes(), MAX_STAT_DELAY).stream()
                .map(r -> new HourlyStats(
                        ((Number) r[0]).intValue(),
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).doubleValue()
                ))
                .collect(Collectors.toList());
    }

    // ── top 10 trips by peak delay ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TripDelaySummary> getTop10LargestDelays(LocalDate from, LocalDate to) {
        return snapshotRepository.findTop10TripsByPeakDelayForScopes(resolveFrom(from), resolveTo(to), analyticsAggregateService.serviceScopes(), DELAYED_MIN, MAX_STAT_DELAY).stream()
                .map(r -> new TripDelaySummary(
                        (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                        ((Number) r[8]).intValue(), ((Number) r[9]).longValue(),
                        formatCapturedAt(r[10])
                ))
                .collect(Collectors.toList());
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
        Map<String, Long> result = new LinkedHashMap<>();
        snapshotRepository.findDailyDelaysByTrips(resolveFrom(from), resolveTo(to), DELAYED_MIN, MAX_STAT_DELAY)
                          .forEach(r -> result.put((String) r[0], ((Number) r[1]).longValue()));
        return result;
    }

    // ── station-filtered analytics ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummaryForStation(LocalDate from, LocalDate to, String stationCode) {
        return analyticsAggregateService.dashboardForStation(from, to, stationCode);
    }

    @Transactional(readOnly = true)
    public List<HourlyStats> getHourlyStatsForStation(LocalDate from, LocalDate to, String stationCode) {
        return snapshotRepository.findHourlyStatsForScope(resolveFrom(from), resolveTo(to), serviceScope(stationCode), MAX_STAT_DELAY).stream()
                .map(r -> new HourlyStats(
                        ((Number) r[0]).intValue(),
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).doubleValue()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TripDelaySummary> getTop10LargestDelaysForStation(LocalDate from, LocalDate to, String stationCode) {
        return snapshotRepository.findTop10TripsByPeakDelayForScope(resolveFrom(from), resolveTo(to), serviceScope(stationCode), DELAYED_MIN, MAX_STAT_DELAY).stream()
                .map(r -> new TripDelaySummary(
                        (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                        ((Number) r[8]).intValue(), ((Number) r[9]).longValue(),
                        formatCapturedAt(r[10])
                ))
                .collect(Collectors.toList());
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
        return snapshotRepository.findTop5RecentDelaysPerTripForScopes(analyticsAggregateService.serviceScopes(), DELAYED_MIN, MAX_STAT_DELAY).stream()
                .map(r -> new RecentDelayEntry(
                        (String) r[0],
                        (String) r[1],
                        ((Number) r[2]).intValue(),
                        formatCapturedAt(r[3]),
                        (String) r[4],
                        (String) r[5]
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RecentDelayEntry> getRecentDelayedTripsForStation(String stationCode) {
        return snapshotRepository.findTop5RecentDelaysPerTripForScope(serviceScope(stationCode), DELAYED_MIN, MAX_STAT_DELAY).stream()
                .map(r -> new RecentDelayEntry(
                        (String) r[0],
                        (String) r[1],
                        ((Number) r[2]).intValue(),
                        formatCapturedAt(r[3]),
                        (String) r[4],
                        (String) r[5]
                ))
                .collect(Collectors.toList());
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
        return snapshotRepository.findTop50ByLateMinutesGreaterThanAndLateMinutesLessThanEqualOrderByCapturedAtDesc(DELAYED_MIN - 1, MAX_STAT_DELAY);
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

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private String formatCapturedAt(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Timestamp) return ((Timestamp) raw).toLocalDateTime().format(HH_MM);
        if (raw instanceof LocalDateTime) return ((LocalDateTime) raw).format(HH_MM);
        return raw.toString();
    }

    private LocalDateTime resolveFrom(LocalDate d) {
        return d != null ? d.atStartOfDay() : EPOCH;
    }

    private LocalDateTime resolveTo(LocalDate d) {
        return d != null ? d.plusDays(1).atStartOfDay() : LocalDateTime.now().plusDays(1);
    }

    private String serviceScope(String overviewCode) {
        String scope = ServiceScope.fromOverviewCode(overviewCode);
        return scope != null ? scope : ServiceScope.CONNOLLY;
    }

}
