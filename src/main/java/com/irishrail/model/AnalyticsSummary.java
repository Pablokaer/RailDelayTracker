package com.irishrail.model;

import java.util.Map;

public class AnalyticsSummary {
    private long totalRecords;
    private long totalDelays;
    private double avgDelayMinutes;
    private String mostDelayedStation;
    private String mostDelayedTrain;
    private Map<String, Long> distribution;

    public AnalyticsSummary(long totalRecords, long totalDelays, double avgDelayMinutes,
                             String mostDelayedStation, String mostDelayedTrain,
                             Map<String, Long> distribution) {
        this.totalRecords      = totalRecords;
        this.totalDelays       = totalDelays;
        this.avgDelayMinutes   = Math.round(avgDelayMinutes * 10.0) / 10.0;
        this.mostDelayedStation = mostDelayedStation;
        this.mostDelayedTrain  = mostDelayedTrain;
        this.distribution      = distribution;
    }

    public long getTotalRecords() { return totalRecords; }
    public long getTotalDelays() { return totalDelays; }
    public double getAvgDelayMinutes() { return avgDelayMinutes; }
    public String getMostDelayedStation() { return mostDelayedStation; }
    public String getMostDelayedTrain() { return mostDelayedTrain; }
    public Map<String, Long> getDistribution() { return distribution; }
}
