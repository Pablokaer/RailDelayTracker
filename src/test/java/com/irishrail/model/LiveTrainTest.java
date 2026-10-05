package com.irishrail.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveTrainTest {

    @Test
    void mapsIrishRailStatusCodes() {
        assertEquals("Running", LiveTrain.statusLabel("R"));
        assertEquals("Not yet running", LiveTrain.statusLabel("N"));
        assertEquals("Terminated", LiveTrain.statusLabel("T"));
        assertEquals("Unknown", LiveTrain.statusLabel(null));
    }

    @Test
    void onlyRunningTrainsCountAsRunning() {
        assertTrue(LiveTrain.isRunning("R"));
        assertFalse(LiveTrain.isRunning("N"));
        assertFalse(LiveTrain.isRunning("T"));
        assertFalse(LiveTrain.isRunning(null));
    }

    /**
     * {@link DelayCategory#of(int)} falls through to EXTREME_DELAY for negative input, so an early
     * train would otherwise be painted the same red as a 40-minute delay.
     */
    @Test
    void earlyTrainsAreNotCategorisedAsExtremeDelays() {
        assertEquals(DelayCategory.ON_TIME, LiveTrain.categoryFor(-6));
        assertEquals(DelayCategory.ON_TIME, LiveTrain.categoryFor(0));
        assertEquals(DelayCategory.ON_TIME, LiveTrain.categoryFor(null));
    }

    @Test
    void categorisesDelaysByMinutes() {
        assertEquals(DelayCategory.SMALL_DELAY, LiveTrain.categoryFor(6));
        assertEquals(DelayCategory.MEDIUM_DELAY, LiveTrain.categoryFor(12));
        assertEquals(DelayCategory.BIG_DELAY, LiveTrain.categoryFor(25));
        assertEquals(DelayCategory.EXTREME_DELAY, LiveTrain.categoryFor(90));
    }

    @Test
    void labelsReportEarlyOnTimeAndLate() {
        assertEquals("6 min early", LiveTrain.delayLabel(-6));
        assertEquals("On time", LiveTrain.delayLabel(0));
        assertEquals("On time", LiveTrain.delayLabel(4));
        assertEquals("+5 min", LiveTrain.delayLabel(5));
        assertEquals("+23 min", LiveTrain.delayLabel(23));
        assertEquals("Not reported", LiveTrain.delayLabel(null));
    }
}
