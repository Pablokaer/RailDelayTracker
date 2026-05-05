package com.irishrail.service;

import com.irishrail.model.*;
import com.irishrail.repository.TrainDelayRepository;
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

    private final TrainDelayRepository repository;

    public DelayTrackingService(TrainDelayRepository repository) {
        this.repository = repository;
    }

    // ── write ─────────────────────────────────────────────────────────────────

    @Transactional
    public void saveAll(List<TrainInfo> trains) {
        if (trains == null || trains.isEmpty()) return;
        repository.saveAll(
                trains.stream()
                      .map(TrainDelayRecord::from)
                      .collect(Collectors.toList())
        );
    }

    // ── dashboard summary ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummary(LocalDate from, LocalDate to) {
        LocalDateTime f = resolveFrom(from);
        LocalDateTime t = resolveTo(to);
        long total   = repository.countByPeriod(f, t);
        long unique  = total > 0 ? repository.countUniqueTrips(f, t)                        : 0L;
        long delayed = total > 0 ? repository.countDelayedUniqueTrips(f, t, DELAYED_MIN)    : 0L;
        Double avg   = total > 0 ? repository.findAvgPeakDelay(f, t, DELAYED_MIN)           : null;
        Integer max  = total > 0 ? repository.findMaxDelay(f, t)                            : null;
        return new DashboardSummary(
                total, unique, delayed,
                avg != null ? avg : 0.0,
                max != null ? max : 0
        );
    }

    // ── station ranking — top 15 ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StationStats> getStationRanking(LocalDate from, LocalDate to) {
        return repository.findStationStatsByTrips(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
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
        return repository.findAllStationStats(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
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
        return repository.findHourlyStats(resolveFrom(from), resolveTo(to)).stream()
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
        return repository.findTop10TripsByPeakDelay(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
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
        return repository.findTopDestinationsByAvgDelay(resolveFrom(from), resolveTo(to), DELAYED_MIN).stream()
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
        List<Object> peaks = repository.findAllPeakDelaysAboveThreshold(
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
        repository.findDailyDelaysByTrips(resolveFrom(from), resolveTo(to), DELAYED_MIN)
                  .forEach(r -> result.put((String) r[0], ((Number) r[1]).longValue()));
        return result;
    }

    // ── station-filtered analytics ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummaryForStation(LocalDate from, LocalDate to, String stationCode) {
        LocalDateTime f = resolveFrom(from);
        LocalDateTime t = resolveTo(to);
        long total   = repository.countByPeriodAndStation(f, t, stationCode);
        long unique  = total > 0 ? repository.countUniqueTripsForStation(f, t, stationCode)                        : 0L;
        long delayed = total > 0 ? repository.countDelayedUniqueTripsForStation(f, t, stationCode, DELAYED_MIN)    : 0L;
        Double avg   = total > 0 ? repository.findAvgPeakDelayForStation(f, t, stationCode, DELAYED_MIN)           : null;
        Integer max  = total > 0 ? repository.findMaxDelayForStation(f, t, stationCode)                            : null;
        return new DashboardSummary(total, unique, delayed, avg != null ? avg : 0.0, max != null ? max : 0);
    }

    @Transactional(readOnly = true)
    public List<HourlyStats> getHourlyStatsForStation(LocalDate from, LocalDate to, String stationCode) {
        return repository.findHourlyStatsForStation(resolveFrom(from), resolveTo(to), stationCode).stream()
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
        return repository.findTop10TripsByPeakDelayForStation(resolveFrom(from), resolveTo(to), stationCode, DELAYED_MIN).stream()
                .map(r -> new TripDelaySummary(
                        (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (String) r[4], (String) r[5], (String) r[6], (String) r[7],
                        ((Number) r[8]).intValue(), ((Number) r[9]).longValue()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DestinationStats> getTopDestinationsByDelayForStation(LocalDate from, LocalDate to, String stationCode) {
        return repository.findTopDestinationsByAvgDelayForStation(resolveFrom(from), resolveTo(to), stationCode, DELAYED_MIN).stream()
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
        List<Object> peaks = repository.findAllPeakDelaysAboveThresholdForStation(
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
        return repository.findTopDelayedTrainsByTrips(
                org.springframework.data.domain.PageRequest.of(0, limit), DELAYED_MIN)
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
    public List<TrainDelayRecord> getRecentDelays() {
        return repository.findTop50ByLateMinutesGreaterThanOrderByCapturedAtDesc(DELAYED_MIN - 1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LocalDateTime resolveFrom(LocalDate d) {
        return d != null ? d.atStartOfDay() : EPOCH;
    }

    private LocalDateTime resolveTo(LocalDate d) {
        return d != null ? d.plusDays(1).atStartOfDay() : LocalDateTime.now().plusDays(1);
    }
}
