package com.irishrail.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.Station;
import com.irishrail.model.StationList;
import com.irishrail.model.TrainInfo;
import com.irishrail.model.TrainInfoList;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Thin client over the Irish Rail realtime API: station lists and per-station departure boards. */
@Service
public class IrishRailService {

    private static final Logger log = LoggerFactory.getLogger(IrishRailService.class);

    private final RestClient restClient;
    private final XmlMapper xmlMapper;
    private final IrishRailProperties properties;
    private final MeterRegistry meters;

    /**
     * Station lists change a few times a year but were re-fetched on every page load — three
     * upstream calls per view of /overview, /get, /map or /journey.
     *
     * <p>Deliberately no {@code expireAfterWrite}: {@link #fallback} serves an arbitrarily old list
     * when the API is down, and a list that is months stale still beats an empty station picker.
     * Freshness is decided by the stored timestamp; Caffeine is here for the size bound.
     */
    private final Cache<String, CachedStations> stationCache;

    /**
     * Departure boards, keyed by station and Heuston filter. The collector refreshes every tracked
     * station each cycle through {@link #fetchTrainsByStation}; page requests read that copy via
     * {@link #getTrainsByStation}. Before, every open tab of the live board was its own upstream
     * poll of a station the collector had fetched seconds earlier.
     *
     * <p>This was an unbounded {@code ConcurrentHashMap} whose keys came straight from the
     * {@code stationCode} request parameter, so {@code /api/trains?stationCode=<anything>} grew it
     * without limit. It is now size-bounded, and callers validate the code first.
     */
    private final Cache<String, CachedBoard> boardCache;

    public IrishRailService(RestClient irishRailRestClient,
                            XmlMapper irishRailXmlMapper,
                            IrishRailProperties properties,
                            MeterRegistry meters) {
        this.restClient = irishRailRestClient;
        this.xmlMapper = irishRailXmlMapper;
        this.properties = properties;
        this.meters = meters;

        this.stationCache = Caffeine.newBuilder().maximumSize(32).recordStats().build();
        this.boardCache = Caffeine.newBuilder()
                .maximumSize(properties.api().maxCachedBoards())
                // Twice the TTL, because staleBoardOrEmpty() still serves an entry one cycle past
                // its freshness window when upstream fails.
                .expireAfterWrite(Duration.ofMillis(properties.api().boardCacheMs() * 2))
                .recordStats()
                .build();
    }

    private record CachedStations(List<Station> stations, long loadedAtMs) {}

    private record CachedBoard(List<TrainInfo> trains, long loadedAtMs) {}

    /** Every station on the network ({@code StationType=A}), not just the collected routes. */
    public List<Station> getAllStations() {
        return fetchStations(properties.api().stationListBaseUrl() + "A", "A");
    }

    public List<Station> getAllDartStations() {
        return fetchStations(properties.api().allStationsUrl(), "DART");
    }

    public List<Station> getStationsByType(String stationType) {
        String type = stationType == null ? "" : stationType.trim().toUpperCase();
        if (type.isBlank()) return Collections.emptyList();
        return fetchStations(properties.api().stationListBaseUrl() + type, type);
    }

    public List<Station> getTrackedStations() {
        Set<String> trackedCodes = properties.trackedStationCodes().stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        List<Station> stations = getCollectionStations().stream()
                .filter(s -> trackedCodes.contains(normalizeCode(s.getStationCode())))
                .collect(Collectors.toList());

        if (trackedCodes.contains("CNLLY") && stations.stream().noneMatch(s -> "CNLLY".equalsIgnoreCase(s.getStationCode()))) {
            stations.add(connollyStation());
        }

        if (trackedCodes.contains("HSTON") && stations.stream().noneMatch(s -> "HSTON".equalsIgnoreCase(s.getStationCode()))) {
            stations.add(heustonStation());
        }

        return distinctSorted(stations);
    }

    public List<Station> getConnollyCollectionStations() {
        List<Station> stations = getStationsByTypes(properties.connolly().collectionStationTypes());
        if (stations.stream().noneMatch(s -> "CNLLY".equalsIgnoreCase(s.getStationCode()))) {
            stations.add(connollyStation());
        }
        return distinctSorted(stations);
    }

    public List<Station> getHeustonCollectionStations() {
        List<Station> stations = getStationsByTypes(properties.heuston().collectionStationTypes());
        if (stations.stream().noneMatch(s -> "HSTON".equalsIgnoreCase(s.getStationCode()))) {
            stations.add(heustonStation());
        }
        return distinctSorted(stations);
    }

