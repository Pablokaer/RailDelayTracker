package com.irishrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * Raw {@code getCurrentTrainsXML} row. This is the wire format only — the API-facing shape the
 * map consumes is {@link LiveTrain}, built by
 * {@link com.irishrail.service.TrainPositionService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "objTrainPositions")
public class TrainPosition {

    /** {@code R} running, {@code N} not yet running, {@code T} terminated. */
    @JacksonXmlProperty(localName = "TrainStatus")
    private String trainStatus;

    @JacksonXmlProperty(localName = "TrainLatitude")
    private String trainLatitude;

    @JacksonXmlProperty(localName = "TrainLongitude")
    private String trainLongitude;

    @JacksonXmlProperty(localName = "TrainCode")
    private String trainCode;

    @JacksonXmlProperty(localName = "TrainDate")
    private String trainDate;

    @JacksonXmlProperty(localName = "PublicMessage")
    private String publicMessage;

    @JacksonXmlProperty(localName = "Direction")
    private String direction;

    public String getTrainStatus() { return trainStatus; }
    public void setTrainStatus(String trainStatus) { this.trainStatus = trainStatus; }

    public String getTrainLatitude() { return trainLatitude; }
    public void setTrainLatitude(String trainLatitude) { this.trainLatitude = trainLatitude; }

    public String getTrainLongitude() { return trainLongitude; }
    public void setTrainLongitude(String trainLongitude) { this.trainLongitude = trainLongitude; }

    public String getTrainCode() { return trainCode; }
    public void setTrainCode(String trainCode) { this.trainCode = trainCode; }

    public String getTrainDate() { return trainDate; }
    public void setTrainDate(String trainDate) { this.trainDate = trainDate; }

    public String getPublicMessage() { return publicMessage; }
    public void setPublicMessage(String publicMessage) { this.publicMessage = publicMessage; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public Double getLatitude() { return parseCoordinate(trainLatitude); }

    public Double getLongitude() { return parseCoordinate(trainLongitude); }

    /** Rejects the (0,0) rows the API emits for trains with no GPS fix. */
    public boolean hasValidCoordinates() {
        Double latitude = getLatitude();
        Double longitude = getLongitude();
        return latitude != null
                && longitude != null
                && latitude >= 51.0 && latitude <= 56.5
                && longitude >= -11.0 && longitude <= -5.0;
    }

    private Double parseCoordinate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
