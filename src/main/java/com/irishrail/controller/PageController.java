package com.irishrail.controller;

import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.AnalyticsView;
import com.irishrail.model.DelayCategory;
import com.irishrail.model.ServiceScope;
import com.irishrail.model.Station;
import com.irishrail.model.TrainInfo;
import com.irishrail.service.AnalyticsQueryService;
import com.irishrail.service.IrishRailService;
import com.irishrail.web.StationCodes;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server-rendered pages.
 *
 * <p>Split from the JSON endpoints, which now live in {@link ApiController}. They shared a single
 * 644-line class in which {@code overview()} and the JSON payload builder ran the same seven
 * queries and the same category arithmetic side by side; both now read one
 * {@link AnalyticsQueryService}, so a page and its own background refresh cannot disagree.
 */
@Controller
public class PageController {

    static final String DEFAULT_STATION = "CNLLY";
    static final String HEUSTON_STATION = "HSTON";

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final IrishRailService irishRailService;
    private final AnalyticsQueryService analytics;
    private final StationCodes stationCodes;
    private final IrishRailProperties properties;

    public PageController(IrishRailService irishRailService,
                          AnalyticsQueryService analytics,
                          StationCodes stationCodes,
                          IrishRailProperties properties) {
        this.irishRailService = irishRailService;
        this.analytics = analytics;
        this.stationCodes = stationCodes;
        this.properties = properties;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/overview";
    }

    // ── live board + analytics ────────────────────────────────────────────────

    @GetMapping("/get")
    public String getTrains(
            @RequestParam(defaultValue = DEFAULT_STATION) String stationCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Model model) {

        // An unknown code falls back rather than 400-ing the whole dashboard, but it never reaches
        // the board cache or the upstream API.
        String code = stationCodes.orDefault(stationCode, DEFAULT_STATION);

        List<Station> stations = sortedByName(irishRailService.getTrackedStations());
        List<TrainInfo> trains = irishRailService.getTrainsByStation(code);

        String stationName = stations.stream()
                .filter(s -> code.equalsIgnoreCase(s.getStationCode()))
                .map(Station::getStationDesc)
                .findFirst().orElse(code);
        long liveDelayed = trains.stream().filter(t -> t.getLate() >= DelayCategory.delayedThreshold()).count();

        model.addAttribute("stations", stations);
        model.addAttribute("selectedCode", code);
        model.addAttribute("stationName", stationName);
        model.addAttribute("trains", trains);
        model.addAttribute("updatedAt", LocalTime.now().format(CLOCK_FMT));
        model.addAttribute("totalTrains", trains.size());
        model.addAttribute("delayedCount", liveDelayed);
        model.addAttribute("onTimeCount", trains.size() - liveDelayed);

        // The live board's analytics are system-wide, not scoped to the station being displayed,
        // and it renders the top 15 stations rather than all of them.
        AnalyticsView view = analytics.get(new AnalyticsQueryService.Request(from, to, null, true, false));
        addAnalytics(model, view);
        model.addAttribute("categoryColors", buildCategoryColors());

        addFilterMeta(model, from, to, code);
        return "trains";
    }

