package com.irishrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "objStation")
public class Station {

    @JacksonXmlProperty(localName = "StationDesc")
    private String stationDesc;

    @JacksonXmlProperty(localName = "StationAlias")
    private String stationAlias;

    @JacksonXmlProperty(localName = "StationLatitude")
    private double stationLatitude;

    @JacksonXmlProperty(localName = "StationLongitude")
    private double stationLongitude;

    @JacksonXmlProperty(localName = "StationCode")
    private String stationCode;

    @JacksonXmlProperty(localName = "StationId")
    private int stationId;

    public String getStationDesc() { return stationDesc; }
    public void setStationDesc(String stationDesc) { this.stationDesc = stationDesc; }

    public String getStationAlias() { return stationAlias; }
    public void setStationAlias(String stationAlias) { this.stationAlias = stationAlias; }

    public double getStationLatitude() { return stationLatitude; }
    public void setStationLatitude(double stationLatitude) { this.stationLatitude = stationLatitude; }

    public double getStationLongitude() { return stationLongitude; }
    public void setStationLongitude(double stationLongitude) { this.stationLongitude = stationLongitude; }

    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }

    public int getStationId() { return stationId; }
    public void setStationId(int stationId) { this.stationId = stationId; }

    public String getDisplayName() {
        return (stationAlias != null && !stationAlias.isBlank()) ? stationAlias : stationDesc;
    }
}
