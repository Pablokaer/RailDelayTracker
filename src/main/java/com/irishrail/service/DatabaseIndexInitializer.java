package com.irishrail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseIndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Indexes were 73% of trip_station_snapshot's 268 MB — they cost more than the data.
        // Every one below is justified by a query that actually uses it.

        // Date-ranged rollups and the recent-delays feeds.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_tss_captured_station_trip
                ON trip_station_snapshot (captured_at, station_code, trip_id)
                """);
        // Per-trip peak delay lookups.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_tss_trip_late_captured
                ON trip_station_snapshot (trip_id, late_minutes, captured_at)
                """);
        // "Most recent delays" ordering.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_tss_late_station_captured
                ON trip_station_snapshot (late_minutes, station_code, captured_at DESC)
                """);
        // Per-train history matches on the trimmed code, because Irish Rail pads it.
        execute("""
                CREATE INDEX IF NOT EXISTS idx_trip_train_code_trimmed
                ON trip (UPPER(TRIM(train_code)))
                """);

        dropUnusedIndexes();
    }

    /**
     * These three recorded zero scans over 59 days of accumulated statistics while costing 96 MB
     * and slowing every insert. They became redundant once the analytics moved onto the daily
     * aggregate tables. Dropping an index is reversible — it is rebuilt from this class on the next
     * start if a query ever needs it again.
     */
    private void dropUnusedIndexes() {
        for (String index : new String[] {
                "idx_tss_scope_captured_station",
                "idx_tss_station_captured_trip",
                "idx_tss_scope_late_captured" }) {
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
