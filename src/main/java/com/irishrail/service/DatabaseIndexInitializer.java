package com.irishrail.service;

import com.irishrail.model.DelayLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DatabaseIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseIndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Indexes were 58% of trip_station_snapshot's footprint (104 MB of index for 75 MB of
        // rows). Every one below is justified by a query that actually uses it, and each is the
        // smallest structure that serves that query.

        // Time-range scans: the retention sweep and the startup backfill. Rows are appended in
        // captured_at order, which is the textbook case for BRIN — a few kB where the B-tree it
        // replaces (captured_at, station_code, trip_id) cost 26 MB.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_tss_captured_brin
                ON trip_station_snapshot USING brin (captured_at)
                """);
        // Per-trip lookups: peak-delay history, and the incremental roll-up, which selects by the
        // trip ids one collection cycle touched.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_tss_trip_late_captured
                ON trip_station_snapshot (trip_id, late_minutes, captured_at)
                """);
        // "Most recent delays" ordering. Partial: the feed only ever reads delayed rows, and most
        // snapshots are of trains on time. The repository inlines the same constant so the planner
        // can match the predicate.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_tss_delayed_station_captured
                ON trip_station_snapshot (late_minutes, station_code, captured_at DESC)
                WHERE late_minutes >= """ + DelayLimits.DELAYED_THRESHOLD_MINUTES);
        // Per-train history matches on the trimmed code, because Irish Rail pads it.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_trip_train_code_trimmed
                ON trip (UPPER(TRIM(train_code)))
                """);

        // The table is append-only, so the default 10% analyze threshold leaves the planner's
        // statistics hours behind the current day — exactly the range every live query filters
        // on. Measured: estimated 16 rows against 2 799 actual, which is what kept the roll-up on a
        // nested loop. 1% keeps the estimate honest at a negligible cost.
        execute("ALTER TABLE trip_station_snapshot SET (autovacuum_analyze_scale_factor = 0.01)");

        dropUnusedIndexes();
    }

    /**
     * Dropping an index is reversible — it is rebuilt from this class on the next start if a query
     * ever needs it again.
     *
     * <ul>
     *   <li>The first three recorded zero scans over 59 days while costing 96 MB.</li>
     *   <li>{@code idx_tss_captured_station_trip} is replaced by the BRIN index above.</li>
     *   <li>{@code idx_tss_late_station_captured} is replaced by its partial counterpart.</li>
     * </ul>
     */
    private void dropUnusedIndexes() {
        for (String index : new String[] {
                "idx_tss_scope_captured_station",
                "idx_tss_station_captured_trip",
                "idx_tss_scope_late_captured",
                "idx_tss_captured_station_trip",
                "idx_tss_late_station_captured" }) {
            execute("DROP INDEX IF EXISTS " + index);
        }
    }

    private void execute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.warn("Could not apply index change [{}]: {}",
                    sql.strip().lines().findFirst().orElse(sql), e.getMessage());
        }
    }
}
