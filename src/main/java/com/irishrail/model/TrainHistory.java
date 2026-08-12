package com.irishrail.model;

import java.util.List;

/**
 * Recorded delay history for one train code, so a marker on the live map can be traced back to
 * how that service has actually been performing.
 *
 * @param daysTracked how many distinct service dates we hold data for
 * @param avgDelay    mean recorded delay in minutes
 * @param maxDelay    worst recorded delay in minutes
 * @param delayedRate share of snapshots at or above the delay threshold, 0–100
 */
public record TrainHistory(
        String trainCode,
        long daysTracked,
        long snapshots,
        double avgDelay,
        int maxDelay,
        double delayedRate,
        List<Entry> recent) {

    /**
     * @param trainDate  Irish Rail service date, e.g. "12 Aug 2026"
     * @param capturedAt "HH:mm" of the capture
     */
    public record Entry(
            String trainDate,
            String stationName,
            String stationCode,
            String schDepart,
            int lateMinutes,
            String capturedAt,
            String delayColor) {}

    public static TrainHistory empty(String trainCode) {
        return new TrainHistory(trainCode, 0L, 0L, 0d, 0, 0d, List.of());
    }
}
