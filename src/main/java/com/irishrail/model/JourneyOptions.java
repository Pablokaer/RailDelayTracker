package com.irishrail.model;

import java.util.List;

/**
 * Answer of {@code /api/journey-options}: the trains that call at the origin and later at the
 * destination.
 *
 * <p>Was assembled as a {@code LinkedHashMap<String, Object>} of {@code LinkedHashMap}s, so the
 * response shape existed only as string literals in the controller — nothing connected it to the
 * fields {@code journey.js} reads, and no rename could be checked by the compiler. The component
 * names below are that same wire format.
 */
public record JourneyOptions(
        String fromStationCode,
        String toStationCode,
        String updatedAt,
        int count,
        List<Option> options) {

    public record Option(
            String trainCode,
            String trainType,
            String origin,
            String destination,
            String direction,
            String status,
            int late,
            int dueIn,
            String schDepart,
            String expDepart,
            String schArrival,
            String expArrival,
            int destinationDueIn,
            String lastLocation) {

        /** @param departure the train as the origin board sees it, {@code arrival} as the destination does */
        public static Option of(TrainInfo departure, TrainInfo arrival) {
            return new Option(
                    departure.getTrainCode(),
                    departure.getTrainType(),
                    departure.getOrigin(),
                    departure.getDestination(),
                    departure.getDirection(),
                    departure.getStatus(),
                    departure.getLate(),
                    departure.getDueIn(),
                    departure.getSchDepart(),
                    departure.getExpDepart(),
                    arrival.getSchArrival(),
                    arrival.getExpArrival(),
                    arrival.getDueIn(),
                    departure.getLastLocation());
        }
    }
}