    // ── overview ──────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public String overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String stationCode,
            @RequestParam(required = false, defaultValue = "") String period,
            Model model) {

        boolean hasExplicit = from != null || to != null;
        boolean isDefaultFilter = !"all".equalsIgnoreCase(period) && !hasExplicit;

        String scope = ServiceScope.fromOverviewCode(stationCode);
        boolean hasStation = scope != null;

        List<Station> stations = sortedByName(irishRailService.getTrackedStations());

        AnalyticsQueryService.Request request =
                AnalyticsQueryService.Request.overview(from, to, scope, period, false, true);
        LocalDate filterFrom = request.from();
        LocalDate filterTo = request.to();

        AnalyticsView view = analytics.get(request);
        addAnalytics(model, view);
        // The overview template names the full ranking "allStations"; the live board calls the
        // same list "stationRank". One query, two names, rather than two code paths.
        model.addAttribute("allStations", view.stationRank());

        String selectedStationName = null;
        if (hasStation) {
            String code = ServiceScope.overviewCode(scope);
            selectedStationName = stations.stream()
                    .filter(s -> code.equalsIgnoreCase(s.getStationCode()))
                    .map(Station::getStationDesc)
                    .findFirst().orElse(code);
        }

        model.addAttribute("stations", stations);
        model.addAttribute("selectedCode", hasStation ? ServiceScope.overviewCode(scope) : "OVERVIEW");
        model.addAttribute("hasStationFilter", hasStation);
        model.addAttribute("selectedStationName", selectedStationName);
        model.addAttribute("connollyCode", DEFAULT_STATION);
        model.addAttribute("heustonCode", HEUSTON_STATION);

        addFilterMeta(model, filterFrom, filterTo, hasStation ? ServiceScope.overviewCode(scope) : "OVERVIEW");
        model.addAttribute("hasFilter", hasExplicit);
        model.addAttribute("isDefaultFilter", isDefaultFilter);
        return "overview";
    }

    // ── map ───────────────────────────────────────────────────────────────────

    @GetMapping("/map")
    public String trainMap(Model model) {
        IrishRailProperties.MapView mapView = properties.map();
        model.addAttribute("stations", sortedByName(irishRailService.getTrackedStations()));
        model.addAttribute("selectedCode", "MAP");
        model.addAttribute("mapTileUrl", mapView.tileUrl());
        model.addAttribute("mapTileAttribution", mapView.tileAttribution());
        model.addAttribute("mapTileFilter", mapView.tileFilter());
        model.addAttribute("mapTileMaxZoom", mapView.tileMaxZoom());
        model.addAttribute("railOverlayUrl", mapView.railOverlayUrl());
        model.addAttribute("railOverlayAttribution", mapView.railOverlayAttribution());
        model.addAttribute("mapRefreshMs", mapView.refreshMs());
        model.addAttribute("delayedThreshold", DelayCategory.delayedThreshold());
        model.addAttribute("delayCategoryData", buildCategoryData());
        return "map";
    }

    // ── journey planner ───────────────────────────────────────────────────────

    @GetMapping("/journey")
    public String journeyPlanner(Model model) {
        model.addAttribute("stations", sortedByName(irishRailService.getJourneyPlannerStations()));
        model.addAttribute("selectedCode", "JOURNEY");
        return "journey";
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<Station> sortedByName(List<Station> stations) {
        return stations.stream()
                .sorted(Comparator.comparing(Station::getStationDesc,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /** Spreads one {@link AnalyticsView} over the attribute names the templates already use. */
    private void addAnalytics(Model model, AnalyticsView v) {
        model.addAttribute("dashboard", v.dashboard());
        model.addAttribute("stationRank", v.stationRank());
        model.addAttribute("hourly", v.hourly());
        model.addAttribute("hourlyLabels", v.hourlyLabels());
        model.addAttribute("hourlyDelayPcts", v.hourlyDelayPcts());
        model.addAttribute("hourlyAvgDelays", v.hourlyAvgDelays());
        model.addAttribute("top10Delays", v.top10Delays());
        model.addAttribute("routeRanking", v.routeRanking());
        model.addAttribute("recentDelays", v.recentDelays());
        model.addAttribute("destinations", v.destinations());
        model.addAttribute("destLabels", v.destLabels());
        model.addAttribute("destAvgDelays", v.destAvgDelays());
        model.addAttribute("destDelayCounts", v.destDelayCounts());
        model.addAttribute("categories", v.categories());
        model.addAttribute("maxCatCount", v.maxCatCount());
        model.addAttribute("catOnTime", v.catOnTime());
        model.addAttribute("catSmall", v.catSmall());
        model.addAttribute("catMedium", v.catMedium());
        model.addAttribute("catBig", v.catBig());
        model.addAttribute("catExtreme", v.catExtreme());
        model.addAttribute("delayCategoryData", buildCategoryData());
        model.addAttribute("delayedThreshold", DelayCategory.delayedThreshold());
    }

    static List<Map<String, Object>> buildCategoryData() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DelayCategory cat : DelayCategory.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minMinutes", cat.getMinMinutes());
            m.put("maxMinutes", cat.getMaxMinutes() == Integer.MAX_VALUE ? -1 : cat.getMaxMinutes());
            m.put("textColor", cat.getTextColor());
            m.put("bgColor", cat.getBgColor());
            m.put("borderColor", cat.getBorderColor());
            m.put("displayLabel", cat.getDisplayLabel());
            m.put("isOnTime", cat.isOnTime());
            list.add(m);
        }
        return list;
    }

    private static Map<String, String> buildCategoryColors() {
        Map<String, String> colors = new LinkedHashMap<>();
        for (DelayCategory cat : DelayCategory.values()) {
            colors.put(cat.getDisplayLabel(), cat.getTextColor());
        }
        return colors;
    }

    private void addFilterMeta(Model model, LocalDate from, LocalDate to, String stationCode) {
        LocalDate today = LocalDate.now();
        model.addAttribute("filterFrom", from != null ? from.toString() : "");
        model.addAttribute("filterTo", to != null ? to.toString() : "");
        model.addAttribute("filterFromDisplay", from != null ? from.format(DISPLAY_FMT) : "");
        model.addAttribute("filterToDisplay", to != null ? to.format(DISPLAY_FMT) : "");
        model.addAttribute("hasFilter", from != null || to != null);
        model.addAttribute("today", today.toString());
        model.addAttribute("yesterday", today.minusDays(1).toString());
        model.addAttribute("last7from", today.minusDays(6).toString());
        model.addAttribute("last30from", today.minusDays(29).toString());
        model.addAttribute("filterStation", stationCode);
    }
}
