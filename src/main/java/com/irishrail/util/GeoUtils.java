package com.irishrail.util;

/** Great-circle helpers for turning pairs of coordinates into a heading the map can draw. */
public final class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private GeoUtils() {}

    /**
     * Initial bearing from one point to another, in degrees clockwise from true north.
     * Returns null when either point is missing or the two points are identical.
     */
    public static Double bearing(Double fromLat, Double fromLon, Double toLat, Double toLon) {
        if (fromLat == null || fromLon == null || toLat == null || toLon == null) return null;
        if (fromLat.equals(toLat) && fromLon.equals(toLon)) return null;

        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double deltaLon = Math.toRadians(toLon - fromLon);

        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360d) % 360d;
    }

    /** Haversine distance in metres, or null when either point is missing. */
    public static Double distanceMeters(Double fromLat, Double fromLon, Double toLat, Double toLon) {
        if (fromLat == null || fromLon == null || toLat == null || toLon == null) return null;

        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(toLon - fromLon);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    /**
     * Compass bearing for an Irish Rail {@code Direction} value. The field carries either a
     * compass word ("Northbound") or a destination ("To Cork"); only the former yields an angle,
     * so destinations return null instead of a misleading default.
     */
    public static Double compassBearing(String direction) {
        if (direction == null) return null;
        String value = direction.toLowerCase();
        if (value.contains("north") && value.contains("west")) return 315d;
        if (value.contains("north") && value.contains("east")) return 45d;
        if (value.contains("south") && value.contains("west")) return 225d;
        if (value.contains("south") && value.contains("east")) return 135d;
        if (value.contains("north")) return 0d;
        if (value.contains("east")) return 90d;
        if (value.contains("south")) return 180d;
        if (value.contains("west")) return 270d;
        return null;
    }
}
