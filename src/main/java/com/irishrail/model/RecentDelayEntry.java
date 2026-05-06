package com.irishrail.model;

public class RecentDelayEntry {
    private String trainCode;
    private String stationFullName;
    private int lateMinutes;
    private String capturedAt;
    private String origin;
    private String destination;

    public RecentDelayEntry(String trainCode, String stationFullName, int lateMinutes,
                             String capturedAt, String origin, String destination) {
        this.trainCode       = trainCode;
        this.stationFullName = stationFullName;
        this.lateMinutes     = lateMinutes;
        this.capturedAt      = capturedAt;
        this.origin          = origin;
        this.destination     = destination;
    }

    public String getTrainCode()       { return trainCode; }
    public String getStationFullName() { return stationFullName; }
    public int getLateMinutes()        { return lateMinutes; }
    public String getCapturedAt()      { return capturedAt; }
    public String getOrigin()          { return origin; }
    public String getDestination()     { return destination; }
}
