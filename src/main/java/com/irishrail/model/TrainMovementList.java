package com.irishrail.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

@JacksonXmlRootElement(localName = "ArrayOfObjTrainMovements")
public class TrainMovementList {

    @JacksonXmlProperty(localName = "objTrainMovements")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<TrainMovement> movements;

    public List<TrainMovement> getMovements() { return movements; }
    public void setMovements(List<TrainMovement> movements) { this.movements = movements; }
}
