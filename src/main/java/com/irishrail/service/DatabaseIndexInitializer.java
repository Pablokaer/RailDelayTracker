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
        createIndex("""
                CREATE INDEX IF NOT EXISTS idx_tss_station_captured_trip
                ON trip_station_snapshot (station_code, captured_at, trip_id)
                """);
        createIndex("""
                CREATE INDEX IF NOT EXISTS idx_tss_captured_station_trip
                ON trip_station_snapshot (captured_at, station_code, trip_id)
                """);
        createIndex("""
                CREATE INDEX IF NOT EXISTS idx_tss_trip_late_captured
                ON trip_station_snapshot (trip_id, late_minutes, captured_at)
                """);
        createIndex("""
                CREATE INDEX IF NOT EXISTS idx_tss_late_station_captured
                ON trip_station_snapshot (late_minutes, station_code, captured_at DESC)
                """);
        createIndex("""
                CREATE INDEX IF NOT EXISTS idx_tss_scope_captured_station
                ON trip_station_snapshot (service_scope, captured_at, station_code)
                """);
        createIndex("""
                CREATE INDEX IF NOT EXISTS idx_tss_scope_late_captured
                ON trip_station_snapshot (service_scope, late_minutes, captured_at DESC)
                """);
    }

    private void createIndex(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.warn("Could not create analytics index: {}", e.getMessage());
        }
    }
}
