-- ═══════════════════════════════════════════════════════════════════════════
-- One-off space reclamation for RailDelayTracker
--
-- WHY THIS IS MANUAL: every statement below takes an ACCESS EXCLUSIVE lock or
-- drops an object. Run it in a maintenance window, with the app stopped, and
-- read each section before executing it. Nothing here is reversible.
--
--   psql -h localhost -U postgres -d irishrail -f reclaim-space.sql
--
-- Background: DELETE in Postgres marks rows dead and frees the space for reuse
-- by future inserts — it does NOT return it to the operating system. Lowering
-- irishrail.retention.days stops the database growing, but the file stays the
-- size it had already reached. Only VACUUM FULL (or pg_repack) shrinks it.
-- ═══════════════════════════════════════════════════════════════════════════

\timing on

-- ─── 1. Where the space is going ──────────────────────────────────────────
-- Run this first and keep the output, so you can compare afterwards.

SELECT relname AS table_name,
       n_live_tup AS live_rows,
       n_dead_tup AS dead_rows,
       pg_size_pretty(pg_total_relation_size(relid)) AS total,
       pg_size_pretty(pg_relation_size(relid))       AS heap,
       pg_size_pretty(pg_indexes_size(relid))        AS indexes,
       last_autovacuum
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;


-- ─── 2. Drop the orphaned tables ──────────────────────────────────────────
-- train_snapshot     : no @Entity maps to it. Left behind by an older schema —
--                      ddl-auto=update creates and alters tables but never drops.
-- train_delay_records: TrainDelayRecord exists but TrainDelayRepository is not
--                      injected anywhere, so nothing reads or writes it. 0 rows,
--                      7.5 MB of allocated space.
--
-- SAFETY CHECK — both must report 0 before you drop them:

SELECT 'train_snapshot' AS table_name, count(*) AS rows FROM train_snapshot
UNION ALL
SELECT 'train_delay_records', count(*) FROM train_delay_records;

-- Uncomment to drop:
-- DROP TABLE IF EXISTS train_snapshot;
-- DROP TABLE IF EXISTS train_delay_records;


-- ─── 3. Reclaim the snapshot table ────────────────────────────────────────
-- VACUUM FULL rewrites the table and its indexes into fresh files, returning
-- the freed space to the filesystem.
--
-- COST: an ACCESS EXCLUSIVE lock for the whole rewrite — the app cannot read or
-- write the table meanwhile — and it needs temporary disk space roughly equal to
-- the final table size. Expect a few minutes at ~650k rows.
--
-- If you cannot take the downtime, use pg_repack instead, which does the same
-- job online:  pg_repack -h localhost -U postgres -d irishrail -t trip_station_snapshot

VACUUM FULL VERBOSE ANALYZE trip_station_snapshot;
VACUUM FULL VERBOSE ANALYZE trip;


-- ─── 4. Keep autovacuum on top of the daily delete ────────────────────────
-- last_autovacuum was NULL on trip_station_snapshot, meaning autovacuum had
-- never processed it. With the nightly retention delete removing ~120k rows a
-- day, dead tuples need to be reclaimed continuously or the table bloats again.
-- The defaults scale with table size (20%), which is too lax here.

ALTER TABLE trip_station_snapshot SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_vacuum_cost_delay = 2
);

ALTER TABLE daily_trip_metrics SET (
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.05
);


-- ─── 5. Confirm ───────────────────────────────────────────────────────────

SELECT relname AS table_name,
       n_live_tup AS live_rows,
       pg_size_pretty(pg_total_relation_size(relid)) AS total,
       pg_size_pretty(pg_relation_size(relid))       AS heap,
       pg_size_pretty(pg_indexes_size(relid))        AS indexes
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- Aggregate coverage should extend past the raw window — that is the whole point.
SELECT 'raw snapshots' AS source,
       min(captured_at)::date AS oldest,
       max(captured_at)::date AS newest
FROM trip_station_snapshot
UNION ALL
SELECT 'daily_trip_metrics', min(service_date), max(service_date) FROM daily_trip_metrics
UNION ALL
SELECT 'daily_hourly_metrics', min(service_date), max(service_date) FROM daily_hourly_metrics
UNION ALL
SELECT 'daily_station_route_metrics', min(service_date), max(service_date) FROM daily_station_route_metrics;
