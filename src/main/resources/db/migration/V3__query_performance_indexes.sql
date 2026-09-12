-- Query-performance structures, moved out of DatabaseIndexInitializer (an ApplicationRunner that
-- issued this DDL on every boot and swallowed the failures).
--
-- Indexes were 58% of trip_station_snapshot's footprint (104 MB of index for 75 MB of rows).
-- Every one below is justified by a query that actually uses it, and each is the smallest
-- structure that serves that query.

-- Time-range scans: the retention sweep and the startup backfill. Rows are appended in
-- captured_at order, which is the textbook case for BRIN — a few kB where the B-tree it replaced
-- (captured_at, station_code, trip_id) cost 26 MB.
CREATE INDEX IF NOT EXISTS idx_tss_captured_brin
    ON trip_station_snapshot USING brin (captured_at);

-- Per-trip lookups: peak-delay history, and the incremental roll-up, which selects by the trip ids
-- one collection cycle touched.
CREATE INDEX IF NOT EXISTS idx_tss_trip_late_captured
    ON trip_station_snapshot (trip_id, late_minutes, captured_at);

-- "Most recent delays" ordering. Partial: the feed only ever reads delayed rows, and most
-- snapshots are of trains on time.
--
-- The literal 5 is DelayLimits.DELAYED_THRESHOLD_MINUTES. A migration is immutable, so it cannot
-- interpolate the constant the way the old ApplicationRunner did; DelayLimitsMigrationGuardTest
-- fails the build if the two ever drift apart, at which point this index needs a new migration.
-- TripStationSnapshotRepository inlines the same literal so the planner can match the predicate.
CREATE INDEX IF NOT EXISTS idx_tss_delayed_station_captured
    ON trip_station_snapshot (late_minutes, station_code, captured_at DESC)
    WHERE late_minutes >= 5;

-- Per-train history matches on the trimmed code, because Irish Rail pads it ("A408 ").
CREATE INDEX IF NOT EXISTS idx_trip_train_code_trimmed
    ON trip (UPPER(TRIM(train_code)));

-- trip_station_snapshot is append-only, so the default 10% analyze threshold leaves the planner's
-- statistics hours behind the current day — exactly the range every live query filters on.
-- Measured: estimated 16 rows against 2 799 actual, which is what kept the roll-up on a nested
-- loop. 1% keeps the estimate honest at a negligible cost.
ALTER TABLE trip_station_snapshot SET (autovacuum_analyze_scale_factor = 0.01);

-- Dropping an index is reversible: add a new migration if a query ever needs one of these again.
-- The first three recorded zero scans over 59 days while costing 96 MB; the last two are replaced
-- by idx_tss_captured_brin and idx_tss_delayed_station_captured above.
DROP INDEX IF EXISTS idx_tss_scope_captured_station;
DROP INDEX IF EXISTS idx_tss_station_captured_trip;
DROP INDEX IF EXISTS idx_tss_scope_late_captured;
DROP INDEX IF EXISTS idx_tss_captured_station_trip;
DROP INDEX IF EXISTS idx_tss_late_station_captured;
