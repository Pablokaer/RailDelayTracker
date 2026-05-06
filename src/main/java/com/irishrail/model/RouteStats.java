package com.irishrail.model;

public class RouteStats {
    private String origin;
    private String destination;
    private long totalTrips;
    private long delayedTrips;
    private double averageDelay;
    private double delayProbability;

    private long totalAccumulatedDelay;

    public RouteStats(String origin, String destination,
                      long totalTrips, long delayedTrips, double averageDelay,
                      long totalAccumulatedDelay) {
        this.origin                = origin;
        this.destination           = destination;
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

    public String getOrigin()                  { return origin; }
    public String getDestination()             { return destination; }
    public long getTotalTrips()                { return totalTrips; }
    public long getDelayedTrips()              { return delayedTrips; }
    public double getAverageDelay()            { return averageDelay; }
    public double getDelayProbability()        { return delayProbability; }
    public long getTotalAccumulatedDelay()     { return totalAccumulatedDelay; }
}
