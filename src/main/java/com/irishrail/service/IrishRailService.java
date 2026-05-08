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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class IrishRailService {

    private static final Logger log = LoggerFactory.getLogger(IrishRailService.class);

    @Value("${irishrail.api.all-stations-url}")
    private String allStationsUrl;

    @Value("${irishrail.api.station-data-base-url}")
    private String stationDataBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final XmlMapper xmlMapper = new XmlMapper();

    public List<Station> getAllDartStations() {
        try {
            String xml = restTemplate.getForObject(allStationsUrl, String.class);
            if (xml == null || xml.isBlank()) return Collections.emptyList();
            StationList list = xmlMapper.readValue(xml, StationList.class);
            List<Station> stations = list.getStations();
            return stations != null ? stations : Collections.emptyList();
        } catch (Exception e) {
            log.error("Falha ao buscar estações DART: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static boolean containsHeuston(String value) {
        return value != null && value.toLowerCase().contains("heuston");
    }

    public List<TrainInfo> getTrainsByStation(String stationCode) {
        try {
            String url = stationDataBaseUrl + stationCode;
            String xml = restTemplate.getForObject(url, String.class);
            if (xml == null || xml.isBlank()) return Collections.emptyList();
            TrainInfoList list = xmlMapper.readValue(xml, TrainInfoList.class);
            List<TrainInfo> trains = list.getTrains();
            if (trains == null) return Collections.emptyList();
            return trains.stream()
                    .filter(t -> !"bus".equalsIgnoreCase(t.getTrainType()))
                    .filter(t -> !containsHeuston(t.getOrigin()) && !containsHeuston(t.getDestination()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Falha ao buscar dados da estação {}: {}", stationCode, e.getMessage());
            return Collections.emptyList();
        }
    }
}
