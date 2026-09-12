package com.irishrail.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bands used to be spelled out by hand in the aggregate SQL as well as declared here. Editing
 * the enum then changed the labels shown in the UI without changing the numbers stored underneath
 * them — and the aggregate tables are durable, so the mismatch outlived the raw data.
 */
class DelayCategorySqlTest {

    @Test
    void boundedBandsBecomeARangeTest() {
        assertThat(DelayCategory.MEDIUM_DELAY.countSql("peak_delay"))
                .isEqualTo("SUM(CASE WHEN peak_delay BETWEEN 10 AND 19 THEN 1 ELSE 0 END)");
    }

    @Test
    void theOpenEndedBandBecomesAGreaterOrEqualTest() {
        assertThat(DelayCategory.EXTREME_DELAY.countSql("peak_delay"))
                .isEqualTo("SUM(CASE WHEN peak_delay >= 40 THEN 1 ELSE 0 END)");
    }

    @Test
    void everyBandMapsToItsAggregateColumn() {
        assertThat(DelayCategory.delayedBands())
                .extracting(DelayCategory::aggregateColumn)
                .containsExactly("small_delay_trips", "medium_delay_trips",
                        "big_delay_trips", "extreme_delay_trips");
    }

    @Test
    void delayedBandsExcludeOnTimeAndStayInAscendingOrder() {
        assertThat(DelayCategory.delayedBands()).doesNotContain(DelayCategory.ON_TIME);
        assertThat(DelayCategory.delayedBands())
                .extracting(DelayCategory::getMinMinutes)
                .isSorted();
    }

    @Test
    void theFirstDelayedBandStartsAtTheSharedThreshold() {
        assertThat(DelayCategory.delayedBands().get(0).getMinMinutes())
                .isEqualTo(DelayCategory.delayedThreshold())
                .isEqualTo(DelayLimits.DELAYED_THRESHOLD_MINUTES);
    }

    /**
     * {@code V3__query_performance_indexes.sql} builds a partial index
     * {@code WHERE late_minutes >= 5} and {@code TripStationSnapshotRepository} inlines the same
     * literal so the planner can match it. A migration is immutable and cannot read the constant,
     * so this is the thing that notices if the constant moves: changing it means writing a new
     * migration that rebuilds the index, then updating the number here.
     */
    @Test
    void theDelayedThresholdStillMatchesTheOneBuiltIntoTheIndexMigration() {
        assertThat(DelayLimits.DELAYED_THRESHOLD_MINUTES)
                .as("V3__query_performance_indexes.sql hardcodes 'WHERE late_minutes >= 5'")
                .isEqualTo(5);
    }
}
