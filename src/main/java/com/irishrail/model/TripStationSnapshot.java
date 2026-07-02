package com.irishrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trip_station_snapshot")
public class TripStationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Trip trip;

    private String stationCode;
    private String stationFullName;
    private String serviceScope;
    private String schDepart;
    private String schArrival;
    private String expDepart;
    private String expArrival;
    private int lateMinutes;
    private String status;
    private LocalDateTime capturedAt;

    public TripStationSnapshot() {}

    public Long getId() { return id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip v) { this.trip = v; }
    public String getStationCode() { return stationCode; }
    public void setStationCode(String v) { this.stationCode = v; }
    public String getStationFullName() { return stationFullName; }
    public void setStationFullName(String v) { this.stationFullName = v; }
    public String getServiceScope() { return serviceScope; }
    public void setServiceScope(String v) { this.serviceScope = v; }
    public String getSchDepart() { return schDepart; }
    public void setSchDepart(String v) { this.schDepart = v; }
    public String getSchArrival() { return schArrival; }
    public void setSchArrival(String v) { this.schArrival = v; }
    public String getExpDepart() { return expDepart; }
    public void setExpDepart(String v) { this.expDepart = v; }
    public String getExpArrival() { return expArrival; }
    public void setExpArrival(String v) { this.expArrival = v; }
    public int getLateMinutes() { return lateMinutes; }
    public void setLateMinutes(int v) { this.lateMinutes = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime v) { this.capturedAt = v; }
}
