package com.irishrail.model;

public class DestinationStats {
    private String destination;
    private long totalCount;
    private long delayCount;
    private double avgDelay;
    private double delayProbability;

    public DestinationStats(String destination, long totalCount,
                             long delayCount, double avgDelay) {
        this.destination      = destination;
        this.totalCount       = totalCount;
        this.delayCount       = delayCount;
        this.avgDelay         = Math.round(avgDelay * 10.0) / 10.0;
        this.delayProbability = totalCount > 0
                ? Math.round(delayCount * 1000.0 / totalCount) / 10.0
                : 0.0;
    }

    public String getDestination()      { return destination; }
    public long getTotalCount()         { return totalCount; }
    public long getDelayCount()         { return delayCount; }
    public double getAvgDelay()         { return avgDelay; }
    public double getDelayProbability() { return delayProbability; }
}
