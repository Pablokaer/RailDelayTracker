package com.irishrail.controller;

import com.irishrail.model.*;
import com.irishrail.service.DelayTrackingService;
import com.irishrail.service.IrishRailService;
import com.irishrail.service.SnapshotEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class TrainController {

    private static final String DEFAULT_STATION = "CNLLY";

    private final IrishRailService irishRailService;
    private final DelayTrackingService delayTrackingService;
    private final SnapshotEventService snapshotEventService;

    public TrainController(IrishRailService irishRailService,
                           DelayTrackingService delayTrackingService,
                           SnapshotEventService snapshotEventService) {
        this.irishRailService     = irishRailService;
        this.delayTrackingService = delayTrackingService;
        this.snapshotEventService = snapshotEventService;
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
        List<Station>   stations = irishRailService.getAllDartStations()
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
            Model model) {

        LocalDate filterFrom  = parseDate(from);
        LocalDate filterTo    = parseDate(to);
        boolean   hasStation  = stationCode != null && !stationCode.isBlank()
                                && !"OVERVIEW".equalsIgnoreCase(stationCode);

        List<Station> stations = irishRailService.getAllDartStations()
                .stream()
                .sorted(Comparator.comparing(Station::getStationDesc))
                .collect(Collectors.toList());

        DashboardSummary       dashboard;
        List<StationStats>     allStations  = Collections.emptyList();
        List<HourlyStats>      hourly;
        List<DestinationStats> destinations;
        List<TripDelaySummary> top10;
        Map<String, Long>      categories;

        if (hasStation) {
            dashboard    = delayTrackingService.getDashboardSummaryForStation(filterFrom, filterTo, stationCode);
            hourly       = delayTrackingService.getHourlyStatsForStation(filterFrom, filterTo, stationCode);
            destinations = delayTrackingService.getTopDestinationsByDelayForStation(filterFrom, filterTo, stationCode);
            top10        = delayTrackingService.getTop10LargestDelaysForStation(filterFrom, filterTo, stationCode);
            categories   = delayTrackingService.getDelayCategoriesForStation(filterFrom, filterTo, stationCode);
        } else {
            dashboard    = delayTrackingService.getDashboardSummary(filterFrom, filterTo);
            allStations  = delayTrackingService.getAllStationRanking(filterFrom, filterTo);
            hourly       = delayTrackingService.getHourlyStats(filterFrom, filterTo);
            destinations = delayTrackingService.getTopDestinationsByDelay(filterFrom, filterTo);
            top10        = delayTrackingService.getTop10LargestDelays(filterFrom, filterTo);
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

        model.addAttribute("dashboard",            dashboard);
        model.addAttribute("allStations",          allStations);
        model.addAttribute("hourly",               hourly);
        model.addAttribute("destinations",         destinations);
        model.addAttribute("top10Delays",          top10);
        model.addAttribute("stations",             stations);
        model.addAttribute("selectedCode",         hasStation ? stationCode.toUpperCase() : "OVERVIEW");
        model.addAttribute("hasStationFilter",     hasStation);
        model.addAttribute("selectedStationName",  selectedStationName);
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

        return "overview";
    }

    // ── JSON APIs ─────────────────────────────────────────────────────────────

    @GetMapping("/api/trains")
    @ResponseBody
    public ResponseEntity<List<TrainInfo>> getTrainsJson(
            @RequestParam(defaultValue = DEFAULT_STATION) String stationCode) {
        return ResponseEntity.ok(irishRailService.getTrainsByStation(stationCode));
    }

    @GetMapping("/api/stations")
    @ResponseBody
    public ResponseEntity<List<Station>> getStations() {
        return ResponseEntity.ok(irishRailService.getAllDartStations());
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
        return ResponseEntity.ok(buildAnalyticsPayload(from, to, null));
    }

    @GetMapping("/api/analytics/overview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAnalyticsOverview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String stationCode) {
        return ResponseEntity.ok(buildAnalyticsPayload(from, to, stationCode));
    }

    @GetMapping("/api/events")
    @ResponseBody
    public SseEmitter getEvents() {
        return snapshotEventService.subscribe();
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/overview?from=" + LocalDate.now() + "&to=" + LocalDate.now();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildAnalyticsPayload(String from, String to, String stationCode) {
        LocalDate filterFrom = parseDate(from);
        LocalDate filterTo   = parseDate(to);
        boolean   hasStation = stationCode != null && !stationCode.isBlank()
                               && !"OVERVIEW".equalsIgnoreCase(stationCode);

        DashboardSummary       dashboard;
        List<StationStats>     stationRank;
        List<HourlyStats>      hourly;
        List<TripDelaySummary> top10;
        List<DestinationStats> destinations;
        Map<String, Long>      categories;

        if (hasStation) {
            dashboard    = delayTrackingService.getDashboardSummaryForStation(filterFrom, filterTo, stationCode);
            stationRank  = Collections.emptyList();
            hourly       = delayTrackingService.getHourlyStatsForStation(filterFrom, filterTo, stationCode);
            top10        = delayTrackingService.getTop10LargestDelaysForStation(filterFrom, filterTo, stationCode);
            destinations = delayTrackingService.getTopDestinationsByDelayForStation(filterFrom, filterTo, stationCode);
            categories   = delayTrackingService.getDelayCategoriesForStation(filterFrom, filterTo, stationCode);
        } else {
            dashboard    = delayTrackingService.getDashboardSummary(filterFrom, filterTo);
            stationRank  = delayTrackingService.getAllStationRanking(filterFrom, filterTo);
            hourly       = delayTrackingService.getHourlyStats(filterFrom, filterTo);
            top10        = delayTrackingService.getTop10LargestDelays(filterFrom, filterTo);
            destinations = delayTrackingService.getTopDestinationsByDelay(filterFrom, filterTo);
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

    private void addFilterMeta(Model model, LocalDate from, LocalDate to, String stationCode) {
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        model.addAttribute("filterFrom",  from != null ? from.toString() : "");
        model.addAttribute("filterTo",    to   != null ? to.toString()   : "");
        model.addAttribute("hasFilter",   from != null || to != null);
        model.addAttribute("today",       today.toString());
        model.addAttribute("yesterday",   yesterday.toString());
        model.addAttribute("last7from",   today.minusDays(6).toString());
        model.addAttribute("last30from",  today.minusDays(29).toString());
        model.addAttribute("filterStation", stationCode);
    }

    private static LocalDate parseDate(String s) {
        try { return (s != null && !s.isBlank()) ? LocalDate.parse(s) : null; }
        catch (Exception e) { return null; }
    }
}
