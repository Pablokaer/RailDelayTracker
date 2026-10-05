package com.irishrail.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

@JacksonXmlRootElement(localName = "ArrayOfObjTrainPositions")
public class TrainPositionList {

    @JacksonXmlProperty(localName = "objTrainPositions")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<TrainPosition> trains;

    public List<TrainPosition> getTrains() { return trains; }
    public void setTrains(List<TrainPosition> trains) { this.trains = trains; }
}
