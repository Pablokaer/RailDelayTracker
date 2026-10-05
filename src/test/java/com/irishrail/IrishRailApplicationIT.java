package com.irishrail;

import com.irishrail.config.IrishRailProperties;
import com.irishrail.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the application starts at all.
 *
 * <p>This class existed but was empty — no annotation, no test method — so nothing ever loaded the
 * Spring context. It could not have: {@code @EnableScheduling} sat on the application class, so a
 * context meant a live collector hitting the Irish Rail API.
 *
 * <p>Starting the context is not a formality here. It runs the Flyway migrations and then makes
 * Hibernate validate the entities against the resulting schema, which is the only check that the
 * migrations and the {@code @Entity} classes still describe the same tables.
 */
class IrishRailApplicationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IrishRailProperties properties;

    @Test
    void contextLoadsAndSchemaValidates() {
        assertThat(properties.collector().threads()).isPositive();
    }

    @Test
    void migrationsCreateEveryTableTheApplicationReads() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "trip",
                "trip_station_snapshot",
                "daily_station_route_metrics",
                "daily_trip_metrics",
                "daily_hourly_metrics");
    }

    @Test
    void migrationsCreateTheIndexesTheHotQueriesDependOn() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains(
                // The roll-up's per-trip lookup and the retention sweep's range scan.
                "idx_tss_trip_late_captured",
                "idx_tss_captured_brin",
                // The partial index behind the "recent delays" feed.
                "idx_tss_delayed_station_captured",
                // Per-train history matches on the trimmed code.
                "idx_trip_train_code_trimmed");
    }

    @Test
    void migrationsAreRecordedSoTheyDoNotRunTwice() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isPositive();
    }
}
