package com.irishrail.controller;

import com.irishrail.model.*;
import com.irishrail.service.DelayTrackingService;
import com.irishrail.service.IrishRailService;
import com.irishrail.service.SnapshotEventService;
import com.irishrail.service.StationDirectory;
import com.irishrail.service.TrainPositionService;
import com.irishrail.service.TrainRouteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Controller
public class TrainController {

    private static final String DEFAULT_STATION = "CNLLY";
    private static final String HEUSTON_STATION = "HSTON";

    private final IrishRailService irishRailService;
    private final DelayTrackingService delayTrackingService;
    private final SnapshotEventService snapshotEventService;
    private final TrainPositionService trainPositionService;
    private final TrainRouteService trainRouteService;
    private final StationDirectory stationDirectory;
    private final ConcurrentHashMap<String, CachedPayload> analyticsOverviewCache = new ConcurrentHashMap<>();

    @Value("${irishrail.analytics.overview-cache-ms:10000}")
    private long analyticsOverviewCacheMs;

    @Value("${irishrail.map.tile-url}")
    private String mapTileUrl;

    @Value("${irishrail.map.tile-attribution}")
    private String mapTileAttribution;

    @Value("${irishrail.map.tile-filter:none}")
    private String mapTileFilter;

    @Value("${irishrail.map.tile-max-zoom:18}")
    private int mapTileMaxZoom;

    @Value("${irishrail.map.rail-overlay-url:}")
    private String railOverlayUrl;

    @Value("${irishrail.map.rail-overlay-attribution:}")
    private String railOverlayAttribution;

    @Value("${irishrail.map.refresh-ms:10000}")
    private long mapRefreshMs;

    public TrainController(IrishRailService irishRailService,
                           DelayTrackingService delayTrackingService,
                           SnapshotEventService snapshotEventService,
                           TrainPositionService trainPositionService,
                           TrainRouteService trainRouteService,
                           StationDirectory stationDirectory) {
        this.irishRailService     = irishRailService;
        this.delayTrackingService = delayTrackingService;
        this.snapshotEventService = snapshotEventService;
        this.trainPositionService = trainPositionService;
        this.trainRouteService    = trainRouteService;
        this.stationDirectory     = stationDirectory;
    }

    // ── live board + analytics ────────────────────────────────────────────────

