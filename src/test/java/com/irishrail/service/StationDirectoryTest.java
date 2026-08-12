package com.irishrail.service;

import com.irishrail.model.Station;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationDirectoryTest {

    @Test
    void normalisesCaseAccentsAndPunctuation() {
        assertEquals("dun laoghaire", StationDirectory.normalize("Dún Laoghaire"));
        assertEquals("park west and cherry orchard", StationDirectory.normalize("Park West and Cherry Orchard"));
        assertEquals("leixlip confey", StationDirectory.normalize("Leixlip (Confey)"));
        assertEquals("park west", StationDirectory.normalize("PARK WEST"));
    }

    @Test
    void dropsTheWordStationSoBothFormsMatch() {
        assertEquals(StationDirectory.normalize("Connolly"), StationDirectory.normalize("Connolly Station"));
    }

    @Test
    void blankInputNormalisesToEmpty() {
        assertEquals("", StationDirectory.normalize(null));
        assertEquals("", StationDirectory.normalize("   "));
    }

    /**
     * The old loose matching used {@code contains} in both directions, which is exactly how
     * "Ennis" could resolve to "Enniscorthy". Exact keys keep them distinct.
     */
    @Test
    void similarNamesProduceDistinctKeys() {
        assertFalse(StationDirectory.normalize("Ennis").equals(StationDirectory.normalize("Enniscorthy")));
        assertFalse(StationDirectory.normalize("Howth").equals(StationDirectory.normalize("Howth Junction")));
    }

    @Test
    void rejectsCoordinatesOutsideIreland() {
        assertTrue(StationDirectory.hasValidCoordinates(station(53.35, -6.25)));
        assertFalse(StationDirectory.hasValidCoordinates(station(0d, 0d)));
        assertFalse(StationDirectory.hasValidCoordinates(station(48.85, 2.35)));
    }

    private static Station station(double latitude, double longitude) {
        Station station = new Station();
        station.setStationCode("TEST");
        station.setStationDesc("Test");
        station.setStationLatitude(latitude);
        station.setStationLongitude(longitude);
        return station;
    }
}
