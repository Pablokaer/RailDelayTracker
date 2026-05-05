package com.irishrail.model;

public class HourlyStats {
    private int hour;
    private String hourLabel;
    private long totalCount;
    private long delayCount;
    private double avgDelay;
    private double delayProbability;

    public HourlyStats(int hour, long totalCount, long delayCount, double avgDelay) {
        this.hour             = hour;
        this.hourLabel        = String.format("%02d:00", hour);
        this.totalCount       = totalCount;
        this.delayCount       = delayCount;
        this.avgDelay         = Math.round(avgDelay * 10.0) / 10.0;
        this.delayProbability = totalCount > 0
                ? Math.round(delayCount * 1000.0 / totalCount) / 10.0
                : 0.0;
    }

    public int getHour()                { return hour; }
    public String getHourLabel()        { return hourLabel; }
    public long getTotalCount()         { return totalCount; }
    public long getDelayCount()         { return delayCount; }
    public double getAvgDelay()         { return avgDelay; }
    public double getDelayProbability() { return delayProbability; }
}
