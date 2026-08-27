package com.irishrail.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.irishrail.model.Station;
import com.irishrail.model.StationList;
import com.irishrail.model.TrainInfo;
import com.irishrail.model.TrainInfoList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Thin client over the Irish Rail realtime API: station lists and per-station departure boards. */
@Service
public class IrishRailService {

    private static final Logger log = LoggerFactory.getLogger(IrishRailService.class);

    @Value("${irishrail.api.all-stations-url}")
    private String allStationsUrl;

    @Value("${irishrail.api.station-list-base-url:https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=}")
    private String stationListBaseUrl;

    @Value("${irishrail.api.station-data-base-url}")
    private String stationDataBaseUrl;

    @Value("${irishrail.tracked-station-codes:CNLLY,HSTON}")
    private String trackedStationCodes;

    @Value("${irishrail.connolly.collection-station-types:D}")
    private String connollyCollectionStationTypes;

    @Value("${irishrail.heuston.collection-station-types:M,S}")
    private String heustonCollectionStationTypes;

    @Value("${irishrail.api.station-cache-ms:3600000}")
    private long stationCacheMs;

    @Value("${irishrail.api.board-cache-ms:35000}")
    private long boardCacheMs;

    private final RestTemplate restTemplate;
    private final XmlMapper xmlMapper = new XmlMapper();

    /**
     * Station lists change a few times a year but were re-fetched on every page load — three
     * upstream calls per view of /overview, /get, /map or /journey.
     */
    private final ConcurrentHashMap<String, CachedStations> stationCache = new ConcurrentHashMap<>();

    public IrishRailService(RestTemplate irishRailRestTemplate) {
        this.restTemplate = irishRailRestTemplate;
    }

    private record CachedStations(List<Station> stations, long loadedAtMs) {}

    /**
     * Departure boards, keyed by station and Heuston filter. The collector refreshes every tracked
     * station each cycle through {@link #fetchTrainsByStation}; page requests read that copy via
     * {@link #getTrainsByStation}. Before, every open tab of the live board was its own upstream
     * poll of a station the collector had fetched seconds earlier.
     */
    private record CachedBoard(List<TrainInfo> trains, long loadedAtMs) {}

    private final ConcurrentHashMap<String, CachedBoard> boardCache = new ConcurrentHashMap<>();

    /** Every station on the network ({@code StationType=A}), not just the collected routes. */
    public List<Station> getAllStations() {
        return fetchStations(stationListBaseUrl + "A", "A");
    }

    public List<Station> getAllDartStations() {
        return fetchStations(allStationsUrl, "DART");
    }

    public List<Station> getStationsByType(String stationType) {
        String type = stationType == null ? "" : stationType.trim().toUpperCase();
        if (type.isBlank()) return Collections.emptyList();
        return fetchStations(stationListBaseUrl + type, type);
    }

    public List<Station> getTrackedStations() {
        Set<String> trackedCodes = List.of(trackedStationCodes.split(",")).stream()
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
        List<Station> stations = getStationsByTypes(connollyCollectionStationTypes);
        if (stations.stream().noneMatch(s -> "CNLLY".equalsIgnoreCase(s.getStationCode()))) {
            stations.add(connollyStation());
        }
        return distinctSorted(stations);
    }

    public List<Station> getHeustonCollectionStations() {
        List<Station> stations = getStationsByTypes(heustonCollectionStationTypes);
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
        CachedBoard cached = boardCache.get(boardKey(stationCode, includeHeustonTrains));
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= boardCacheMs) {
            return cached.trains();
        }
        return fetchTrainsByStation(stationCode, includeHeustonTrains);
    }

    /** Always goes upstream and refreshes the cache. The collector's entry point. */
    public List<TrainInfo> fetchTrainsByStation(String stationCode, boolean includeHeustonTrains) {
        String key = boardKey(stationCode, includeHeustonTrains);
        try {
            String url = stationDataBaseUrl + stationCode;
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) return staleBoardOrEmpty(key);
            TrainInfoList list = xmlMapper.readValue(xml, TrainInfoList.class);
            List<TrainInfo> trains = list.getTrains();
            List<TrainInfo> filtered = trains == null ? List.of() : trains.stream()
                    .filter(t -> !"bus".equalsIgnoreCase(t.getTrainType()))
                    .filter(t -> includeHeustonTrains || (!containsHeuston(t.getOrigin()) && !containsHeuston(t.getDestination())))
                    .collect(Collectors.toUnmodifiableList());
            boardCache.put(key, new CachedBoard(filtered, System.currentTimeMillis()));
            return filtered;
        } catch (Exception e) {
            log.error("Falha ao buscar dados da estação {}: {}", stationCode, e.getMessage());
            return staleBoardOrEmpty(key);
        }
    }

    /**
     * On an upstream failure a board up to two cycles old is still better than a blank one; past
     * that it would be showing trains that have long since left.
     */
    private List<TrainInfo> staleBoardOrEmpty(String key) {
        CachedBoard cached = boardCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= boardCacheMs * 2) {
            return cached.trains();
        }
        return Collections.emptyList();
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

    private List<Station> getStationsByTypes(String stationTypes) {
        if (stationTypes == null || stationTypes.isBlank()) return new ArrayList<>();
        List<Station> stations = List.of(stationTypes.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .flatMap(type -> getStationsByType(type).stream())
                .collect(Collectors.toList());
        return distinctSorted(stations);
    }

    private List<Station> fetchStations(String url, String label) {
        CachedStations cached = stationCache.get(url);
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= stationCacheMs) {
            return cached.stations();
        }

        try {
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) return fallback(cached);
            StationList list = xmlMapper.readValue(xml, StationList.class);
            List<Station> stations = list.getStations();
            if (stations == null || stations.isEmpty()) return fallback(cached);

            List<Station> immutable = List.copyOf(stations);
            stationCache.put(url, new CachedStations(immutable, System.currentTimeMillis()));
            return immutable;
        } catch (Exception e) {
            log.error("Falha ao buscar estações {}: {}", label, e.getMessage());
            return fallback(cached);
        }
    }

    /** Stale station names beat no station names — the list is near-static. */
    private List<Station> fallback(CachedStations cached) {
        return cached != null ? cached.stations() : Collections.emptyList();
    }

    private List<Station> distinctSorted(List<Station> stations) {
        Map<String, Station> byCode = new LinkedHashMap<>();
        for (Station station : stations) {
            String code = normalizeCode(station.getStationCode());
            if (!code.isBlank()) {
                byCode.putIfAbsent(code, station);
            }
        }
        return byCode.values().stream()
                .sorted(Comparator.comparing(Station::getStationDesc, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());
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
