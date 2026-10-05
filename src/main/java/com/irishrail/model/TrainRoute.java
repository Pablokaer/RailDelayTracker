package com.irishrail.model;

import java.util.List;

/**
 * A train's whole run as the map draws it: the ordered passenger stops with coordinates, split into
 * the part already travelled and the part still ahead.
 *
 * @param stops         in schedule order, timing points removed
 * @param reachedCount  how many stops the train has already been recorded at
 * @param nextStopIndex index into {@link #stops} of the next stop, or -1 when unknown
 */
public record TrainRoute(
        String trainCode,
        String trainDate,
        String origin,
        String destination,
        List<Stop> stops,
        int reachedCount,
        int nextStopIndex) {

    /**
     * @param kind        {@code O} origin, {@code S} stop, {@code D} destination
     * @param reached     the train has actually been recorded here
     * @param next        this is the next scheduled stop
     * @param lateMinutes expected minus scheduled, in minutes; null when either is unknown
     */
    public record Stop(
            int order,
            String code,
            String name,
            double latitude,
            double longitude,
            String kind,
            String scheduledArrival,
            String scheduledDeparture,
            String expectedArrival,
            String expectedDeparture,
            String actualArrival,
            String actualDeparture,
            boolean reached,
            boolean next,
            Integer lateMinutes) {}

    public static TrainRoute empty(String trainCode, String trainDate) {
        return new TrainRoute(trainCode, trainDate, null, null, List.of(), 0, -1);
    }
}
