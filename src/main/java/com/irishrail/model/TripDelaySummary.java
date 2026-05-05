package com.irishrail.model;

public class TripDelaySummary {
    private String trainCode;
    private String stationFullName;
    private String trainDate;
    private String schDepart;
    private String schArrival;
    private String direction;
    private String origin;
    private String destination;
    private int peakDelayMinutes;
    private long snapshotCount;

    public TripDelaySummary(String trainCode, String stationFullName,
                             String trainDate, String schDepart, String schArrival,
                             String direction, String origin, String destination,
                             int peakDelayMinutes, long snapshotCount) {
        this.trainCode        = trainCode;
        this.stationFullName  = stationFullName;
        this.trainDate        = trainDate;
        this.schDepart        = schDepart;
        this.schArrival       = schArrival;
        this.direction        = direction;
        this.origin           = origin;
        this.destination      = destination;
        this.peakDelayMinutes = peakDelayMinutes;
        this.snapshotCount    = snapshotCount;
    }

    public String getTrainCode()        { return trainCode; }
    public String getStationFullName()  { return stationFullName; }
    public String getTrainDate()        { return trainDate; }
    public String getSchDepart()        { return schDepart; }
    public String getSchArrival()       { return schArrival; }
    public String getDirection()        { return direction; }
    public String getOrigin()           { return origin; }
    public String getDestination()      { return destination; }
    public int getPeakDelayMinutes()    { return peakDelayMinutes; }
    public long getSnapshotCount()      { return snapshotCount; }
}
