package com.irishrail.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "train_delay_records")
public class TrainDelayRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String trainCode;
    private String trainType;
    private String stationCode;
    private String stationFullName;
    private String origin;
    private String destination;
    private String direction;
    private String schDepart;
    private String expDepart;
    private String schArrival;
    private String expArrival;
    private int lateMinutes;
    private String status;
    private String trainDate;
    private LocalDateTime capturedAt;

    public TrainDelayRecord() {}

    public static TrainDelayRecord from(TrainInfo t) {
        TrainDelayRecord r = new TrainDelayRecord();
        r.trainCode     = t.getTrainCode();
        r.trainType     = t.getTrainType();
        r.stationCode   = t.getStationCode();
        r.stationFullName = t.getStationFullName();
        r.origin        = t.getOrigin();
        r.destination   = t.getDestination();
        r.direction     = t.getDirection();
        r.schDepart     = t.getSchDepart();
        r.expDepart     = t.getExpDepart();
        r.schArrival    = t.getSchArrival();
        r.expArrival    = t.getExpArrival();
        r.lateMinutes   = t.getLate();
        r.status        = t.getStatus();
        r.trainDate     = t.getTrainDate();
        r.capturedAt    = LocalDateTime.now();
        return r;
    }

    public Long getId() { return id; }
    public String getTrainCode() { return trainCode; }
    public void setTrainCode(String v) { this.trainCode = v; }
    public String getTrainType() { return trainType; }
    public void setTrainType(String v) { this.trainType = v; }
    public String getStationCode() { return stationCode; }
    public void setStationCode(String v) { this.stationCode = v; }
    public String getStationFullName() { return stationFullName; }
    public void setStationFullName(String v) { this.stationFullName = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public String getDestination() { return destination; }
    public void setDestination(String v) { this.destination = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public String getSchDepart() { return schDepart; }
    public void setSchDepart(String v) { this.schDepart = v; }
    public String getExpDepart() { return expDepart; }
    public void setExpDepart(String v) { this.expDepart = v; }
    public String getSchArrival() { return schArrival; }
    public void setSchArrival(String v) { this.schArrival = v; }
    public String getExpArrival() { return expArrival; }
    public void setExpArrival(String v) { this.expArrival = v; }
    public int getLateMinutes() { return lateMinutes; }
    public void setLateMinutes(int v) { this.lateMinutes = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getTrainDate() { return trainDate; }
    public void setTrainDate(String v) { this.trainDate = v; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime v) { this.capturedAt = v; }
}