    @GetMapping("/get")
    public String getTrains(
            @RequestParam(defaultValue = DEFAULT_STATION) String stationCode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Model model) {

        LocalDate filterFrom = parseDate(from);
        LocalDate filterTo   = parseDate(to);

        // Live board
        List<Station>   stations = irishRailService.getTrackedStations()
                .stream()
                .sorted(Comparator.comparing(Station::getStationDesc))
                .collect(Collectors.toList());
        List<TrainInfo> trains   = irishRailService.getTrainsByStation(stationCode);

        Station selected   = stations.stream()
                .filter(s -> stationCode.equalsIgnoreCase(s.getStationCode()))
                .findFirst().orElse(null);
        String stationName = selected != null ? selected.getStationDesc() : stationCode;
        String updatedAt   = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        long liveDelayed   = trains.stream().filter(t -> t.getLate() >= DelayCategory.delayedThreshold()).count();

        model.addAttribute("stations",     stations);
        model.addAttribute("selectedCode", stationCode.toUpperCase());
        model.addAttribute("stationName",  stationName);
        model.addAttribute("trains",       trains);
        model.addAttribute("updatedAt",    updatedAt);
        model.addAttribute("totalTrains",  trains.size());
        model.addAttribute("delayedCount", liveDelayed);
        model.addAttribute("onTimeCount",  trains.size() - liveDelayed);

        // Analytics (date-filtered)
        DashboardSummary       dashboard    = delayTrackingService.getDashboardSummary(filterFrom, filterTo);
        List<StationStats>     stationRank  = delayTrackingService.getStationRanking(filterFrom, filterTo);
        List<HourlyStats>      hourly       = delayTrackingService.getHourlyStats(filterFrom, filterTo);
        List<TripDelaySummary> top10        = delayTrackingService.getTop10LargestDelays(filterFrom, filterTo);
        List<DestinationStats> destinations = delayTrackingService.getTopDestinationsByDelay(filterFrom, filterTo);
        Map<String, Long>      categories   = delayTrackingService.getDelayCategories(filterFrom, filterTo);

        long maxCatCount = categories.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        long catOnTime   = dashboard.getOnTimeTrips();
        long catSmall    = categories.getOrDefault(DelayCategory.SMALL_DELAY.getDisplayLabel(),   0L);
        long catMedium   = categories.getOrDefault(DelayCategory.MEDIUM_DELAY.getDisplayLabel(),  0L);
        long catBig      = categories.getOrDefault(DelayCategory.BIG_DELAY.getDisplayLabel(),     0L);
        long catExtreme  = categories.getOrDefault(DelayCategory.EXTREME_DELAY.getDisplayLabel(), 0L);

        model.addAttribute("dashboard",    dashboard);
        model.addAttribute("stationRank",  stationRank);
        model.addAttribute("hourly",       hourly);
        model.addAttribute("top10Delays",  top10);
        model.addAttribute("destinations", destinations);
        model.addAttribute("categories",   categories);
        model.addAttribute("maxCatCount",  maxCatCount);
        model.addAttribute("catOnTime",    catOnTime);
        model.addAttribute("catSmall",     catSmall);
        model.addAttribute("catMedium",    catMedium);
        model.addAttribute("catBig",       catBig);
        model.addAttribute("catExtreme",   catExtreme);

        model.addAttribute("hourlyLabels",
                hourly.stream().map(HourlyStats::getHourLabel).collect(Collectors.toList()));
        model.addAttribute("hourlyDelayPcts",
                hourly.stream().map(HourlyStats::getDelayProbability).collect(Collectors.toList()));
        model.addAttribute("hourlyAvgDelays",
                hourly.stream().map(HourlyStats::getAvgDelay).collect(Collectors.toList()));
        model.addAttribute("destLabels",
                destinations.stream().map(DestinationStats::getDestination).collect(Collectors.toList()));
        model.addAttribute("destAvgDelays",
                destinations.stream().map(DestinationStats::getAvgDelay).collect(Collectors.toList()));
        model.addAttribute("destDelayCounts",
                destinations.stream().map(DestinationStats::getDelayCount).collect(Collectors.toList()));

        model.addAttribute("delayCategoryData", buildCategoryData());
        model.addAttribute("categoryColors",    buildCategoryColors());
        model.addAttribute("delayedThreshold",  DelayCategory.delayedThreshold());

        // Filter metadata
        addFilterMeta(model, filterFrom, filterTo, stationCode);

        return "trains";
    }

