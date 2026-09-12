package com.irishrail.controller;

import com.irishrail.model.AnalyticsView;
import com.irishrail.model.JourneyOptions;
import com.irishrail.model.Station;
import com.irishrail.model.StationPoint;
import com.irishrail.model.TrainDelaySummary;
import com.irishrail.model.TrainHistory;
import com.irishrail.model.TrainInfo;
import com.irishrail.model.TrainPositionsView;
import com.irishrail.model.TrainRoute;
import com.irishrail.model.TripStationSnapshot;
import com.irishrail.service.AnalyticsQueryService;
import com.irishrail.service.DelayTrackingService;
import com.irishrail.service.IrishRailService;
import com.irishrail.service.SnapshotEventService;
import com.irishrail.service.StationDirectory;
import com.irishrail.service.TrainPositionService;
import com.irishrail.service.TrainRouteService;
import com.irishrail.web.StationCodes;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The JSON and event-stream endpoints, split out of the former combined controller. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final IrishRailService irishRailService;
    private final DelayTrackingService delayTrackingService;
    private final AnalyticsQueryService analytics;
    private final SnapshotEventService snapshotEventService;
    private final TrainPositionService trainPositionService;
    private final TrainRouteService trainRouteService;
    private final StationDirectory stationDirectory;
    private final StationCodes stationCodes;

    public ApiController(IrishRailService irishRailService,
                         DelayTrackingService delayTrackingService,
                         AnalyticsQueryService analytics,
                         SnapshotEventService snapshotEventService,
                         TrainPositionService trainPositionService,
                         TrainRouteService trainRouteService,
                         StationDirectory stationDirectory,
                         StationCodes stationCodes) {
        this.irishRailService = irishRailService;
        this.delayTrackingService = delayTrackingService;
        this.analytics = analytics;
        this.snapshotEventService = snapshotEventService;
        this.trainPositionService = trainPositionService;
        this.trainRouteService = trainRouteService;
        this.stationDirectory = stationDirectory;
        this.stationCodes = stationCodes;
    }

    // ── live data ─────────────────────────────────────────────────────────────

    @GetMapping("/trains")
    public ResponseEntity<List<TrainInfo>> getTrainsJson(
            @RequestParam(defaultValue = PageController.DEFAULT_STATION) String stationCode) {
        // Validated before it can reach a cache key or the upstream URL.
        String code = stationCodes.require("stationCode", stationCode);
        // Served from the collector's board cache; the browser may reuse it for a few seconds too.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)))
                .body(irishRailService.getTrainsByStation(code));
    }

    @GetMapping("/stations")
    public ResponseEntity<List<Station>> getStations() {
        return ResponseEntity.ok(irishRailService.getTrackedStations());
    }

    /** Stations with coordinates, for the map's station layer. */
    @GetMapping("/stations/all")
    public ResponseEntity<List<StationPoint>> getAllStations() {
        List<StationPoint> stations = stationDirectory.all().stream()
                .filter(s -> s.getStationLatitude() != 0d && s.getStationLongitude() != 0d)
                .map(StationPoint::of)
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(stations);
    }

    @GetMapping("/journey-options")
    public ResponseEntity<JourneyOptions> getJourneyOptions(
            @RequestParam String fromStationCode,
            @RequestParam String toStationCode) {

        String fromCode = stationCodes.require("fromStationCode", fromStationCode);
        String toCode = stationCodes.require("toStationCode", toStationCode);

        List<TrainInfo> departureBoard = irishRailService.getTrainsByStation(fromCode, true);
        List<TrainInfo> destinationBoard = irishRailService.getTrainsByStation(toCode, true);

        Map<String, TrainInfo> destinationTrains = destinationBoard.stream()
                .filter(t -> !trainJourneyKey(t).isBlank())
                .collect(Collectors.toMap(ApiController::trainJourneyKey, t -> t, (a, b) -> a));

        List<JourneyOptions.Option> options = departureBoard.stream()
                .filter(t -> !trainJourneyKey(t).isBlank())
                .filter(t -> destinationTrains.containsKey(trainJourneyKey(t)))
                .filter(t -> destinationTrains.get(trainJourneyKey(t)).getDueIn() >= t.getDueIn())
                .sorted(Comparator.comparingInt(TrainInfo::getDueIn))
                .map(t -> JourneyOptions.Option.of(t, destinationTrains.get(trainJourneyKey(t))))
                .toList();

        return ResponseEntity.ok(new JourneyOptions(
                fromCode, toCode, LocalTime.now().format(CLOCK_FMT), options.size(), options));
    }

    @GetMapping("/train-positions")
    public ResponseEntity<TrainPositionsView> getTrainPositions() {
        // The snapshot is already refreshed on a timer server-side; letting the browser reuse it
        // for a couple of seconds absorbs double-fires without ever showing properly stale data.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(3)))
                .body(TrainPositionsView.of(trainPositionService.getSnapshot()));
    }

    /** Stop-by-stop path of one train, with the portion already travelled marked. */
    @GetMapping("/trains/{trainCode}/route")
    public ResponseEntity<TrainRoute> getTrainRoute(
            @PathVariable String trainCode,
            @RequestParam(required = false) String trainDate) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)))
                .body(trainRouteService.getRoute(trainCode, trainDate));
    }

    /** Recorded delay history for one train code — the map's link into the analytics database. */
    @GetMapping("/trains/{trainCode}/history")
    public ResponseEntity<TrainHistory> getTrainHistory(
            @PathVariable String trainCode,
            @RequestParam(defaultValue = "25") int limit) {
        int rows = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(delayTrackingService.getTrainHistory(trainCode, rows));
    }

    // ── analytics ─────────────────────────────────────────────────────────────

    @GetMapping("/analytics/trains")
    public ResponseEntity<List<TrainDelaySummary>> getTopTrains() {
        return ResponseEntity.ok(delayTrackingService.getTopDelayedTrains(10));
    }

    @GetMapping("/analytics/recent")
    public ResponseEntity<List<TripStationSnapshot>> getRecentSnapshots() {
        return ResponseEntity.ok(delayTrackingService.getRecentDelays());
    }

    @GetMapping("/analytics/daily")
    public ResponseEntity<Map<String, Long>> getDailyDelays() {
        return ResponseEntity.ok(delayTrackingService.getDailyDelays(null, null));
    }

    @GetMapping("/analytics/summary")
    public ResponseEntity<AnalyticsView> getAnalyticsSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analytics.get(
                AnalyticsQueryService.Request.overview(from, to, null, "", false, true)));
    }

    @GetMapping("/analytics/overview")
    public ResponseEntity<AnalyticsView> getAnalyticsOverview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String stationCode,
            @RequestParam(required = false, defaultValue = "") String period) {

        AnalyticsView view = analytics.get(
                AnalyticsQueryService.Request.overview(from, to, stationCode, period, false, true));

        // Server-side the payload is reused for 10 s; letting the browser keep it for a few seconds
        // absorbs a reload or a scope flick without ever showing a stale aggregate.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)))
                .body(view);
    }

    // ── event stream ──────────────────────────────────────────────────────────

    @GetMapping("/events")
    public ResponseEntity<SseEmitter> getEvents() {
        SseEmitter emitter = snapshotEventService.subscribe();
        // Null means the subscriber cap was reached. Saying so is better than accepting a stream
        // this instance cannot afford to serve: the client's own backoff then applies.
        return emitter == null
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
                : ResponseEntity.ok(emitter);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String trainJourneyKey(TrainInfo train) {
        String trainCode = normalizeText(train.getTrainCode());
        String trainDate = normalizeText(train.getTrainDate());
        if (trainCode.isBlank() || trainDate.isBlank()) return "";
        return trainCode + "|" + trainDate;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
