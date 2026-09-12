package com.irishrail.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.DelayCategory;
import com.irishrail.model.LiveTrain;
import com.irishrail.model.Station;
import com.irishrail.model.TrainPosition;
import com.irishrail.model.TrainPositionList;
import com.irishrail.util.GeoUtils;
import com.irishrail.util.PublicMessageParser;
import com.irishrail.util.PublicMessageParser.ParsedMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns live train positions: one scheduled fetch feeds every client.
 *
 * <p>Previously each request checked a 10 s TTL and fetched upstream on a miss, so N clients
 * polling every 10 s produced N upstream calls per window, and a failing API meant every single
 * request retried it. Here a timer refreshes an immutable snapshot and requests only ever read
 * memory.
 *
 * <p>The heading is the part the map depends on. It resolves in order of trustworthiness:
 * actual movement since the last capture, then the bearing to the next stop, then to the final
 * destination, then the compass word in {@code Direction}. When none apply the heading is null and
 * the map draws a plain dot instead of an arrow pointing somewhere invented.
 */
@Service
public class TrainPositionService {

    private static final Logger log = LoggerFactory.getLogger(TrainPositionService.class);

    /** Below this the GPS jitter of a stationary train would produce a random heading. */
    private static final double MIN_MOVEMENT_METERS = 60d;

    /**
     * Upstream refreshes coordinates roughly once a minute, so most captures show a train at the
     * exact position it had 10 s ago. A movement heading is kept for this long rather than being
     * replaced by the straight line to the next stop, which on a curved route points elsewhere.
     */
    private static final long MOVEMENT_HEADING_TTL_MS = 180_000L;

    private final RestClient restClient;
    private final StationDirectory stationDirectory;
    private final XmlMapper xmlMapper;
    private final IrishRailProperties properties;
    private final MeterRegistry meters;

    private volatile Snapshot snapshot = Snapshot.empty();
    private final Map<String, TrackPoint> lastSeenPositions = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public TrainPositionService(RestClient irishRailRestClient,
                                StationDirectory stationDirectory,
                                XmlMapper irishRailXmlMapper,
                                IrishRailProperties properties,
                                MeterRegistry meters) {
        this.restClient = irishRailRestClient;
        this.stationDirectory = stationDirectory;
        this.xmlMapper = irishRailXmlMapper;
        this.properties = properties;
        this.meters = meters;
        meters.gauge("irishrail.positions.tracked", lastSeenPositions, Map::size);
    }

    /**
     * @param headingAtMs when {@code heading} was established, so a movement heading can expire
     */
    private record TrackPoint(double latitude, double longitude,
                              Double heading, String headingSource, long headingAtMs) {}

    /**
     * @param capturedAt when the upstream data was fetched, null before the first success
     * @param failing    true when the last refresh attempt failed and the data below is stale
     */
    public record Snapshot(List<LiveTrain> trains, Instant capturedAt, boolean failing) {
        static Snapshot empty() { return new Snapshot(List.of(), null, false); }
    }

    public Snapshot getSnapshot() {
        Snapshot local = snapshot;
        if (local.capturedAt() == null) {
            // Nothing cached yet (first request may land before the first timer tick).
            refresh();
            return snapshot;
        }
        return local;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${irishrail.api.train-positions-refresh-ms:10000}")
    public void refresh() {
        // Single-flight: a slow upstream must not stack up refreshes behind it.
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            List<TrainPosition> raw = fetchPositions();
            if (raw == null) {
                snapshot = new Snapshot(snapshot.trains(), snapshot.capturedAt(), true);
                return;
            }
            List<LiveTrain> trains = new ArrayList<>(raw.size());
            for (TrainPosition position : raw) {
                if (!position.hasValidCoordinates()) continue;
                trains.add(toLiveTrain(position));
            }
            pruneVanishedTrains(trains);
            snapshot = new Snapshot(List.copyOf(trains), Instant.now(), false);
        } finally {
            refreshing.set(false);
        }
    }