    // ── overview ──────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public String overview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String stationCode,
            @RequestParam(required = false, defaultValue = "") String period,
            Model model) {

        boolean   allTime         = "all".equalsIgnoreCase(period);
        boolean   hasExplicit     = (from != null && !from.isBlank()) || (to != null && !to.isBlank());
        boolean   isDefaultFilter = !allTime && !hasExplicit;
        LocalDate today           = LocalDate.now();
        LocalDate filterFrom      = allTime ? null : (hasExplicit ? parseDate(from) : today);
        LocalDate filterTo        = allTime ? null : (hasExplicit ? parseDate(to)   : today);
        boolean   hasStation      = ServiceScope.fromOverviewCode(stationCode) != null;

        List<Station> stations = irishRailService.getTrackedStations()
                .stream()
                .sorted(Comparator.comparing(Station::getStationDesc))
                .collect(Collectors.toList());

        DashboardSummary       dashboard;
        List<StationStats>     allStations  = Collections.emptyList();
        List<HourlyStats>      hourly;
        List<DestinationStats> destinations;
        List<TripDelaySummary> top10;
        List<RouteStats>       routeRanking;
        Map<String, Long>      categories;

        if (hasStation) {
            dashboard    = delayTrackingService.getDashboardSummaryForStation(filterFrom, filterTo, stationCode);
            allStations  = delayTrackingService.getAllStationRankingForStation(filterFrom, filterTo, stationCode);
            hourly       = delayTrackingService.getHourlyStatsForStation(filterFrom, filterTo, stationCode);
            destinations = delayTrackingService.getTopDestinationsByDelayForStation(filterFrom, filterTo, stationCode);
            top10        = delayTrackingService.getTop10LargestDelaysForStation(filterFrom, filterTo, stationCode);
            routeRanking = delayTrackingService.getTopRoutesByDelayForStation(filterFrom, filterTo, stationCode);
            categories   = delayTrackingService.getDelayCategoriesForStation(filterFrom, filterTo, stationCode);
        } else {
            dashboard    = delayTrackingService.getDashboardSummary(filterFrom, filterTo);
            allStations  = delayTrackingService.getAllStationRanking(filterFrom, filterTo);
            hourly       = delayTrackingService.getHourlyStats(filterFrom, filterTo);
            destinations = delayTrackingService.getTopDestinationsByDelay(filterFrom, filterTo);
            top10        = delayTrackingService.getTop10LargestDelays(filterFrom, filterTo);
            routeRanking = delayTrackingService.getTopRoutesByDelay(filterFrom, filterTo);
            categories   = delayTrackingService.getDelayCategories(filterFrom, filterTo);
        }

        String selectedStationName = null;
        if (hasStation) {
            selectedStationName = stations.stream()
                    .filter(s -> stationCode.equalsIgnoreCase(s.getStationCode()))
                    .map(Station::getStationDesc)
                    .findFirst().orElse(stationCode.toUpperCase());
        }

        long catOnTime  = dashboard.getOnTimeTrips();
        long catSmall   = categories.getOrDefault(DelayCategory.SMALL_DELAY.getDisplayLabel(),   0L);
        long catMedium  = categories.getOrDefault(DelayCategory.MEDIUM_DELAY.getDisplayLabel(),  0L);
        long catBig     = categories.getOrDefault(DelayCategory.BIG_DELAY.getDisplayLabel(),     0L);
        long catExtreme = categories.getOrDefault(DelayCategory.EXTREME_DELAY.getDisplayLabel(), 0L);

        List<RecentDelayEntry> recentDelays = hasStation
                ? delayTrackingService.getRecentDelayedTripsForStation(stationCode)
                : delayTrackingService.getRecentDelayedTrips();

        model.addAttribute("dashboard",            dashboard);
        model.addAttribute("allStations",          allStations);
        model.addAttribute("hourly",               hourly);
        model.addAttribute("destinations",         destinations);
        model.addAttribute("top10Delays",          top10);
        model.addAttribute("routeRanking",         routeRanking);
        model.addAttribute("recentDelays",         recentDelays);
        model.addAttribute("stations",             stations);
        model.addAttribute("selectedCode",         hasStation ? stationCode.toUpperCase() : "OVERVIEW");
        model.addAttribute("hasStationFilter",     hasStation);
        model.addAttribute("selectedStationName",  selectedStationName);
        model.addAttribute("connollyCode",         DEFAULT_STATION);
        model.addAttribute("heustonCode",          HEUSTON_STATION);
        model.addAttribute("catOnTime",            catOnTime);
        model.addAttribute("catSmall",             catSmall);
        model.addAttribute("catMedium",            catMedium);
        model.addAttribute("catBig",               catBig);
        model.addAttribute("catExtreme",           catExtreme);

        model.addAttribute("hourlyLabels",
                hourly.stream().map(HourlyStats::getHourLabel).collect(Collectors.toList()));
        model.addAttribute("hourlyDelayPcts",
                hourly.stream().map(HourlyStats::getDelayProbability).collect(Collectors.toList()));
        model.addAttribute("hourlyAvgDelays",
                hourly.stream().map(HourlyStats::getAvgDelay).collect(Collectors.toList()));
        model.addAttribute("destLabels",
                destinations.stream().map(DestinationStats::getDestination).collect(Collectors.toList()));
        model.addAttribute("destAvgDelays",
                destinations.stream().map(DestinationStats::getAvgDelay).collect(Collectors.toList()));
        model.addAttribute("destDelayCounts",
                destinations.stream().map(DestinationStats::getDelayCount).collect(Collectors.toList()));

        model.addAttribute("delayCategoryData", buildCategoryData());
        model.addAttribute("categoryColors",    buildCategoryColors());
        model.addAttribute("delayedThreshold",  DelayCategory.delayedThreshold());

        addFilterMeta(model, filterFrom, filterTo, hasStation ? stationCode : "OVERVIEW");
        model.addAttribute("hasFilter",       hasExplicit);
        model.addAttribute("isDefaultFilter", isDefaultFilter);

        return "overview";
    }

    @GetMapping("/map")
    public String trainMap(Model model) {
        List<Station> stations = irishRailService.getTrackedStations()
                .stream()
                .sorted(Comparator.comparing(Station::getStationDesc))
                .collect(Collectors.toList());
        model.addAttribute("stations", stations);
        model.addAttribute("selectedCode", "MAP");
        model.addAttribute("mapTileUrl", mapTileUrl);
        model.addAttribute("mapTileAttribution", mapTileAttribution);
        model.addAttribute("mapTileFilter", mapTileFilter);
        model.addAttribute("mapTileMaxZoom", mapTileMaxZoom);
        model.addAttribute("railOverlayUrl", railOverlayUrl);
        model.addAttribute("railOverlayAttribution", railOverlayAttribution);
        model.addAttribute("mapRefreshMs", mapRefreshMs);
        model.addAttribute("delayedThreshold", DelayCategory.delayedThreshold());
        model.addAttribute("delayCategoryData", buildCategoryData());
        return "map";
    }

    @GetMapping("/journey")
    public String journeyPlanner(Model model) {
        List<Station> stations = irishRailService.getJourneyPlannerStations()
                .stream()
                .sorted(Comparator.comparing(Station::getStationDesc))
                .collect(Collectors.toList());
        model.addAttribute("stations", stations);
        model.addAttribute("selectedCode", "JOURNEY");
        return "journey";
    }

    // ── JSON APIs ─────────────────────────────────────────────────────────────

    @GetMapping("/api/trains")
    @ResponseBody
    public ResponseEntity<List<TrainInfo>> getTrainsJson(
            @RequestParam(defaultValue = DEFAULT_STATION) String stationCode) {
        // Served from the collector's board cache; the browser may reuse it for a few seconds too.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)))
                .body(irishRailService.getTrainsByStation(stationCode));
    }

    @GetMapping("/api/stations")
    @ResponseBody
    public ResponseEntity<List<Station>> getStations() {
        return ResponseEntity.ok(irishRailService.getTrackedStations());
    }

    @GetMapping("/api/journey-options")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getJourneyOptions(
            @RequestParam String fromStationCode,
            @RequestParam String toStationCode) {

        String fromCode = normalizeStationCode(fromStationCode);
        String toCode = normalizeStationCode(toStationCode);
        List<TrainInfo> departureBoard = irishRailService.getTrainsByStation(fromCode, true);
        List<TrainInfo> destinationBoard = irishRailService.getTrainsByStation(toCode, true);

        Map<String, TrainInfo> destinationTrains = destinationBoard.stream()
                .filter(t -> !trainJourneyKey(t).isBlank())
                .collect(Collectors.toMap(this::trainJourneyKey, t -> t, (a, b) -> a));

        List<Map<String, Object>> options = departureBoard.stream()
                .filter(t -> !trainJourneyKey(t).isBlank())
                .filter(t -> destinationTrains.containsKey(trainJourneyKey(t)))
                .filter(t -> destinationTrains.get(trainJourneyKey(t)).getDueIn() >= t.getDueIn())
                .sorted(Comparator.comparingInt(TrainInfo::getDueIn))
                .map(t -> journeyOptionPayload(t, destinationTrains.get(trainJourneyKey(t))))
                .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromStationCode", fromCode);
        payload.put("toStationCode", toCode);
        payload.put("updatedAt", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        payload.put("count", options.size());
        payload.put("options", options);
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/api/train-positions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTrainPositions() {
        TrainPositionService.Snapshot snapshot = trainPositionService.getSnapshot();
        List<LiveTrain> trains = snapshot.trains();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capturedAt", snapshot.capturedAt() == null ? null : snapshot.capturedAt().toString());
        payload.put("stale", snapshot.failing());
        payload.put("count", trains.size());
        payload.put("runningCount", trains.stream().filter(LiveTrain::running).count());
        payload.put("delayedCount", trains.stream()
                .filter(t -> t.lateMinutes() != null && t.lateMinutes() >= DelayCategory.delayedThreshold())
                .count());
        payload.put("positions", trains);

        // The snapshot is already refreshed on a timer server-side; letting the browser reuse it
        // for a couple of seconds absorbs double-fires without ever showing properly stale data.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(3)))
                .body(payload);
    }

    /** Stations with coordinates, for the map's station layer. */
    @GetMapping("/api/stations/all")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getAllStations() {
        List<Map<String, Object>> stations = stationDirectory.all().stream()
                .filter(s -> s.getStationLatitude() != 0d && s.getStationLongitude() != 0d)
                .map(s -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", s.getStationCode());
                    item.put("name", s.getStationDesc());
                    item.put("latitude", s.getStationLatitude());
                    item.put("longitude", s.getStationLongitude());
                    return item;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(stations);
    }

    /** Stop-by-stop path of one train, with the portion already travelled marked. */
    @GetMapping("/api/trains/{trainCode}/route")
    @ResponseBody
    public ResponseEntity<TrainRoute> getTrainRoute(
            @PathVariable String trainCode,
            @RequestParam(required = false) String trainDate) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)))
                .body(trainRouteService.getRoute(trainCode, trainDate));
    }

    /** Recorded delay history for one train code — the map's link into the analytics database. */
    @GetMapping("/api/trains/{trainCode}/history")
    @ResponseBody
    public ResponseEntity<TrainHistory> getTrainHistory(
            @PathVariable String trainCode,
            @RequestParam(defaultValue = "25") int limit) {
        int rows = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(delayTrackingService.getTrainHistory(trainCode, rows));
    }

    @GetMapping("/api/analytics/trains")
    @ResponseBody
    public ResponseEntity<List<TrainDelaySummary>> getTopTrains() {
        return ResponseEntity.ok(delayTrackingService.getTopDelayedTrains(10));
    }

    @GetMapping("/api/analytics/recent")
    @ResponseBody
    public ResponseEntity<List<TripStationSnapshot>> getRecentSnapshots() {
        return ResponseEntity.ok(delayTrackingService.getRecentDelays());
    }

    @GetMapping("/api/analytics/daily")
    @ResponseBody
    public ResponseEntity<Map<String, Long>> getDailyDelays() {
        return ResponseEntity.ok(delayTrackingService.getDailyDelays(null, null));
    }

    @GetMapping("/api/analytics/summary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAnalyticsSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(buildAnalyticsPayload(from, to, null, ""));
    }

    @GetMapping("/api/analytics/overview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAnalyticsOverview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String stationCode,
            @RequestParam(required = false, defaultValue = "") String period) {
        String cacheKey = analyticsOverviewCacheKey(from, to, stationCode, period);
        long now = System.currentTimeMillis();
        CachedPayload cached = analyticsOverviewCache.get(cacheKey);
        // Server-side the payload is reused for 10 s; letting the browser keep it for a few seconds
        // absorbs a reload or a scope flick without ever showing a stale aggregate.
        CacheControl browserCache = CacheControl.maxAge(Duration.ofSeconds(5));
        if (cached != null && now - cached.createdAtMs() <= analyticsOverviewCacheMs) {
            return ResponseEntity.ok().cacheControl(browserCache).body(cached.payload());
        }

        Map<String, Object> payload = buildAnalyticsPayload(from, to, stationCode, period);
        analyticsOverviewCache.put(cacheKey, new CachedPayload(payload, now));
        return ResponseEntity.ok().cacheControl(browserCache).body(payload);
    }

    @GetMapping("/api/events")
    @ResponseBody
    public SseEmitter getEvents() {
        return snapshotEventService.subscribe();
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/overview";
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private record CachedPayload(Map<String, Object> payload, long createdAtMs) {}

    private String normalizeStationCode(String stationCode) {
        return stationCode == null ? "" : stationCode.trim().toUpperCase();
    }

    private String trainJourneyKey(TrainInfo train) {
        String trainCode = normalizeText(train.getTrainCode());
        String trainDate = normalizeText(train.getTrainDate());
        if (trainCode.isBlank() || trainDate.isBlank()) return "";
        return trainCode + "|" + trainDate;
    }

    private Map<String, Object> journeyOptionPayload(TrainInfo from, TrainInfo to) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("trainCode", from.getTrainCode());
        option.put("trainType", from.getTrainType());
        option.put("origin", from.getOrigin());
        option.put("destination", from.getDestination());
        option.put("direction", from.getDirection());
        option.put("status", from.getStatus());
        option.put("late", from.getLate());
        option.put("dueIn", from.getDueIn());
        option.put("schDepart", from.getSchDepart());
        option.put("expDepart", from.getExpDepart());
        option.put("schArrival", to.getSchArrival());
        option.put("expArrival", to.getExpArrival());
        option.put("destinationDueIn", to.getDueIn());
        option.put("lastLocation", from.getLastLocation());
        return option;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String analyticsOverviewCacheKey(String from, String to, String stationCode, String period) {
        String scope = normalizeCachePart(stationCode);
        if ("OVERVIEW".equalsIgnoreCase(scope)) scope = "";
        return normalizeCachePart(from) + "|"
                + normalizeCachePart(to) + "|"
                + scope.toUpperCase() + "|"
                + normalizeCachePart(period).toLowerCase();
    }

    private String normalizeCachePart(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, Object> buildAnalyticsPayload(String from, String to, String stationCode, String period) {
        boolean   allTime    = "all".equalsIgnoreCase(period);
        boolean   hasExplicit = (from != null && !from.isBlank()) || (to != null && !to.isBlank());
        LocalDate today      = LocalDate.now();
        LocalDate filterFrom = allTime ? null : (hasExplicit ? parseDate(from) : today);
        LocalDate filterTo   = allTime ? null : (hasExplicit ? parseDate(to)   : today);
        boolean   hasStation = ServiceScope.fromOverviewCode(stationCode) != null;

        DashboardSummary       dashboard;
        List<StationStats>     stationRank;
        List<HourlyStats>      hourly;
        List<TripDelaySummary> top10;
        List<DestinationStats> destinations;
        List<RouteStats>       routeRanking;
        Map<String, Long>      categories;

        if (hasStation) {
            dashboard    = delayTrackingService.getDashboardSummaryForStation(filterFrom, filterTo, stationCode);
            stationRank  = delayTrackingService.getAllStationRankingForStation(filterFrom, filterTo, stationCode);
            hourly       = delayTrackingService.getHourlyStatsForStation(filterFrom, filterTo, stationCode);
            top10        = delayTrackingService.getTop10LargestDelaysForStation(filterFrom, filterTo, stationCode);
            destinations = delayTrackingService.getTopDestinationsByDelayForStation(filterFrom, filterTo, stationCode);
            routeRanking = delayTrackingService.getTopRoutesByDelayForStation(filterFrom, filterTo, stationCode);
            categories   = delayTrackingService.getDelayCategoriesForStation(filterFrom, filterTo, stationCode);
        } else {
            dashboard    = delayTrackingService.getDashboardSummary(filterFrom, filterTo);
            stationRank  = delayTrackingService.getAllStationRanking(filterFrom, filterTo);
            hourly       = delayTrackingService.getHourlyStats(filterFrom, filterTo);
            top10        = delayTrackingService.getTop10LargestDelays(filterFrom, filterTo);
            destinations = delayTrackingService.getTopDestinationsByDelay(filterFrom, filterTo);
            routeRanking = delayTrackingService.getTopRoutesByDelay(filterFrom, filterTo);
            categories   = delayTrackingService.getDelayCategories(filterFrom, filterTo);
        }

        long maxCatCount = categories.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        long catOnTime   = dashboard.getOnTimeTrips();
        long catSmall    = categories.getOrDefault(DelayCategory.SMALL_DELAY.getDisplayLabel(),   0L);
        long catMedium   = categories.getOrDefault(DelayCategory.MEDIUM_DELAY.getDisplayLabel(),  0L);
        long catBig      = categories.getOrDefault(DelayCategory.BIG_DELAY.getDisplayLabel(),     0L);
        long catExtreme  = categories.getOrDefault(DelayCategory.EXTREME_DELAY.getDisplayLabel(), 0L);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dashboard",       dashboard);
        result.put("stationRank",     stationRank);
        result.put("hourlyLabels",    hourly.stream().map(HourlyStats::getHourLabel).collect(Collectors.toList()));
        result.put("hourlyDelayPcts", hourly.stream().map(HourlyStats::getDelayProbability).collect(Collectors.toList()));
        result.put("hourlyAvgDelays", hourly.stream().map(HourlyStats::getAvgDelay).collect(Collectors.toList()));
        result.put("top10Delays",     top10);
        result.put("routeRanking",    routeRanking);
        result.put("recentDelays",    hasStation
                ? delayTrackingService.getRecentDelayedTripsForStation(stationCode)
                : delayTrackingService.getRecentDelayedTrips());
        result.put("destinations",    destinations);
        result.put("destLabels",      destinations.stream().map(DestinationStats::getDestination).collect(Collectors.toList()));
        result.put("destAvgDelays",   destinations.stream().map(DestinationStats::getAvgDelay).collect(Collectors.toList()));
        result.put("destDelayCounts", destinations.stream().map(DestinationStats::getDelayCount).collect(Collectors.toList()));
        result.put("categories",      categories);
        result.put("maxCatCount",     maxCatCount);
        result.put("catOnTime",       catOnTime);
        result.put("catSmall",        catSmall);
        result.put("catMedium",       catMedium);
        result.put("catBig",          catBig);
        result.put("catExtreme",      catExtreme);
        return result;
    }

    private List<Map<String, Object>> buildCategoryData() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DelayCategory cat : DelayCategory.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minMinutes",   cat.getMinMinutes());
            m.put("maxMinutes",   cat.getMaxMinutes() == Integer.MAX_VALUE ? -1 : cat.getMaxMinutes());
            m.put("textColor",    cat.getTextColor());
            m.put("bgColor",      cat.getBgColor());
            m.put("borderColor",  cat.getBorderColor());
            m.put("displayLabel", cat.getDisplayLabel());
            m.put("isOnTime",     cat.isOnTime());
            list.add(m);
        }
        return list;
    }

    private Map<String, String> buildCategoryColors() {
        Map<String, String> colors = new LinkedHashMap<>();
        for (DelayCategory cat : DelayCategory.values()) {
            colors.put(cat.getDisplayLabel(), cat.getTextColor());
        }
        return colors;
    }

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private void addFilterMeta(Model model, LocalDate from, LocalDate to, String stationCode) {
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        model.addAttribute("filterFrom",        from != null ? from.toString() : "");
        model.addAttribute("filterTo",          to   != null ? to.toString()   : "");
        model.addAttribute("filterFromDisplay", from != null ? from.format(DISPLAY_FMT) : "");
        model.addAttribute("filterToDisplay",   to   != null ? to.format(DISPLAY_FMT)   : "");
        model.addAttribute("hasFilter",         from != null || to != null);
        model.addAttribute("today",             today.toString());
        model.addAttribute("yesterday",         yesterday.toString());
        model.addAttribute("last7from",         today.minusDays(6).toString());
        model.addAttribute("last30from",        today.minusDays(29).toString());
        model.addAttribute("filterStation",     stationCode);
    }

    private static LocalDate parseDate(String s) {
        try { return (s != null && !s.isBlank()) ? LocalDate.parse(s) : null; }
        catch (Exception e) { return null; }
    }
}
