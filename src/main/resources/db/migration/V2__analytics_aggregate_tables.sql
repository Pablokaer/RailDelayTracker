-- Daily analytics aggregates.
--
-- Moved out of AnalyticsAggregateService.ensureSchema(), which ran this DDL on every boot. The
-- Javadoc there already warned that "schema changes need care" precisely because the DDL lived in
-- application code; it now lives here, versioned.
--
-- These tables are durable and deliberately outlive the raw snapshots they were derived from:
-- ~440 kB/day against ~33 MB/day of raw. There is no DROP TABLE anywhere in this file.

CREATE TABLE IF NOT EXISTS daily_station_route_metrics (
    service_date          date             NOT NULL,
    service_scope         varchar(32)      NOT NULL,
    station_code          varchar(32)      NOT NULL,
    station_full_name     varchar(255),
    origin                varchar(255)     NOT NULL,
    destination           varchar(255)     NOT NULL,
    total_snapshots       bigint           NOT NULL,
    unique_trips          bigint           NOT NULL,
    delayed_trips         bigint           NOT NULL,
    on_time_trips         bigint           NOT NULL,
    average_delay_minutes double precision NOT NULL,
    max_delay_minutes     integer          NOT NULL,
    total_delay_minutes   bigint           NOT NULL,
    small_delay_trips     bigint           NOT NULL,
    medium_delay_trips    bigint           NOT NULL,
    big_delay_trips       bigint           NOT NULL,
    extreme_delay_trips   bigint           NOT NULL,
    updated_at            timestamp        NOT NULL,
    PRIMARY KEY (service_date, service_scope, station_code, origin, destination)
);

CREATE TABLE IF NOT EXISTS daily_trip_metrics (
    service_date      date        NOT NULL,
    service_scope     varchar(32) NOT NULL,
    trip_id           bigint      NOT NULL,
    train_code        varchar(32),
    train_date        varchar(32),
    direction         varchar(64),
    origin            varchar(255) NOT NULL,
    destination       varchar(255) NOT NULL,
    peak_delay        integer     NOT NULL,
    snapshot_count    bigint      NOT NULL,
    first_captured_at timestamp,
    peak_station_code varchar(32),
    peak_station_name varchar(255),
    peak_sch_depart   varchar(16),
    peak_sch_arrival  varchar(16),
    peak_captured_at  timestamp,
    updated_at        timestamp   NOT NULL,
    PRIMARY KEY (service_date, service_scope, trip_id)
);

CREATE TABLE IF NOT EXISTS daily_hourly_metrics (
    service_date        date        NOT NULL,
    service_scope       varchar(32) NOT NULL,
    hour_of_day         smallint    NOT NULL,
    snapshot_count      bigint      NOT NULL,
    delayed_snapshots   bigint      NOT NULL,
    total_delay_minutes bigint      NOT NULL,
    updated_at          timestamp   NOT NULL,
    PRIMARY KEY (service_date, service_scope, hour_of_day)
);

CREATE INDEX IF NOT EXISTS idx_dsrm_scope_date_station ON daily_station_route_metrics (service_scope, service_date, station_code);
CREATE INDEX IF NOT EXISTS idx_dsrm_date_scope        ON daily_station_route_metrics (service_date, service_scope);
CREATE INDEX IF NOT EXISTS idx_dtm_date_scope         ON daily_trip_metrics (service_date, service_scope);
CREATE INDEX IF NOT EXISTS idx_dtm_scope_peak         ON daily_trip_metrics (service_scope, peak_delay DESC);
CREATE INDEX IF NOT EXISTS idx_dtm_trip               ON daily_trip_metrics (trip_id);
CREATE INDEX IF NOT EXISTS idx_dhm_date_scope         ON daily_hourly_metrics (service_date, service_scope);

-- routes() reads daily_trip_metrics, so this index was pure write overhead.
DROP INDEX IF EXISTS idx_dsrm_scope_route_date;
