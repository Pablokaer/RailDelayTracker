package com.irishrail.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.Station;
import com.irishrail.model.TrainMovement;
import com.irishrail.model.TrainMovementList;
import com.irishrail.model.TrainRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the stop-by-stop path of a single train from {@code getTrainMovementsXML}.
 *
 * <p>The feed returns every scheduled point including signalling and timing points
 * ({@code LocationType=T}), which carry no name and no coordinates; those are dropped so what
 * remains is the passenger route, which resolves fully against {@link StationDirectory}.
 *
 * <p>Progress comes from the actual {@code Arrival}/{@code Departure} times, which stay empty until
 * the train really passes. Where the feed has not populated them at all, {@code StopType=N} is used
 * as a fallback marker for the next stop.
 */
@Service
public class TrainRouteService {

    private static final Logger log = LoggerFactory.getLogger(TrainRouteService.class);

    /** Irish Rail expects the service date as "13 Aug 2026". */
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final RestClient restClient;
    private final StationDirectory stationDirectory;
    private final XmlMapper xmlMapper;
    private final IrishRailProperties properties;

    /**
     * Was a {@code ConcurrentHashMap} emptied wholesale once it passed 400 entries, which threw
     * away every warm route to make room for one. Caffeine evicts the least useful entry instead,
     * and the entries live past their freshness window so {@link #getRoute} can still serve a
     * slightly old route when the upstream call fails.
     */
    private final Cache<String, Cached> cache;

    public TrainRouteService(RestClient irishRailRestClient,
                             StationDirectory stationDirectory,
                             XmlMapper irishRailXmlMapper,
                             IrishRailProperties properties) {
        this.restClient = irishRailRestClient;
        this.stationDirectory = stationDirectory;
        this.xmlMapper = irishRailXmlMapper;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.api().maxCachedRoutes())
                .expireAfterWrite(Duration.ofMillis(properties.api().routeCacheMs() * 4))
                .build();
    }

    private record Cached(TrainRoute route, long loadedAtMs) {}

    public TrainRoute getRoute(String trainCode, String trainDate) {
        if (trainCode == null || trainCode.isBlank()) return TrainRoute.empty(trainCode, trainDate);

        String code = trainCode.trim().toUpperCase();
        String date = (trainDate == null || trainDate.isBlank())
                ? LocalDate.now().format(API_DATE)
                : trainDate.trim();

        String key = code + "|" + date;
        Cached cached = cache.getIfPresent(key);
        if (cached != null && System.currentTimeMillis() - cached.loadedAtMs() <= properties.api().routeCacheMs()) {
            return cached.route();
        }

        TrainRoute route = fetchRoute(code, date);
        if (route != null) {
            cache.put(key, new Cached(route, System.currentTimeMillis()));
            return route;
        }
        return cached != null ? cached.route() : TrainRoute.empty(code, date);
    }

    private TrainRoute fetchRoute(String trainCode, String trainDate) {
        try {
            String url = UriComponentsBuilder.fromUriString(properties.api().trainMovementsUrl())
                    .queryParam("TrainId", trainCode)
                    .queryParam("TrainDate", trainDate)
                    .build()
                    .toUriString();

            // Already encoded by the builder, so pass a URI: a String would be re-read as a
            // URI template and any brace in a train code treated as a placeholder.
            String xml = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
            if (xml == null || xml.isBlank()) return null;

            TrainMovementList list = xmlMapper.readValue(xml, TrainMovementList.class);
            List<TrainMovement> movements = list.getMovements();
            if (movements == null || movements.isEmpty()) {
                return TrainRoute.empty(trainCode, trainDate);
            }
            return buildRoute(trainCode, trainDate, movements);
        } catch (Exception e) {
            log.error("Failed to fetch route for train {} on {}: {}", trainCode, trainDate, e.getMessage());
            return null;
        }
    }

    private TrainRoute buildRoute(String trainCode, String trainDate, List<TrainMovement> movements) {
        List<TrainMovement> ordered = movements.stream()
                .filter(m -> !m.isTimingPoint())
                .sorted(Comparator.comparing(m -> m.getLocationOrder() == null ? 0 : m.getLocationOrder()))
                .toList();

        List<TrainRoute.Stop> stops = new ArrayList<>(ordered.size());
        int nextIndex = -1;
        int reached = 0;

        for (TrainMovement movement : ordered) {
            Station station = stationDirectory.findByCodeWithCoordinates(movement.getLocationCode()).orElse(null);
            if (station == null) {
                log.debug("Route {}: no coordinates for {}", trainCode, movement.getLocationCode());
                continue;
            }

            boolean isReached = movement.hasBeenReached();
            if (isReached) reached++;
            if (nextIndex < 0 && movement.isNextStop()) nextIndex = stops.size();

            stops.add(new TrainRoute.Stop(
                    movement.getLocationOrder() == null ? stops.size() + 1 : movement.getLocationOrder(),
                    trim(movement.getLocationCode()),
                    blankTo(movement.getLocationFullName(), station.getStationDesc()),
                    station.getStationLatitude(),
                    station.getStationLongitude(),
                    trim(movement.getLocationType()),
                    shortTime(movement.getScheduledArrival()),
                    shortTime(movement.getScheduledDeparture()),
                    shortTime(movement.getExpectedArrival()),
                    shortTime(movement.getExpectedDeparture()),
                    shortTime(movement.getArrival()),
                    shortTime(movement.getDeparture()),
                    isReached,
                    movement.isNextStop(),
                    lateMinutes(movement)));
        }

        // When the feed has not filled in any actual times, fall back to the next-stop marker so the
        // travelled portion of the line is still drawn.
        if (reached == 0 && nextIndex > 0) reached = nextIndex;

        TrainMovement first = ordered.isEmpty() ? movements.get(0) : ordered.get(0);
        return new TrainRoute(
                trim(trainCode),
                trainDate,
                trim(first.getTrainOrigin()),
                trim(first.getTrainDestination()),
                List.copyOf(stops),
                reached,
                nextIndex);
    }

    /**
     * Expected minus scheduled at this stop. Arrival is preferred; the origin has no meaningful
     * arrival so its departure is used instead.
     */
    private Integer lateMinutes(TrainMovement movement) {
        Integer byArrival = diffMinutes(movement.getScheduledArrival(), movement.getExpectedArrival());
        if (byArrival != null) return byArrival;
        return diffMinutes(movement.getScheduledDeparture(), movement.getExpectedDeparture());
    }

    private Integer diffMinutes(String scheduled, String expected) {
        LocalTime a = parseTime(scheduled);
        LocalTime b = parseTime(expected);
        if (a == null || b == null) return null;
        // Irish Rail uses 00:00:00 as "not applicable" rather than midnight.
        if (a.equals(LocalTime.MIDNIGHT) || b.equals(LocalTime.MIDNIGHT)) return null;

        int minutes = (b.toSecondOfDay() - a.toSecondOfDay()) / 60;
        // A run crossing midnight would otherwise read as ~1440 minutes early or late.
        if (minutes > 720) minutes -= 1440;
        if (minutes < -720) minutes += 1440;
        return minutes;
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = value.trim().split(":");
            if (parts.length < 2) return null;
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int second = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return LocalTime.of(hour % 24, minute, second);
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortTime(String value) {
        LocalTime time = parseTime(value);
        if (time == null) return null;
        if (time.equals(LocalTime.MIDNIGHT)) return null;
        return time.format(HH_MM);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankTo(String value, String fallback) {
        String trimmed = trim(value);
        return (trimmed == null || trimmed.isEmpty()) ? fallback : trimmed;
    }
}
