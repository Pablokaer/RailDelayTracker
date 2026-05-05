package com.irishrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "objStationData")
public class TrainInfo {

    @JacksonXmlProperty(localName = "Traincode")
    private String trainCode;

    @JacksonXmlProperty(localName = "Stationfullname")
    private String stationFullName;

    @JacksonXmlProperty(localName = "Stationcode")
    private String stationCode;

    @JacksonXmlProperty(localName = "Querytime")
    private String queryTime;

    @JacksonXmlProperty(localName = "Traindate")
    private String trainDate;

    @JacksonXmlProperty(localName = "Origin")
    private String origin;

    @JacksonXmlProperty(localName = "Destination")
    private String destination;

    @JacksonXmlProperty(localName = "Origintime")
    private String originTime;

    @JacksonXmlProperty(localName = "Destinationtime")
    private String destinationTime;

    @JacksonXmlProperty(localName = "Status")
    private String status;

    @JacksonXmlProperty(localName = "Lastlocation")
    private String lastLocation;

    @JacksonXmlProperty(localName = "Duein")
    private int dueIn;

    @JacksonXmlProperty(localName = "Late")
    private int late;

    @JacksonXmlProperty(localName = "Exparrival")
    private String expArrival;

    @JacksonXmlProperty(localName = "Expdepart")
    private String expDepart;

    @JacksonXmlProperty(localName = "Scharrival")
    private String schArrival;

    @JacksonXmlProperty(localName = "Schdepart")
    private String schDepart;

    @JacksonXmlProperty(localName = "Direction")
    private String direction;

    @JacksonXmlProperty(localName = "Traintype")
    private String trainType;

    @JacksonXmlProperty(localName = "Locationtype")
    private String locationType;

    public String getTrainCode() { return trainCode; }
    public void setTrainCode(String trainCode) { this.trainCode = trainCode; }

    public String getStationFullName() { return stationFullName; }
    public void setStationFullName(String stationFullName) { this.stationFullName = stationFullName; }

    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }

    public String getQueryTime() { return queryTime; }
    public void setQueryTime(String queryTime) { this.queryTime = queryTime; }

    public String getTrainDate() { return trainDate; }
    public void setTrainDate(String trainDate) { this.trainDate = trainDate; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getOriginTime() { return originTime; }
    public void setOriginTime(String originTime) { this.originTime = originTime; }

    public String getDestinationTime() { return destinationTime; }
    public void setDestinationTime(String destinationTime) { this.destinationTime = destinationTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastLocation() { return lastLocation; }
    public void setLastLocation(String lastLocation) { this.lastLocation = lastLocation; }

    public int getDueIn() { return dueIn; }
    public void setDueIn(int dueIn) { this.dueIn = dueIn; }

    public int getLate() { return late; }
    public void setLate(int late) { this.late = late; }

    public String getExpArrival() { return expArrival; }
    public void setExpArrival(String expArrival) { this.expArrival = expArrival; }

    public String getExpDepart() { return expDepart; }
    public void setExpDepart(String expDepart) { this.expDepart = expDepart; }

    public String getSchArrival() { return schArrival; }
    public void setSchArrival(String schArrival) { this.schArrival = schArrival; }

    public String getSchDepart() { return schDepart; }
    public void setSchDepart(String schDepart) { this.schDepart = schDepart; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getTrainType() { return trainType; }
    public void setTrainType(String trainType) { this.trainType = trainType; }

    public String getLocationType() { return locationType; }
    public void setLocationType(String locationType) { this.locationType = locationType; }

    public boolean isLate() {
        return late >= 5;
    }

    public DelayCategory getDelayCategory() {
        return DelayCategory.of(late);
    }

    public String getDueInDisplay() {
        if (dueIn == 0) return "A chegar";
        return dueIn + " min";
    }
}
