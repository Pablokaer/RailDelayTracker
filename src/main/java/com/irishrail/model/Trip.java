package com.irishrail.model;

import jakarta.persistence.*;

@Entity
@Table(name = "trip",
       uniqueConstraints = @UniqueConstraint(columnNames = {"train_code", "train_date"}))
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String trainCode;
    private String trainDate;
    private String trainType;
    private String origin;
    private String destination;
    private String direction;

    public Trip() {}

    public Long getId() { return id; }
    public String getTrainCode() { return trainCode; }
    public void setTrainCode(String v) { this.trainCode = v; }
    public String getTrainDate() { return trainDate; }
    public void setTrainDate(String v) { this.trainDate = v; }
    public String getTrainType() { return trainType; }
    public void setTrainType(String v) { this.trainType = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public String getDestination() { return destination; }
    public void setDestination(String v) { this.destination = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
}
