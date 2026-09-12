package com.irishrail.service;

import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.Station;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Name → {@link Station} index over the <em>full</em> Irish Rail station list
 * ({@code StationType=A}, 171 stations).
 *
 * <p>Resolving destinations against the D/M/S lists used for delay collection silently failed for
 * anything off those routes — Sligo, Galway, Belfast, Cobh, Midleton and Maynooth are all absent
 * from them — which is why so many map markers had no destination to point at.
 *
 * <p>Matching is exact on a normalised key rather than substring-based: {@code contains} in both
 * directions made "Ennis" match "Enniscorthy" and let trailing junk in a badly parsed destination
 * match an unrelated station.
 */
@Service
public class StationDirectory {

    private static final Logger log = LoggerFactory.getLogger(StationDirectory.class);

    private final IrishRailService irishRailService;
    private final IrishRailProperties properties;

    /**
     * Guards the refresh, which makes a blocking HTTP call.
     *
     * <p>This was a {@code synchronized (this)} block. Two reasons it is a lock now: a virtual
     * thread that blocks inside {@code synchronized} pins its carrier for the whole upstream call,
     * and {@code tryLock} lets a second caller return the previous snapshot immediately instead of
     * queueing behind a refresh whose result it is about to read anyway.
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of(), Map.of(), 0L);

    public StationDirectory(IrishRailService irishRailService, IrishRailProperties properties) {
        this.irishRailService = irishRailService;
        this.properties = properties;
    }

    private record Snapshot(List<Station> stations, Map<String, Station> byName,
                            Map<String, Station> byCode, long loadedAtMs) {}

    /** All known stations, sorted by name. Empty only if the API has never answered. */
    public List<Station> all() {
        return current().stations();
    }

    /** Resolves a destination or stop name coming from a public message to a real station. */
    public Optional<Station> findByName(String name) {
        String key = normalize(name);
        if (key.isEmpty()) return Optional.empty();
        return Optional.ofNullable(current().byName().get(key));
    }

    /** Coordinates for a station name, when the station has a usable position. */
    public Optional<Station> findWithCoordinates(String name) {
        return findByName(name).filter(StationDirectory::hasValidCoordinates);
    }

    /**
     * Resolves an Irish Rail station code such as {@code CNLLY}. Train movement rows identify
     * locations by code rather than by name, so route drawing needs this index.
     */
    public Optional<Station> findByCode(String stationCode) {
        if (stationCode == null || stationCode.isBlank()) return Optional.empty();
        return Optional.ofNullable(current().byCode().get(stationCode.trim().toUpperCase()));
    }

    public Optional<Station> findByCodeWithCoordinates(String stationCode) {
        return findByCode(stationCode).filter(StationDirectory::hasValidCoordinates);
    }

    /**
     * Whether this code names a real station on the network.
     *
     * <p>Used to reject unknown codes at the edge rather than forwarding them upstream and caching
     * a result under them. When the directory has never loaded (the API has never answered) this
     * cannot tell, and says yes rather than making every station look invalid — the caller's
     * syntactic check still applies.
     */
    public boolean isKnownCode(String stationCode) {
        if (stationCode == null || stationCode.isBlank()) return false;
        Snapshot local = current();
        if (local.byCode().isEmpty()) return true;
        return local.byCode().containsKey(stationCode.trim().toUpperCase());
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${irishrail.api.station-cache-ms:3600000}")
    public void refresh() {
        List<Station> stations = irishRailService.getAllStations();
        if (stations.isEmpty()) {
            log.warn("Station directory refresh returned no stations; keeping {} cached entries",
                    snapshot.stations().size());
            return;
        }
        Map<String, Station> byCode = new HashMap<>();
        for (Station station : stations) {
            if (station.getStationCode() != null && !station.getStationCode().isBlank()) {
                byCode.putIfAbsent(station.getStationCode().trim().toUpperCase(), station);
            }
        }

        snapshot = new Snapshot(List.copyOf(stations), buildIndex(stations),
                Collections.unmodifiableMap(byCode), System.currentTimeMillis());
        log.info("Station directory loaded: {} stations, {} name keys, {} codes",
                stations.size(), snapshot.byName().size(), snapshot.byCode().size());
    }

    private Snapshot current() {
        Snapshot local = snapshot;
        boolean expired = local.loadedAtMs() > 0
                && System.currentTimeMillis() - local.loadedAtMs() > properties.api().stationCacheMs();
        if (local.loadedAtMs() != 0L && !expired) return local;

        boolean neverLoaded = local.loadedAtMs() == 0L;
        if (neverLoaded) {
            // Nothing to serve yet, so this one has to wait for the fetch.
            refreshLock.lock();
            try {
                if (snapshot == local) refresh();
            } finally {
                refreshLock.unlock();
            }
        } else if (refreshLock.tryLock()) {
            try {
                if (snapshot == local) refresh();
            } finally {
                refreshLock.unlock();
            }
        }
        return snapshot;
    }

    private static Map<String, Station> buildIndex(List<Station> stations) {
        List<Station> sorted = new ArrayList<>(stations);
        // Deterministic order so a key claimed by two stations always resolves the same way.
        sorted.sort((a, b) -> String.valueOf(a.getStationDesc())
                .compareToIgnoreCase(String.valueOf(b.getStationDesc())));

        Map<String, Station> index = new HashMap<>();
        for (Station station : sorted) {
            for (String key : keysFor(station)) {
                index.putIfAbsent(key, station);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static List<String> keysFor(Station station) {
        List<String> keys = new ArrayList<>(6);
        addKey(keys, station.getStationDesc());
        addKey(keys, station.getStationAlias());

        // "Dublin Connolly" is announced as "Connolly" about as often as its full name.
        String desc = normalize(station.getStationDesc());
        if (desc.startsWith("dublin ")) addKey(keys, desc.substring(7));

        // "Celbridge (Hazelhatch)" should also answer to plain "Celbridge".
        for (String raw : new String[] { station.getStationDesc(), station.getStationAlias() }) {
            if (raw == null) continue;
            int paren = raw.indexOf('(');
            if (paren > 0) addKey(keys, raw.substring(0, paren));
        }
        return keys;
    }

    private static void addKey(List<String> keys, String value) {
        String key = normalize(value);
        if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
    }

    static String normalize(String value) {
        if (value == null) return "";
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents
                .toLowerCase()
                .replaceAll("[^a-z0-9]", " ")
                .replaceAll("\\bstation\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean hasValidCoordinates(Station station) {
        return station.getStationLatitude() >= 51.0 && station.getStationLatitude() <= 56.5
                && station.getStationLongitude() >= -11.0 && station.getStationLongitude() <= -5.0;
    }
}
