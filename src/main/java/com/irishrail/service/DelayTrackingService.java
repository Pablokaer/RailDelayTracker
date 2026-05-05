package com.irishrail.service;

import com.irishrail.model.*;
import com.irishrail.repository.TripRepository;
import com.irishrail.repository.TripStationSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DelayTrackingService {

    private static final LocalDateTime EPOCH       = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final int           DELAYED_MIN = DelayCategory.delayedThreshold();

    private final TripRepository                tripRepository;
    private final TripStationSnapshotRepository snapshotRepository;

    public DelayTrackingService(TripRepository tripRepository,
                                TripStationSnapshotRepository snapshotRepository) {
        this.tripRepository     = tripRepository;
        this.snapshotRepository = snapshotRepository;
    }

    // ── write ─────────────────────────────────────────────────────────────────

    @Transactional
    public void saveAll(List<TrainInfo> trains) {
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
        LocalDateTime f = resolveFrom(from);
        LocalDateTime t = resolveTo(to);
        long total   = snapshotRepository.countByPeriod(f, t);
        long unique  = total > 0 ? snapshotRepository.countUniqueTrips(f, t)                        : 0L;
        long delayed = total > 0 ? snapshotRepository.countDelayedUniqueTrips(f, t, DELAYED_MIN)    : 0L;
        Double avg   = total > 0 ? snapshotRepository.findAvgPeakDelay(f, t, DELAYED_MIN)           : null;
        Integer max  = total > 0 ? snapshotRepository.findMaxDelay(f, t)                            : null;
        return new DashboardSummary(
                total, unique, delayed,
                avg != null ? avg : 0.0,
                max != null ? max : 0
        );
    }

    // ── station ranking — top 15 ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StationStats> getStationRanking(LocalDate from, LocalDate to) {
        return snapshotRepository.findStationStatsByTrips(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
                .map(r -> new StationStats(
                        (String) r[0], (String) r[1],
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).longValue(),
                        ((Number) r[4]).doubleValue()
                ))
                .collect(Collectors.toList());
    }

    // ── station ranking — all stations ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StationStats> getAllStationRanking(LocalDate from, LocalDate to) {
        return snapshotRepository.findAllStationStats(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
                .map(r -> new StationStats(
                        (String) r[0], (String) r[1],
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).longValue(),
                        ((Number) r[4]).doubleValue()
                ))
                .collect(Collectors.toList());
    }

    // ── hourly analysis ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<HourlyStats> getHourlyStats(LocalDate from, LocalDate to) {
        return snapshotRepository.findHourlyStats(resolveFrom(from), resolveTo(to)).stream()
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
        return snapshotRepository.findTop10TripsByPeakDelay(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
                .map(r -> new TripDelaySummary(
                        (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                        ((Number) r[8]).intValue(), ((Number) r[9]).longValue()
                ))
                .collect(Collectors.toList());
    }

    // ── destinations ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DestinationStats> getTopDestinationsByDelay(LocalDate from, LocalDate to) {
        return snapshotRepository.findTopDestinationsByAvgDelay(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
                .map(r -> new DestinationStats(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).doubleValue()
                ))
                .collect(Collectors.toList());
    }

    // ── delay categories ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Long> getDelayCategories(LocalDate from, LocalDate to) {
        List<Object> peaks = snapshotRepository.findAllPeakDelaysAboveThreshold(
                resolveFrom(from), resolveTo(to), DELAYED_MIN);

        Map<String, Long> dist = new LinkedHashMap<>();
        for (DelayCategory cat : DelayCategory.values()) {
            if (cat.isDelayed()) dist.put(cat.getDisplayLabel(), 0L);
        }
        for (Object o : peaks) {
            if (o == null) continue;
            DelayCategory cat = DelayCategory.of(((Number) o).intValue());
            if (cat.isDelayed()) dist.merge(cat.getDisplayLabel(), 1L, Long::sum);
        }
        return dist;
    }

    // ── daily delays ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Long> getDailyDelays(LocalDate from, LocalDate to) {
        Map<String, Long> result = new LinkedHashMap<>();
        snapshotRepository.findDailyDelaysByTrips(resolveFrom(from), resolveTo(to), DELAYED_MIN)
                          .forEach(r -> result.put((String) r[0], ((Number) r[1]).longValue()));
        return result;
    }

    // ── station-filtered analytics ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummaryForStation(LocalDate from, LocalDate to, String stationCode) {
        LocalDateTime f = resolveFrom(from);
        LocalDateTime t = resolveTo(to);
        long total   = snapshotRepository.countByPeriodAndStation(f, t, stationCode);
        long unique  = total > 0 ? snapshotRepository.countUniqueTripsForStation(f, t, stationCode)                        : 0L;
        long delayed = total > 0 ? snapshotRepository.countDelayedUniqueTripsForStation(f, t, stationCode, DELAYED_MIN)    : 0L;
        Double avg   = total > 0 ? snapshotRepository.findAvgPeakDelayForStation(f, t, stationCode, DELAYED_MIN)           : null;
        Integer max  = total > 0 ? snapshotRepository.findMaxDelayForStation(f, t, stationCode)                            : null;
        return new DashboardSummary(total, unique, delayed, avg != null ? avg : 0.0, max != null ? max : 0);
    }

    @Transactional(readOnly = true)
    public List<HourlyStats> getHourlyStatsForStation(LocalDate from, LocalDate to, String stationCode) {
        return snapshotRepository.findHourlyStatsForStation(resolveFrom(from), resolveTo(to), stationCode).stream()
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
        return snapshotRepository.findTop10TripsByPeakDelayForStation(resolveFrom(from), resolveTo(to), stationCode, DELAYED_MIN).stream()
                .map(r -> new TripDelaySummary(
                        (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                        ((Number) r[8]).intValue(), ((Number) r[9]).longValue()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DestinationStats> getTopDestinationsByDelayForStation(LocalDate from, LocalDate to, String stationCode) {
        return snapshotRepository.findTopDestinationsByAvgDelayForStation(resolveFrom(from), resolveTo(to), stationCode, DELAYED_MIN).stream()
                .map(r -> new DestinationStats(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).doubleValue()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getDelayCategoriesForStation(LocalDate from, LocalDate to, String stationCode) {
        List<Object> peaks = snapshotRepository.findAllPeakDelaysAboveThresholdForStation(
                resolveFrom(from), resolveTo(to), stationCode, DELAYED_MIN);
        Map<String, Long> dist = new LinkedHashMap<>();
        for (DelayCategory cat : DelayCategory.values()) {
            if (cat.isDelayed()) dist.put(cat.getDisplayLabel(), 0L);
        }
        for (Object o : peaks) {
            if (o == null) continue;
            DelayCategory cat = DelayCategory.of(((Number) o).intValue());
            if (cat.isDelayed()) dist.merge(cat.getDisplayLabel(), 1L, Long::sum);
        }
        return dist;
    }

    // ── legacy REST API ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TrainDelaySummary> getTopDelayedTrains(int limit) {
        return snapshotRepository.findTopDelayedTrainsByTrips(DELAYED_MIN)
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
        return snapshotRepository.findTop50ByLateMinutesGreaterThanOrderByCapturedAtDesc(DELAYED_MIN - 1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LocalDateTime resolveFrom(LocalDate d) {
        return d != null ? d.atStartOfDay() : EPOCH;
    }

    private LocalDateTime resolveTo(LocalDate d) {
        return d != null ? d.plusDays(1).atStartOfDay() : LocalDateTime.now().plusDays(1);
    }
}