    public List<Station> getCollectionStations() {
        List<Station> stations = getConnollyCollectionStations();
        stations.addAll(getHeustonCollectionStations());
        return distinctSorted(stations);
    }

    public List<Station> getJourneyPlannerStations() {
        return getCollectionStations();
    }

    public List<TrainInfo> getTrainsByStation(String stationCode) {
        return getTrainsByStation(stationCode, isHeustonStation(stationCode));
    }

    /** Serves the collector's copy when it is fresh enough; otherwise goes upstream. */
    public List<TrainInfo> getTrainsByStation(String stationCode, boolean includeHeustonTrains) {
        CachedBoard cached = boardCache.getIfPresent(boardKey(stationCode, includeHeustonTrains));
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= properties.api().boardCacheMs()) {
            return cached.trains();
        }
        return fetchTrainsByStation(stationCode, includeHeustonTrains);
    }

    /** Always goes upstream and refreshes the cache. The collector's entry point. */
    public List<TrainInfo> fetchTrainsByStation(String stationCode, boolean includeHeustonTrains) {
        String key = boardKey(stationCode, includeHeustonTrains);
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            String xml = fetch(stationDataUrl(stationCode));
            if (xml == null || xml.isBlank()) return staleBoardOrEmpty(key);
            TrainInfoList list = xmlMapper.readValue(xml, TrainInfoList.class);
            List<TrainInfo> trains = list.getTrains();
            List<TrainInfo> filtered = trains == null ? List.of() : trains.stream()
                    .filter(t -> !"bus".equalsIgnoreCase(t.getTrainType()))
                    .filter(t -> includeHeustonTrains || (!containsHeuston(t.getOrigin()) && !containsHeuston(t.getDestination())))
                    .collect(Collectors.toUnmodifiableList());
            boardCache.put(key, new CachedBoard(filtered, System.currentTimeMillis()));
            outcome = "success";
            return filtered;
        } catch (Exception e) {
            log.error("Failed to fetch station board for {}: {}", stationCode, e.getMessage());
            return staleBoardOrEmpty(key);
        } finally {
            sample.stop(Timer.builder("irishrail.upstream")
                    .tag("endpoint", "station-board")
                    .tag("outcome", outcome)
                    .register(meters));
        }
    }

    /**
     * On an upstream failure a board up to two cycles old is still better than a blank one; past
     * that it would be showing trains that have long since left.
     */
    private List<TrainInfo> staleBoardOrEmpty(String key) {
        CachedBoard cached = boardCache.getIfPresent(key);
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= properties.api().boardCacheMs() * 2) {
            return cached.trains();
        }
        return Collections.emptyList();
    }

    /**
     * Departure-board URL for one station.
     *
     * <p>The code used to be concatenated straight onto the configured base URL. Building the URI
     * encodes it instead, so a code carrying {@code &} cannot append parameters to the upstream
     * query and a {@code {} } cannot be read as an unresolved {@code RestTemplate} URI-template
     * placeholder (which throws rather than fetching).
     *
     * <p>The configured value historically ended in {@code &StationCode=}; that suffix is stripped
     * so an old override does not produce the parameter twice.
     */
    String stationDataUrl(String stationCode) {
        String base = properties.api().stationDataBaseUrl().trim();
        int marker = base.toUpperCase(Locale.ROOT).indexOf("STATIONCODE=");
        if (marker > 0) base = base.substring(0, marker).replaceAll("[?&]+$", "");
        return UriComponentsBuilder.fromUriString(base)
                .queryParam("StationCode", normalizeCode(stationCode))
                .build()
                .toUriString();
    }

    private static String boardKey(String stationCode, boolean includeHeustonTrains) {
        return normalizeCode(stationCode) + "|" + includeHeustonTrains;
    }

    public static boolean containsHeuston(String value) {
        return value != null && value.toLowerCase().contains("heuston");
    }

    public static boolean isHeustonRelated(TrainInfo train) {
        return train != null && (containsHeuston(train.getOrigin()) || containsHeuston(train.getDestination()));
    }

    private List<Station> getStationsByTypes(List<String> stationTypes) {
        if (stationTypes == null || stationTypes.isEmpty()) return new ArrayList<>();
        List<Station> stations = stationTypes.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .flatMap(type -> getStationsByType(type).stream())
                .collect(Collectors.toList());
        return distinctSorted(stations);
    }

    private List<Station> fetchStations(String url, String label) {
        CachedStations cached = stationCache.getIfPresent(url);
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= properties.api().stationCacheMs()) {
            return cached.stations();
        }

        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            String xml = fetch(url);
            if (xml == null || xml.isBlank()) return fallback(cached);
            StationList list = xmlMapper.readValue(xml, StationList.class);
            List<Station> stations = list.getStations();
            if (stations == null || stations.isEmpty()) return fallback(cached);

            List<Station> immutable = List.copyOf(stations);
            stationCache.put(url, new CachedStations(immutable, System.currentTimeMillis()));
            outcome = "success";
            return immutable;
        } catch (Exception e) {
            log.error("Failed to fetch station list {}: {}", label, e.getMessage());
            return fallback(cached);
        } finally {
            sample.stop(Timer.builder("irishrail.upstream")
                    .tag("endpoint", "station-list")
                    .tag("outcome", outcome)
                    .register(meters));
        }
    }

    /**
     * The URL is already fully built and encoded, so it is passed as a {@link URI}: handing a
     * string to {@code uri(...)} would have it read as a URI template, where any brace in the
     * value becomes a placeholder to expand.
     */
    private String fetch(String url) {
        return restClient.get().uri(URI.create(url)).retrieve().body(String.class);
    }

    /** Stale station names beat no station names — the list is near-static. */
    private List<Station> fallback(CachedStations cached) {
        return cached != null ? cached.stations() : Collections.emptyList();
    }

    private List<Station> distinctSorted(List<Station> stations) {
        return dedupe(stations).stream()
                .sorted(Comparator.comparing(Station::getStationDesc, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());
    }

    /**
     * One entry per physical station.
     *
     * <p>Distinct by code is not enough: the four-track stretch of the Kildare line (Adamstown,
     * Clondalkin, Hazelhatch, Kishoge, Park West) is listed once per platform pair — a base code
     * with {@code StationId} N plus an "F" (fast, 900+N) and an "S" (slow, 1000+N) variant, all
     * with the same coordinates and all answering with the same departures. They showed up as
     * three identical rows in the journey planner and cost nine redundant upstream calls per
     * collection cycle. Most variants share the name; one does not ("PARK WEST" for Park West and
     * Cherry Orchard), so identical coordinates — to 4 decimals, about 10 m — count as the same
     * station too. Within a group the lowest {@code StationId} wins, which is the base code (and
     * the one the departure feed itself reports for every train).
     */
    static List<Station> dedupe(List<Station> stations) {
        Map<String, Station> byCode = new LinkedHashMap<>();
        for (Station station : stations) {
            String code = normalizeCode(station.getStationCode());
            if (!code.isBlank()) byCode.putIfAbsent(code, station);
        }
        Map<String, Station> byName = collapse(byCode.values(), IrishRailService::nameKey);
        Map<String, Station> byPlace = collapse(byName.values(), IrishRailService::placeKey);
        return new ArrayList<>(byPlace.values());
    }

    /** Groups by {@code key}, keeping the lowest StationId per group; a null key never groups. */
    private static Map<String, Station> collapse(Iterable<Station> stations,
                                                 java.util.function.Function<Station, String> key) {
        Map<String, Station> kept = new LinkedHashMap<>();
        for (Station station : stations) {
            String k = key.apply(station);
            if (k == null) k = "code:" + normalizeCode(station.getStationCode());
            Station existing = kept.get(k);
            if (existing == null || station.getStationId() < existing.getStationId()) kept.put(k, station);
        }
        return kept;
    }

    private static String nameKey(Station station) {
        String name = StationDirectory.normalize(station.getStationDesc());
        return name.isEmpty() ? null : "name:" + name;
    }

    private static String placeKey(Station station) {
        if (!StationDirectory.hasValidCoordinates(station)) return null;
        return String.format(Locale.ROOT, "place:%.4f,%.4f", station.getStationLatitude(), station.getStationLongitude());
    }

    private static boolean isHeustonStation(String stationCode) {
        return "HSTON".equalsIgnoreCase(normalizeCode(stationCode));
    }

    private static String normalizeCode(String stationCode) {
        return stationCode == null ? "" : stationCode.trim().toUpperCase();
    }

    private Station heustonStation() {
        Station station = new Station();
        station.setStationCode("HSTON");
        station.setStationDesc("Dublin Heuston");
        station.setStationAlias("Heuston");
        station.setStationLatitude(53.3465);
        station.setStationLongitude(-6.2927);
        return station;
    }

    private Station connollyStation() {
        Station station = new Station();
        station.setStationCode("CNLLY");
        station.setStationDesc("Dublin Connolly");
        station.setStationAlias("Connolly");
        station.setStationLatitude(53.3531);
        station.setStationLongitude(-6.2459);
        return station;
    }
}
