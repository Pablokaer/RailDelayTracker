package com.irishrail.model;

public class DashboardSummary {
    private long totalSnapshots;   // raw rows in DB
    private long uniqueTrips;      // distinct (trainCode, stationCode, trainDate, schDepart)
    private long delayedTrips;     // trips where peak lateMinutes > 0
    private long onTimeTrips;      // uniqueTrips - delayedTrips
    private double averageDelay;   // avg peak delay for delayed trips only
    private double delayProbability; // delayedTrips / uniqueTrips * 100
    private int maxDelay;

    public DashboardSummary(long totalSnapshots, long uniqueTrips, long delayedTrips,
                             double averageDelay, int maxDelay) {
        this.totalSnapshots    = totalSnapshots;
        this.uniqueTrips       = uniqueTrips;
        this.delayedTrips      = delayedTrips;
        this.onTimeTrips       = uniqueTrips - delayedTrips;
        this.averageDelay      = Math.round(averageDelay * 10.0) / 10.0;
        this.delayProbability  = uniqueTrips > 0
                ? Math.round(delayedTrips * 1000.0 / uniqueTrips) / 10.0
                : 0.0;
        this.maxDelay = maxDelay;
    }

    public String getDelayProbabilityColor() {
        if (delayProbability < 20) return "#3ecf73";
        if (delayProbability < 40) return "#f59e0b";
        return "#f87171";
    }

    public long getTotalSnapshots()     { return totalSnapshots; }
    public long getUniqueTrips()        { return uniqueTrips; }
    public long getDelayedTrips()       { return delayedTrips; }
    public long getOnTimeTrips()        { return onTimeTrips; }
    public double getAverageDelay()     { return averageDelay; }
    public double getDelayProbability() { return delayProbability; }
    public int getMaxDelay()            { return maxDelay; }
}
