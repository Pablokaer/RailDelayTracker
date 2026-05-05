package com.irishrail.model;

public class StationStats {
    private String stationCode;
    private String stationName;
    private long totalTrips;       // distinct trips at this station
    private long delayedTrips;     // trips where peak lateMinutes > 0
    private double averageDelay;   // avg peak delay for delayed trips
    private double delayProbability;

    public StationStats(String stationCode, String stationName,
                        long totalTrips, long delayedTrips, double averageDelay) {
        this.stationCode      = stationCode;
        this.stationName      = stationName;
        this.totalTrips       = totalTrips;
        this.delayedTrips     = delayedTrips;
        this.averageDelay     = Math.round(averageDelay * 10.0) / 10.0;
        this.delayProbability = totalTrips > 0
                ? Math.round(delayedTrips * 1000.0 / totalTrips) / 10.0
                : 0.0;
    }

    public String getDelayProbabilityBarColor() {
        if (delayProbability < 20) return "#3ecf73";
        if (delayProbability < 40) return "#f59e0b";
        return "#f87171";
    }

    public String getStationCode()      { return stationCode; }
    public String getStationName()      { return stationName; }
    public long getTotalTrips()         { return totalTrips; }
    public long getDelayedTrips()       { return delayedTrips; }
    public double getAverageDelay()     { return averageDelay; }
    public double getDelayProbability() { return delayProbability; }
}
