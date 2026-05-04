package com.irishrail.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

@JacksonXmlRootElement(localName = "ArrayOfObjStation")
public class StationList {

    @JacksonXmlProperty(localName = "objStation")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<Station> stations;

    public List<Station> getStations() { return stations; }
    public void setStations(List<Station> stations) { this.stations = stations; }
}
