package com.irishrail.controller;

import com.irishrail.model.Station;
import com.irishrail.model.TrainInfo;
import com.irishrail.service.IrishRailService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
public class TrainController {

    private static final String DEFAULT_STATION = "CNLLY";

    private final IrishRailService irishRailService;

    public TrainController(IrishRailService irishRailService) {
        this.irishRailService = irishRailService;
    }

    @GetMapping("/get")
    public String getTrains(@RequestParam(defaultValue = DEFAULT_STATION) String stationCode,
                            Model model) {
        List<Station> stations = irishRailService.getAllDartStations();
        List<TrainInfo> trains = irishRailService.getTrainsByStation(stationCode);

        Station selected = stations.stream()
                .filter(s -> stationCode.equalsIgnoreCase(s.getStationCode()))
                .findFirst()
                .orElse(null);

        String stationName = selected != null ? selected.getStationDesc() : stationCode;
        String updatedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        long delayedCount = trains.stream().filter(t -> t.getLate() > 0).count();

        model.addAttribute("stations", stations);
        model.addAttribute("selectedCode", stationCode.toUpperCase());
        model.addAttribute("stationName", stationName);
        model.addAttribute("trains", trains);
        model.addAttribute("updatedAt", updatedAt);
        model.addAttribute("totalTrains", trains.size());
        model.addAttribute("delayedCount", delayedCount);
        model.addAttribute("onTimeCount", trains.size() - delayedCount);
        return "trains";
    }

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

    @GetMapping("/")
    public String home() {
        return "redirect:/get";
    }
}
