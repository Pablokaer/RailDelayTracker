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

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private final RestTemplate restTemplate = new RestTemplate();
    private final XmlMapper xmlMapper = new XmlMapper();

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

    public List<TrainInfo> getTrainsByStation(String stationCode) {
        return getTrainsByStation(stationCode, isHeustonStation(stationCode));
    }

    public List<TrainInfo> getTrainsByStation(String stationCode, boolean includeHeustonTrains) {
        try {
            String url = stationDataBaseUrl + stationCode;
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) return Collections.emptyList();
            TrainInfoList list = xmlMapper.readValue(xml, TrainInfoList.class);
            List<TrainInfo> trains = list.getTrains();
            if (trains == null) return Collections.emptyList();
            return trains.stream()
                    .filter(t -> !"bus".equalsIgnoreCase(t.getTrainType()))
                    .filter(t -> includeHeustonTrains || (!containsHeuston(t.getOrigin()) && !containsHeuston(t.getDestination())))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Falha ao buscar dados da estação {}: {}", stationCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    public static boolean containsHeuston(String value) {
        return value != null && value.toLowerCase().contains("heuston");
    }

    public static boolean isHeustonRelated(TrainInfo train) {
        return train != null && (containsHeuston(train.getOrigin()) || containsHeuston(train.getDestination()));
    }

    private List<Station> getStationsByTypes(String stationTypes) {
        if (stationTypes == null || stationTypes.isBlank()) return Collections.emptyList();
        List<Station> stations = List.of(stationTypes.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .flatMap(type -> getStationsByType(type).stream())
                .collect(Collectors.toList());
        return distinctSorted(stations);
    }

    private List<Station> fetchStations(String url, String label) {
        try {
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) return Collections.emptyList();
            StationList list = xmlMapper.readValue(xml, StationList.class);
            List<Station> stations = list.getStations();
            return stations != null ? stations : Collections.emptyList();
        } catch (Exception e) {
            log.error("Falha ao buscar estações {}: {}", label, e.getMessage());
            return Collections.emptyList();
        }
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
        return station;
    }

    private Station connollyStation() {
        Station station = new Station();
        station.setStationCode("CNLLY");
        station.setStationDesc("Dublin Connolly");
        station.setStationAlias("Connolly");
        return station;
    }
}
