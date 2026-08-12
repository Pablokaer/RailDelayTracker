package com.irishrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * One row of {@code getTrainMovementsXML}: a single scheduled point on a train's run.
 *
 * <p>{@link #locationType} is the field that matters for mapping — {@code T} marks signalling and
 * timing points such as {@code SUBJN} or {@code DC336}, which have no name and no coordinates and
 * must be filtered out. {@code O}/{@code S}/{@code D} are origin, intermediate stop and destination.
 *
 * <p>{@link #arrival} and {@link #departure} are the <em>actual</em> times and arrive empty until the
 * train has really passed, which is what makes them a reliable progress signal.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "objTrainMovements")
public class TrainMovement {

    @JacksonXmlProperty(localName = "TrainCode")
    private String trainCode;

    @JacksonXmlProperty(localName = "TrainDate")
    private String trainDate;

    @JacksonXmlProperty(localName = "LocationCode")
    private String locationCode;

    @JacksonXmlProperty(localName = "LocationFullName")
    private String locationFullName;

    @JacksonXmlProperty(localName = "LocationOrder")
    private Integer locationOrder;

    /** {@code O} origin, {@code S} stop, {@code T} timing point, {@code D} destination. */
    @JacksonXmlProperty(localName = "LocationType")
    private String locationType;

    @JacksonXmlProperty(localName = "TrainOrigin")
    private String trainOrigin;

    @JacksonXmlProperty(localName = "TrainDestination")
    private String trainDestination;

    @JacksonXmlProperty(localName = "ScheduledArrival")
    private String scheduledArrival;

    @JacksonXmlProperty(localName = "ScheduledDeparture")
    private String scheduledDeparture;

    @JacksonXmlProperty(localName = "ExpectedArrival")
    private String expectedArrival;

    @JacksonXmlProperty(localName = "ExpectedDeparture")
    private String expectedDeparture;

    @JacksonXmlProperty(localName = "Arrival")
    private String arrival;

    @JacksonXmlProperty(localName = "Departure")
    private String departure;

    /** {@code C} current, {@code N} next, {@code -} otherwise. */
    @JacksonXmlProperty(localName = "StopType")
    private String stopType;

    public String getTrainCode() { return trainCode; }
    public void setTrainCode(String v) { this.trainCode = v; }

    public String getTrainDate() { return trainDate; }
    public void setTrainDate(String v) { this.trainDate = v; }

    public String getLocationCode() { return locationCode; }
    public void setLocationCode(String v) { this.locationCode = v; }

    public String getLocationFullName() { return locationFullName; }
    public void setLocationFullName(String v) { this.locationFullName = v; }

    public Integer getLocationOrder() { return locationOrder; }
    public void setLocationOrder(Integer v) { this.locationOrder = v; }

    public String getLocationType() { return locationType; }
    public void setLocationType(String v) { this.locationType = v; }

    public String getTrainOrigin() { return trainOrigin; }
    public void setTrainOrigin(String v) { this.trainOrigin = v; }

    public String getTrainDestination() { return trainDestination; }
    public void setTrainDestination(String v) { this.trainDestination = v; }

    public String getScheduledArrival() { return scheduledArrival; }
    public void setScheduledArrival(String v) { this.scheduledArrival = v; }

    public String getScheduledDeparture() { return scheduledDeparture; }
    public void setScheduledDeparture(String v) { this.scheduledDeparture = v; }

    public String getExpectedArrival() { return expectedArrival; }
    public void setExpectedArrival(String v) { this.expectedArrival = v; }

    public String getExpectedDeparture() { return expectedDeparture; }
    public void setExpectedDeparture(String v) { this.expectedDeparture = v; }

    public String getArrival() { return arrival; }
    public void setArrival(String v) { this.arrival = v; }

    public String getDeparture() { return departure; }
    public void setDeparture(String v) { this.departure = v; }

    public String getStopType() { return stopType; }
    public void setStopType(String v) { this.stopType = v; }

    /** True for signalling and timing points, which are not passenger stops. */
    public boolean isTimingPoint() {
        return locationType != null && "T".equalsIgnoreCase(locationType.trim());
    }

    /** True once the train has actually been recorded at this location. */
    public boolean hasBeenReached() {
        return notBlank(arrival) || notBlank(departure);
    }

    public boolean isNextStop() {
        return stopType != null && "N".equalsIgnoreCase(stopType.trim());
    }

    public boolean isCurrentStop() {
        return stopType != null && "C".equalsIgnoreCase(stopType.trim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
