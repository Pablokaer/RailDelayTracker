package com.irishrail.model;

/**
 * A station as the map's station layer needs it. Replaces a per-station
 * {@code LinkedHashMap<String, Object>}; the component names are the wire format.
 */
public record StationPoint(String code, String name, double latitude, double longitude) {

    public static StationPoint of(Station station) {
        return new StationPoint(
                station.getStationCode(),
                station.getStationDesc(),
                station.getStationLatitude(),
                station.getStationLongitude());
    }
}
