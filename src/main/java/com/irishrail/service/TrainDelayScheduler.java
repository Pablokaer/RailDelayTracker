package com.irishrail.service;

import com.irishrail.model.Station;
import com.irishrail.model.TrainInfo;
import com.irishrail.model.ServiceScope;
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
    private final AnalyticsAggregateService     analyticsAggregateService;

    @Value("${irishrail.retention.days:90}")
    private int retentionDays;

    // key = trainCode|stationCode|trainDate|schDepart → last saved lateMinutes
    private final ConcurrentHashMap<String, Integer> lastSeen = new ConcurrentHashMap<>();

    public TrainDelayScheduler(IrishRailService irishRailService,
                               DelayTrackingService delayTrackingService,
                               TripStationSnapshotRepository snapshotRepository,
                               TripRepository tripRepository,
                               SnapshotEventService snapshotEventService,
                               AnalyticsAggregateService analyticsAggregateService) {
        this.irishRailService     = irishRailService;
        this.delayTrackingService = delayTrackingService;
        this.snapshotRepository   = snapshotRepository;
        this.tripRepository       = tripRepository;
        this.snapshotEventService = snapshotEventService;
        this.analyticsAggregateService = analyticsAggregateService;
    }

    // ── collector: every 30 s during DART operating hours (06:00–00:30) ───────

    @Scheduled(fixedRate = 30_000)
    public void collectAllStations() {
        if (!isDartHours()) return;

        List<Station> connollyStations = irishRailService.getConnollyCollectionStations();
        List<Station> heustonStations = irishRailService.getHeustonCollectionStations();
        if (connollyStations.isEmpty() && heustonStations.isEmpty()) {
            log.warn("Scheduled collect: no stations returned from API");
            return;
        }
        int totalSaved = 0;
        int stationChecks = 0;

        for (Station station : connollyStations) {
            stationChecks++;
            List<TrainInfo> connollyTrains = irishRailService.getTrainsByStation(station.getStationCode(), false);
            List<TrainInfo> changedConnolly = connollyTrains.stream()
                    .filter(t -> hasChanged(t, ServiceScope.CONNOLLY))
                    .collect(Collectors.toList());
            if (!changedConnolly.isEmpty()) {
                delayTrackingService.saveAll(changedConnolly, ServiceScope.CONNOLLY);
                totalSaved += changedConnolly.size();
            }
        }

        for (Station station : heustonStations) {
            stationChecks++;
            List<TrainInfo> heustonTrains = irishRailService.getTrainsByStation(station.getStationCode(), true).stream()
                    .filter(IrishRailService::isHeustonRelated)
                    .collect(Collectors.toList());
            if ("HSTON".equalsIgnoreCase(station.getStationCode())) {
                heustonTrains = irishRailService.getTrainsByStation(station.getStationCode(), true);
            }
            List<TrainInfo> changedHeuston = heustonTrains.stream()
                    .filter(t -> hasChanged(t, ServiceScope.HEUSTON))
                    .collect(Collectors.toList());
            if (!changedHeuston.isEmpty()) {
                delayTrackingService.saveAll(changedHeuston, ServiceScope.HEUSTON);
                totalSaved += changedHeuston.size();
            }
        }
        if (totalSaved > 0) {
            log.info("Collect: {} snapshots saved across {} station checks ({} Connolly, {} Heuston)",
                    totalSaved, stationChecks, connollyStations.size(), heustonStations.size());
            analyticsAggregateService.refreshDate(LocalDateTime.now().toLocalDate());
        }
        snapshotEventService.broadcast();
    }

    // ── cleanup: every day at 03:00 ──────────────────────────────────────────

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int snapshots = snapshotRepository.deleteByCapturedAtBefore(cutoff);
        int aggregates = analyticsAggregateService.deleteBefore(cutoff.toLocalDate());
        int trips     = tripRepository.deleteOrphans();
        lastSeen.clear();
        log.info("Cleanup: {} snapshots, {} aggregate rows and {} orphan trips deleted (older than {}), state map cleared",
                snapshots, aggregates, trips, cutoff.toLocalDate());
    }

    // ── DART hours check (06:00–00:30, spans midnight) ───────────────────────

    private boolean isDartHours() {
        LocalTime t = LocalTime.now();
        // Inactive window: 00:31–05:59
        return t.isAfter(LocalTime.of(5, 59, 59)) || t.isBefore(LocalTime.of(0, 31));
    }

    // ── state-change filter ───────────────────────────────────────────────────

    private boolean hasChanged(TrainInfo t, String scope) {
        String key = scope + "|"
                   + t.getTrainCode()  + "|"
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
