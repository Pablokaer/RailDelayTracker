package com.irishrail.model;

public class TrainDelaySummary {
    private String trainCode;
    private long delayCount;
    private double avgDelayMinutes;
    private int maxDelayMinutes;

    public TrainDelaySummary(String trainCode, long delayCount,
                              double avgDelayMinutes, int maxDelayMinutes) {
        this.trainCode       = trainCode;
        this.delayCount      = delayCount;
        this.avgDelayMinutes = Math.round(avgDelayMinutes * 10.0) / 10.0;
        this.maxDelayMinutes = maxDelayMinutes;
    }

    public String getTrainCode() { return trainCode; }
    public long getDelayCount() { return delayCount; }
    public double getAvgDelayMinutes() { return avgDelayMinutes; }
    public int getMaxDelayMinutes() { return maxDelayMinutes; }
}
