package com.irishrail.model;

public class StationDelaySummary {
    private String stationCode;
    private String stationName;
    private long delayCount;
    private double avgDelayMinutes;
    private int maxDelayMinutes;

    public StationDelaySummary(String stationCode, String stationName,
                                long delayCount, double avgDelayMinutes, int maxDelayMinutes) {
        this.stationCode      = stationCode;
        this.stationName      = stationName;
        this.delayCount       = delayCount;
        this.avgDelayMinutes  = Math.round(avgDelayMinutes * 10.0) / 10.0;
        this.maxDelayMinutes  = maxDelayMinutes;
    }

    public String getStationCode() { return stationCode; }
    public String getStationName() { return stationName; }
    public long getDelayCount() { return delayCount; }
    public double getAvgDelayMinutes() { return avgDelayMinutes; }
    public int getMaxDelayMinutes() { return maxDelayMinutes; }
}
