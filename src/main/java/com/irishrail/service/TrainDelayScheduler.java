package com.irishrail.service;

import com.irishrail.model.Station;
import com.irishrail.model.TrainInfo;
import com.irishrail.repository.TripRepository;
import com.irishrail.repository.TripStationSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class TrainDelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrainDelayScheduler.class);

    private final IrishRailService              irishRailService;
    private final DelayTrackingService          delayTrackingService;
    private final TripStationSnapshotRepository snapshotRepository;
    private final TripRepository                tripRepository;
    private final SnapshotEventService          snapshotEventService;

    @Value("${irishrail.retention.days:90}")
    private int retentionDays;

    // key = trainCode|stationCode|trainDate|schDepart → last saved lateMinutes
    private final ConcurrentHashMap<String, Integer> lastSeen = new ConcurrentHashMap<>();

    public TrainDelayScheduler(IrishRailService irishRailService,
                               DelayTrackingService delayTrackingService,
                               TripStationSnapshotRepository snapshotRepository,
                               TripRepository tripRepository,
                               SnapshotEventService snapshotEventService) {
        this.irishRailService     = irishRailService;
        this.delayTrackingService = delayTrackingService;
        this.snapshotRepository   = snapshotRepository;
        this.tripRepository       = tripRepository;
        this.snapshotEventService = snapshotEventService;
    }

    // ── collector: every 30 s during DART operating hours (06:00–00:30) ───────

    @Scheduled(fixedRate = 30_000)
    public void collectAllStations() {
        if (!isDartHours()) return;

        List<Station> stations = irishRailService.getAllDartStations();
        if (stations.isEmpty()) {
            log.warn("Scheduled collect: no stations returned from API");
            return;
        }
        int totalSaved = 0;
        for (Station station : stations) {
            List<TrainInfo> trains = irishRailService.getTrainsByStation(station.getStationCode());
            List<TrainInfo> changed = trains.stream()
                    .filter(this::hasChanged)
                    .collect(Collectors.toList());
            if (!changed.isEmpty()) {
                delayTrackingService.saveAll(changed);
                totalSaved += changed.size();
            }
        }
        if (totalSaved > 0) {
            log.info("Collect: {} snapshots saved across {} stations", totalSaved, stations.size());
        }
        snapshotEventService.broadcast();
    }

    // ── cleanup: every day at 03:00 ──────────────────────────────────────────

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int snapshots = snapshotRepository.deleteByCapturedAtBefore(cutoff);
        int trips     = tripRepository.deleteOrphans();
        lastSeen.clear();
        log.info("Cleanup: {} snapshots and {} orphan trips deleted (older than {}), state map cleared",
                snapshots, trips, cutoff.toLocalDate());
    }

    // ── DART hours check (06:00–00:30, spans midnight) ───────────────────────

    private boolean isDartHours() {
        LocalTime t = LocalTime.now();
        // Inactive window: 00:31–05:59
        return t.isAfter(LocalTime.of(5, 59, 59)) || t.isBefore(LocalTime.of(0, 31));
    }

    // ── state-change filter ───────────────────────────────────────────────────

    private boolean hasChanged(TrainInfo t) {
        String key = t.getTrainCode()  + "|"
                   + t.getStationCode() + "|"
                   + t.getTrainDate()   + "|"
                   + t.getSchDepart();
        Integer last    = lastSeen.get(key);
        int     current = t.getLate();
        if (last == null || !last.equals(current)) {
            lastSeen.put(key, current);
            return true;
        }
        return false;
    }
}
