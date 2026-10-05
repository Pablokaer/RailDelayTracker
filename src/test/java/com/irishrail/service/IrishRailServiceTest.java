package com.irishrail.service;

import com.irishrail.model.Station;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrishRailServiceTest {

    private static Station station(String code, String desc, int id) {
        return station(code, desc, id, 53.33 + id, -6.4);
    }

    private static Station station(String code, String desc, int id, double lat, double lon) {
        Station s = new Station();
        s.setStationCode(code);
        s.setStationDesc(desc);
        s.setStationId(id);
        s.setStationLatitude(lat);
        s.setStationLongitude(lon);
        return s;
    }

    @Test
    void collapsesPlatformVariantsOntoTheBaseCode() {
        // Real shape of the Kildare-line entries: base N, fast 900+N, slow 1000+N.
        List<Station> result = IrishRailService.dedupe(List.of(
                station("ADAMS", "Adamstown", 1075),
                station("ADAMF", "Adamstown", 975),
                station("ADMTN", "Adamstown", 75),
                station("PWESF", "Park West and Cherry Orchard", 976),
                station("CHORC", "Park West and Cherry Orchard", 76)));

        assertEquals(2, result.size());
        assertEquals("ADMTN", result.get(0).getStationCode());
        assertEquals("CHORC", result.get(1).getStationCode());
    }

    @Test
    void collapsesSamePlaceUnderADifferentName() {
        // PWESS is listed as "PARK WEST" but sits exactly on Park West and Cherry Orchard.
        List<Station> result = IrishRailService.dedupe(List.of(
                station("PWESS", "PARK WEST", 1076, 53.334, -6.37868),
                station("CHORC", "Park West and Cherry Orchard", 76, 53.334, -6.37868),
                station("CLDKN", "Clondalkin", 77, 53.3334, -6.40628)));

        assertEquals(2, result.size());
        assertEquals("CHORC", result.get(0).getStationCode());
    }

    @Test
    void invalidCoordinatesNeverGroupStations() {
        // Two unrelated stations both carrying a junk position must both survive.
        List<Station> result = IrishRailService.dedupe(List.of(
                station("AAA", "Alpha", 1, 0, 0),
                station("BBB", "Beta", 2, 0, 0)));

        assertEquals(2, result.size());
    }

    @Test
    void keepsDistinctStationsAndDropsExactCodeRepeats() {
        List<Station> result = IrishRailService.dedupe(List.of(
                station("CNLLY", "Dublin Connolly", 1),
                station("cnlly", "Dublin Connolly", 1),
                station("HSTON", "Dublin Heuston", 2),
                station("PERSE", "Dublin Pearse", 3)));

        assertEquals(3, result.size());
    }

    @Test
    void nameMatchingIgnoresCaseAccentsAndStationSuffix() {
        List<Station> result = IrishRailService.dedupe(List.of(
                station("AAA", "Céad Station", 10),
                station("BBB", "cead", 20)));

        assertEquals(1, result.size());
        assertEquals("AAA", result.get(0).getStationCode());
    }
}