    private List<TrainPosition> fetchPositions() {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            String xml = restClient.get()
                    .uri(URI.create(properties.api().currentTrainsUrl()))
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank()) {
                log.warn("Train positions: empty response from Irish Rail");
                return null;
            }
            TrainPositionList list = xmlMapper.readValue(xml, TrainPositionList.class);
            List<TrainPosition> trains = list.getTrains();
            outcome = "success";
            return trains == null ? List.of() : trains;
        } catch (Exception e) {
            log.error("Train positions: fetch failed ({})", e.getMessage());
            return null;
        } finally {
            sample.stop(Timer.builder("irishrail.upstream")
                    .tag("endpoint", "train-positions")
                    .tag("outcome", outcome)
                    .register(meters));
        }
    }

    private LiveTrain toLiveTrain(TrainPosition position) {
        ParsedMessage parsed = PublicMessageParser.parse(position.getPublicMessage());
        double latitude = position.getLatitude();
        double longitude = position.getLongitude();

        Station nextStop = parsed.nextStop() == null
                ? null
                : stationDirectory.findWithCoordinates(parsed.nextStop()).orElse(null);
        Station destination = parsed.destination() == null
                ? null
                : stationDirectory.findWithCoordinates(parsed.destination()).orElse(null);

        Heading heading = resolveHeading(position, latitude, longitude, nextStop, destination);
        Station target = nextStop != null ? nextStop : destination;

        DelayCategory category = LiveTrain.categoryFor(parsed.lateMinutes());
        String status = position.getTrainStatus();

        return new LiveTrain(
                position.getTrainCode() == null ? null : position.getTrainCode().trim(),
                position.getTrainDate(),
                status,
                LiveTrain.statusLabel(status),
                LiveTrain.isRunning(status),
                latitude,
                longitude,
                position.getDirection(),
                parsed.lateMinutes(),
                LiveTrain.delayLabel(parsed.lateMinutes()),
                category.getTextColor(),
                category.getBgColor(),
                parsed.origin(),
                destination != null ? destination.getStationDesc() : parsed.destination(),
                nextStop != null ? nextStop.getStationDesc() : parsed.nextStop(),
                parsed.lastLocation(),
                parsed.scheduledDeparture(),
                parsed.expectedDeparture(),
                parsed.text(),
                heading.degrees(),
                heading.source(),
                target != null ? target.getStationLatitude() : null,
                target != null ? target.getStationLongitude() : null);
    }

    private record Heading(Double degrees, String source) {
        static final Heading NONE = new Heading(null, "unknown");
    }

    private Heading resolveHeading(TrainPosition position, double latitude, double longitude,
                                   Station nextStop, Station destination) {

        String key = trackKey(position);
        TrackPoint previous = key == null ? null : lastSeenPositions.get(key);
        long now = System.currentTimeMillis();

        Heading resolved = Heading.NONE;
        long headingAtMs = now;

        // 1. Where the train actually went since we last saw it.
        if (previous != null) {
            Double moved = GeoUtils.distanceMeters(previous.latitude(), previous.longitude(), latitude, longitude);
            if (moved != null && moved >= MIN_MOVEMENT_METERS) {
                Double degrees = GeoUtils.bearing(previous.latitude(), previous.longitude(), latitude, longitude);
                if (degrees != null) resolved = new Heading(degrees, "movement");
            }
        }

        // 2. A recent movement heading still beats any inferred one.
        if (resolved.degrees() == null
                && previous != null
                && "movement".equals(previous.headingSource())
                && previous.heading() != null
                && now - previous.headingAtMs() <= MOVEMENT_HEADING_TTL_MS) {
            resolved = new Heading(previous.heading(), "movement");
            headingAtMs = previous.headingAtMs();
        }

        // 3. Straight line to the next stop, then to the final destination.
        if (resolved.degrees() == null && nextStop != null) {
            Double degrees = GeoUtils.bearing(latitude, longitude,
                    nextStop.getStationLatitude(), nextStop.getStationLongitude());
            if (degrees != null) resolved = new Heading(degrees, "next-stop");
        }

        if (resolved.degrees() == null && destination != null) {
            Double degrees = GeoUtils.bearing(latitude, longitude,
                    destination.getStationLatitude(), destination.getStationLongitude());
            if (degrees != null) resolved = new Heading(degrees, "destination");
        }

        // 4. The compass word in Direction, when there is one.
        if (resolved.degrees() == null) {
            Double degrees = GeoUtils.compassBearing(position.getDirection());
            if (degrees != null) resolved = new Heading(degrees, "compass");
        }

        // 5. Whatever we knew before, rather than dropping the arrow entirely.
        if (resolved.degrees() == null && previous != null && previous.heading() != null) {
            resolved = new Heading(previous.heading(), "previous");
            headingAtMs = previous.headingAtMs();
        }

        if (key != null) {
            lastSeenPositions.put(key, new TrackPoint(
                    latitude, longitude, resolved.degrees(), resolved.source(), headingAtMs));
        }

        return resolved;
    }

    /** Keeps {@link #lastSeenPositions} from growing without bound as services come and go. */
    private void pruneVanishedTrains(List<LiveTrain> trains) {
        if (lastSeenPositions.size() <= trains.size() * 3 + 50) return;
        List<String> live = trains.stream().map(t -> t.trainCode() + "|" + t.trainDate()).toList();
        lastSeenPositions.keySet().retainAll(live);
    }

    private static String trackKey(TrainPosition position) {
        if (position.getTrainCode() == null || position.getTrainCode().isBlank()) return null;
        return position.getTrainCode().trim() + "|" + Optional.ofNullable(position.getTrainDate()).orElse("");
    }
}
