package com.irishrail.model;

public class StationStats {
    private String stationCode;
    private String stationName;
    private long totalTrips;
    private long delayedTrips;
    private double averageDelay;
    private double delayProbability;
    private long totalAccumulatedDelay;

    public StationStats(String stationCode, String stationName,
                        long totalTrips, long delayedTrips, double averageDelay,
                        long totalAccumulatedDelay) {
        this.stationCode           = stationCode;
        this.stationName           = stationName;
        this.totalTrips            = totalTrips;
        this.delayedTrips          = delayedTrips;
        this.averageDelay          = Math.round(averageDelay * 10.0) / 10.0;
        this.delayProbability      = totalTrips > 0
                ? Math.round(delayedTrips * 1000.0 / totalTrips) / 10.0
                : 0.0;
        this.totalAccumulatedDelay = totalAccumulatedDelay;
    }

    public String getDelayProbabilityBarColor() {
        if (delayProbability < 20) return "#3ecf73";
        if (delayProbability < 40) return "#f59e0b";
        return "#f87171";
    }

    public String getStationCode()           { return stationCode; }
    public String getStationName()           { return stationName; }
    public long getTotalTrips()              { return totalTrips; }
    public long getDelayedTrips()            { return delayedTrips; }
    public double getAverageDelay()          { return averageDelay; }
    public double getDelayProbability()      { return delayProbability; }
    public long getTotalAccumulatedDelay()   { return totalAccumulatedDelay; }
}
