package com.irishrail.service;

import com.irishrail.model.Station;
import com.irishrail.model.TrainInfo;
import com.irishrail.model.ServiceScope;
import com.irishrail.repository.TripRepository;
import com.irishrail.repository.TripStationSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final ThreadPoolTaskExecutor        stationFetchExecutor;
    private final TaskScheduler                 taskScheduler;
    private final TransactionTemplate           transactions;

    @Value("${irishrail.retention.days:30}")
    private int retentionDays;

    /**
     * Aggregates cost ~75× less per day of history than raw snapshots (~440 kB against ~33 MB), so
     * they are kept far longer — or forever, which is what a value of 0 or less means.
     */
    @Value("${irishrail.retention.aggregate-days:0}")
    private int aggregateRetentionDays;

    @Value("${irishrail.collector.cycle-budget-seconds:120}")
    private long cycleBudgetSeconds;

    @Value("${irishrail.retention.startup-delay-ms:45000}")
    private long retentionStartupDelayMs;

    /** One departure as the change detector sees it → the last lateMinutes we stored for it. */
    private record SeenKey(String scope, String trainCode, String stationCode, String trainDate, String schDepart) {}

    private final ConcurrentHashMap<SeenKey, Integer> lastSeen = new ConcurrentHashMap<>();

    public TrainDelayScheduler(IrishRailService irishRailService,
                               DelayTrackingService delayTrackingService,
                               TripStationSnapshotRepository snapshotRepository,
                               TripRepository tripRepository,
                               SnapshotEventService snapshotEventService,
                               AnalyticsAggregateService analyticsAggregateService,
                               @Qualifier("stationFetchExecutor") ThreadPoolTaskExecutor stationFetchExecutor,
                               TaskScheduler taskScheduler,
                               PlatformTransactionManager transactionManager) {
        this.irishRailService     = irishRailService;
        this.delayTrackingService = delayTrackingService;
        this.snapshotRepository   = snapshotRepository;
        this.tripRepository       = tripRepository;
        this.snapshotEventService = snapshotEventService;
        this.analyticsAggregateService = analyticsAggregateService;
        this.stationFetchExecutor = stationFetchExecutor;
        this.taskScheduler        = taskScheduler;
        this.transactions         = new TransactionTemplate(transactionManager);
    }

    private record FetchJob(Station station, String scope, boolean heustonOnly) {}
    private record FetchResult(String scope, List<TrainInfo> trains) {}

    // ── collector: during DART operating hours (06:00–00:30) ──────────────────

    /**
     * {@code fixedDelay} rather than {@code fixedRate}: with ~130 stations to poll, a cycle can
     * outlast its own interval, and fixedRate would then queue cycles back to back forever.
     */
    @Scheduled(fixedDelayString = "${irishrail.collector.interval-ms:30000}")
    public void collectAllStations() {
        if (!isDartHours()) return;

        List<Station> connollyStations = irishRailService.getConnollyCollectionStations();
        List<Station> heustonStations = irishRailService.getHeustonCollectionStations();
        if (connollyStations.isEmpty() && heustonStations.isEmpty()) {
            log.warn("Scheduled collect: no stations returned from API");
            return;
        }

        List<FetchJob> jobs = new ArrayList<>(connollyStations.size() + heustonStations.size());
        for (Station station : connollyStations) {
            jobs.add(new FetchJob(station, ServiceScope.CONNOLLY, false));
        }
        for (Station station : heustonStations) {
            // At Heuston itself every train counts; elsewhere only Heuston-bound services do.
            boolean heustonOnly = !"HSTON".equalsIgnoreCase(station.getStationCode());
            jobs.add(new FetchJob(station, ServiceScope.HEUSTON, heustonOnly));
        }

        long startedAtNanos = System.nanoTime();
        List<FetchResult> results = fetchAllInParallel(jobs);
        long fetchedAtNanos = System.nanoTime();

        // Persist on this thread: one transaction boundary per scope, and the change-detection map
        // stays deterministic instead of racing across fetch threads.
        Map<String, List<TrainInfo>> changedByScope = new LinkedHashMap<>();
        for (FetchResult result : results) {
            for (TrainInfo train : result.trains()) {
                if (hasChanged(train, result.scope())) {
                    changedByScope.computeIfAbsent(result.scope(), k -> new ArrayList<>()).add(train);
                }
            }
        }

        int totalSaved = 0;
        for (Map.Entry<String, List<TrainInfo>> entry : changedByScope.entrySet()) {
            DelayTrackingService.SavedBatch batch = delayTrackingService.saveAll(entry.getValue(), entry.getKey());
            totalSaved += entry.getValue().size();
            // The roll-up runs on its own schedule and only touches what this batch changed, so
            // its cost no longer sits inside the collection cycle or scales with the day so far.
            analyticsAggregateService.markDirty(batch.capturedAt().toLocalDate(), batch.tripIds(), batch.capturedAt());
        }

        long doneNanos = System.nanoTime();
        if (totalSaved > 0) {
            log.info("Collect: {} snapshots saved across {} station checks in {} ms (fetch {} ms, persist {} ms; {} Connolly, {} Heuston)",
                    totalSaved, jobs.size(),
                    Duration.ofNanos(doneNanos - startedAtNanos).toMillis(),
                    Duration.ofNanos(fetchedAtNanos - startedAtNanos).toMillis(),
                    Duration.ofNanos(doneNanos - fetchedAtNanos).toMillis(),
                    connollyStations.size(), heustonStations.size());
        } else {
            log.debug("Collect: no changes across {} station checks in {} ms", jobs.size(),
                    Duration.ofNanos(doneNanos - startedAtNanos).toMillis());
        }
        snapshotEventService.broadcast();
    }

    private List<FetchResult> fetchAllInParallel(List<FetchJob> jobs) {
        List<CompletableFuture<FetchResult>> futures = jobs.stream()
                .map(job -> CompletableFuture.supplyAsync(() -> fetch(job), stationFetchExecutor))
                .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(cycleBudgetSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            long pending = futures.stream().filter(f -> !f.isDone()).count();
            log.warn("Collect: cycle budget of {}s exceeded, proceeding without {} pending station(s)",
                    cycleBudgetSeconds, pending);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.error("Collect: unexpected failure while fetching stations: {}", e.getMessage());
        }

        List<FetchResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<FetchResult> future : futures) {
            if (!future.isDone() || future.isCompletedExceptionally()) continue;
            FetchResult result = future.getNow(null);
            if (result != null && !result.trains().isEmpty()) results.add(result);
        }
        return results;
    }

    private FetchResult fetch(FetchJob job) {
        // Always live: this is the one place that refreshes the departure-board cache that
        // /api/trains and the journey planner read from.
        List<TrainInfo> trains = irishRailService.fetchTrainsByStation(
                job.station().getStationCode(), !ServiceScope.CONNOLLY.equals(job.scope()));
        if (job.heustonOnly()) {
            trains = trains.stream().filter(IrishRailService::isHeustonRelated).collect(Collectors.toList());
        }
        return new FetchResult(job.scope(), trains);
    }

    // ── cleanup: every day at 03:00, and once after each start ───────────────

    /**
     * The 03:00 cron only fires if the process is alive at that instant. A deploy or an outage
     * spanning it skips a day, and a development instance that is never up at 03:00 never trims at
     * all — which is how a local database ended up holding snapshots six weeks past the cutoff.
     * Running once after boot makes the policy hold regardless of uptime pattern. Delayed so it
     * does not compete with the startup backfill and the first collection cycles.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scheduleStartupCleanup() {
        taskScheduler.schedule(this::cleanup, Instant.now().plusMillis(retentionStartupDelayMs));
    }

    /**
     * Two independent cutoffs. Applying one cutoff to both tables meant the cheap long-term
     * aggregate was discarded in the same sweep as the expensive raw data it summarises.
     *
     * <p>Raw snapshots are trimmed on a whole-day boundary so no partially-deleted day is ever left
     * behind — the aggregate for any remaining day is therefore always complete, which is what lets
     * the startup backfill safely re-derive it.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        // TransactionTemplate rather than @Transactional: the startup path above calls this method
        // on the same bean, and self-invocation bypasses the Spring proxy.
        try {
            transactions.executeWithoutResult(status -> cleanupInTransaction());
        } catch (RuntimeException e) {
            log.error("Cleanup failed; retention will be retried at the next scheduled run: {}", e.getMessage());
        }
    }

    private void cleanupInTransaction() {
        LocalDate rawCutoff = LocalDate.now().minusDays(retentionDays);
        int snapshots = snapshotRepository.deleteByCapturedAtBefore(rawCutoff.atStartOfDay());
        int trips     = tripRepository.deleteOrphans();

        int aggregates = 0;
        String aggregateNote = "aggregates kept indefinitely";
        if (aggregateRetentionDays > 0) {
            LocalDate aggregateCutoff = LocalDate.now().minusDays(aggregateRetentionDays);
            aggregates = analyticsAggregateService.deleteBefore(aggregateCutoff);
            aggregateNote = "aggregates older than " + aggregateCutoff;
        }

        lastSeen.clear();
        delayTrackingService.clearTripCache();
        log.info("Cleanup: {} snapshots older than {} and {} orphan trips deleted; "
                        + "{} aggregate rows deleted ({}); state map cleared",
                snapshots, rawCutoff, trips, aggregates, aggregateNote);
    }

    // ── DART hours check (06:00–00:30, spans midnight) ───────────────────────

    private boolean isDartHours() {
        LocalTime t = LocalTime.now();
        // Inactive window: 00:31–05:59
        return t.isAfter(LocalTime.of(5, 59, 59)) || t.isBefore(LocalTime.of(0, 31));
    }

    // ── state-change filter ───────────────────────────────────────────────────

    private boolean hasChanged(TrainInfo t, String scope) {
        SeenKey key = new SeenKey(scope, t.getTrainCode(), t.getStationCode(), t.getTrainDate(), t.getSchDepart());
        Integer last    = lastSeen.get(key);
        int     current = t.getLate();
        if (last == null || !last.equals(current)) {
            lastSeen.put(key, current);
            return true;
        }
        return false;
    }
}
