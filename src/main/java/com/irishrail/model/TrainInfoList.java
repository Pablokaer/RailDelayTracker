package com.irishrail.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

@JacksonXmlRootElement(localName = "ArrayOfObjStationData")
public class TrainInfoList {

    @JacksonXmlProperty(localName = "objStationData")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<TrainInfo> trains;

    public List<TrainInfo> getTrains() { return trains; }
    public void setTrains(List<TrainInfo> trains) { this.trains = trains; }
}
