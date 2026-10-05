package com.irishrail.model;

import com.irishrail.service.TrainPositionService;

import java.util.List;

/**
 * Answer of {@code /api/train-positions}: the live snapshot the map polls.
 *
 * <p>Component names are the wire format {@code map.js} reads; this replaces a hand-built
 * {@code LinkedHashMap<String, Object>}.
 *
 * @param stale true when the last upstream refresh failed and the positions below are the previous
 *              snapshot rather than current data
 */
public record TrainPositionsView(
        String capturedAt,
        boolean stale,
        int count,
        long runningCount,
        long delayedCount,
        List<LiveTrain> positions) {

    public static TrainPositionsView of(TrainPositionService.Snapshot snapshot) {
        List<LiveTrain> trains = snapshot.trains();
        return new TrainPositionsView(
                snapshot.capturedAt() == null ? null : snapshot.capturedAt().toString(),
                snapshot.failing(),
                trains.size(),
                trains.stream().filter(LiveTrain::running).count(),
                trains.stream()
                        .filter(t -> t.lateMinutes() != null
                                && t.lateMinutes() >= DelayCategory.delayedThreshold())
                        .count(),
                trains);
    }
}
