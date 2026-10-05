package com.irishrail.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoUtilsTest {

    private static final double DUBLIN_LAT = 53.3531, DUBLIN_LON = -6.2459;
    private static final double CORK_LAT   = 51.9018, CORK_LON   = -8.4582;

    @Test
    void bearingDueNorthIsZero() {
        assertEquals(0d, GeoUtils.bearing(53.0, -6.25, 54.0, -6.25), 0.001);
    }

    @Test
    void bearingDueEastIsNinety() {
        assertEquals(90d, GeoUtils.bearing(53.0, -7.0, 53.0, -6.0), 0.5);
    }

    @Test
    void bearingIsAlwaysInZeroToThreeSixty() {
        Double westward = GeoUtils.bearing(53.0, -6.0, 53.0, -7.0);
        assertEquals(270d, westward, 0.5);
        assertTrue(westward >= 0 && westward < 360);
    }

    @Test
    void dublinToCorkPointsSouthWest() {
        Double bearing = GeoUtils.bearing(DUBLIN_LAT, DUBLIN_LON, CORK_LAT, CORK_LON);
        assertTrue(bearing > 180 && bearing < 270, "expected south-west, got " + bearing);
    }

    @Test
    void identicalPointsHaveNoBearing() {
        assertNull(GeoUtils.bearing(53.0, -6.0, 53.0, -6.0));
    }

    @Test
    void missingCoordinatesYieldNull() {
        assertNull(GeoUtils.bearing(null, -6.0, 53.0, -6.0));
        assertNull(GeoUtils.bearing(53.0, null, 53.0, -6.0));
        assertNull(GeoUtils.distanceMeters(53.0, -6.0, null, -6.0));
    }

    @Test
    void distanceDublinToCorkIsAboutTwoTwentyKilometres() {
        Double metres = GeoUtils.distanceMeters(DUBLIN_LAT, DUBLIN_LON, CORK_LAT, CORK_LON);
        assertEquals(220_000d, metres, 15_000d);
    }

    @Test
    void distanceToSelfIsZero() {
        assertEquals(0d, GeoUtils.distanceMeters(53.0, -6.0, 53.0, -6.0), 0.001);
    }

    @Test
    void compassBearingReadsIrishRailDirectionWords() {
        assertEquals(0d, GeoUtils.compassBearing("Northbound"));
        assertEquals(180d, GeoUtils.compassBearing("Southbound"));
        assertEquals(90d, GeoUtils.compassBearing("Eastbound"));
        assertEquals(270d, GeoUtils.compassBearing("Westbound"));
    }

    /**
     * Over half of the {@code Direction} values are destinations, not compass words. Returning a
     * hard-coded 45° for those — as the old map did — drew arrows pointing at nothing.
     */
    @Test
    void destinationDirectionsHaveNoCompassBearing() {
        assertNull(GeoUtils.compassBearing("To Cork"));
        assertNull(GeoUtils.compassBearing("To Dublin Heuston"));
        assertNull(GeoUtils.compassBearing("To Maynooth"));
        assertNull(GeoUtils.compassBearing(null));
    }
}
