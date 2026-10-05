package com.irishrail.model;

/**
 * A train position as the map consumes it: coordinates plus a resolved heading, the delay parsed
 * out of the public message, and the leg the train is currently running.
 *
 * @param status        raw Irish Rail status: {@code R} running, {@code N} not yet running, {@code T} terminated
 * @param statusLabel   human-readable form of {@code status}
 * @param running       true only for {@code R} — the trains actually moving right now
 * @param lateMinutes   minutes late, negative when early, null when the API did not report it
 * @param delayLabel    e.g. "On time", "+4 min", "6 min early"
 * @param delayColor    text colour from {@link DelayCategory}
 * @param delayBgColor  background colour from {@link DelayCategory}
 * @param heading       degrees clockwise from north, or null when it cannot be established
 * @param headingSource how {@code heading} was derived: movement / next-stop / destination / compass
 * @param targetLat     coordinates of {@code nextStop} when known, else of {@code destination}
 */
public record LiveTrain(
        String trainCode,
        String trainDate,
        String status,
        String statusLabel,
        boolean running,
        double latitude,
        double longitude,
        String direction,
        Integer lateMinutes,
        String delayLabel,
        String delayColor,
        String delayBgColor,
        String origin,
        String destination,
        String nextStop,
        String lastLocation,
        String scheduledDeparture,
        String expectedDeparture,
        String message,
        Double heading,
        String headingSource,
        Double targetLat,
        Double targetLon) {

    public static String statusLabel(String status) {
        if (status == null) return "Unknown";
        return switch (status.trim().toUpperCase()) {
            case "R" -> "Running";
            case "N" -> "Not yet running";
            case "T" -> "Terminated";
            default  -> status.trim();
        };
    }

    public static boolean isRunning(String status) {
        return status != null && "R".equalsIgnoreCase(status.trim());
    }

    /**
     * {@link DelayCategory#of(int)} only covers non-negative minutes, so early running is clamped
     * to on-time for colouring while {@link #delayLabel} still reports it as early.
     */
    public static DelayCategory categoryFor(Integer lateMinutes) {
        return DelayCategory.of(lateMinutes == null ? 0 : Math.max(0, lateMinutes));
    }

    public static String delayLabel(Integer lateMinutes) {
        if (lateMinutes == null) return "Not reported";
        if (lateMinutes < 0) return Math.abs(lateMinutes) + " min early";
        if (lateMinutes < DelayCategory.delayedThreshold()) return "On time";
        return "+" + lateMinutes + " min";
    }
}
