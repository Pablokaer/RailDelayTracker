# RailDelayTracker

## What is it

RailDelayTracker is a web dashboard for monitoring delays on the **DART** network (Dublin Area Rapid Transit), the commuter rail line connecting the north and south coast of Dublin, Ireland.

The application collects real-time data from the Irish Rail public API, stores a history of snapshots in PostgreSQL, and provides two main views: a live departure board per station and an analytics dashboard with accumulated delay statistics.

---

## Where the data comes from

All data comes exclusively from the **Irish Rail public API** (Iarnród Éireann), available at:

```
https://api.irishrail.ie/realtime/realtime.asmx
```

These endpoints are used:

| Endpoint | Returns |
|---|---|
| `getAllStationsXML_WithStationType?StationType=D` | All DART stations |
| `getAllStationsXML_WithStationType?StationType=A` | Every station on the network (171) — used to resolve destinations and the map's station layer |
| `getStationDataByCodeXML_WithNumMins?NumMins=90&StationCode=XXXX` | Trains expected in the next 90 minutes at a given station |
| `getCurrentTrainsXML` | Live position of every train currently in the system |

The API returns **XML**, which is deserialized using Jackson XML. Each response includes scheduled time, actual time, minutes late, origin, destination, and train type.

Data is collected automatically every **30 seconds** during DART operating hours (06:00–00:30), using Spring's `@Scheduled`. Each cycle polls ~140 stations concurrently on a bounded thread pool (`irishrail.collector.threads`) and completes in well under a second in steady state. Changed departures are written as a single JDBC batch (a few ms per cycle), and the boards fetched by the collector are cached for `irishrail.api.board-cache-ms`, so `/api/trains` and the journey planner read the collector's copy instead of polling upstream per open tab. The daily analytics aggregates are rolled up on their own timer (`irishrail.analytics.aggregates.refresh-ms`), restricted to the trips each cycle actually changed — never by re-reading the whole day.

Filters applied during collection:
- The live overview exposes configured service tabs (`irishrail.tracked-station-codes`)
- Collection scans the available station list so each service tab can rank all stations seen in that scope
- Trains of type `bus` are discarded
- Connolly scope excludes trains whose origin or destination contains "Heuston"
- Heuston scope stores trains related to Heuston separately from Connolly analytics

### Reading the `PublicMessage` field

`getCurrentTrainsXML` packs three lines into `PublicMessage`, separated by a **literal backslash-n**
(two characters, not a line break):

```
A220\n16:00 - Dublin Heuston to Cork (2 mins late)\nDeparted Inchicore next stop Thurles
P524\nCobh to Cork\nExpected Departure 16:00
```

`PublicMessageParser` extracts the train code, origin, destination, scheduled/expected departure,
minutes late (negative when early), last location and next stop from it. This is the only place the
live map gets delay information from, so it is covered by unit tests.

### How the map decides which way a train points

`TrainPositionService` resolves a heading in descending order of trustworthiness, and reports which
source it used so the UI can be honest about it:

1. **movement** — bearing between the previous and current capture (needs ≥60 m of travel). Kept for
   up to 3 minutes, because upstream only refreshes coordinates about once a minute.
2. **next-stop** — bearing to the next scheduled stop.
3. **destination** — bearing to the final destination.
4. **compass** — the `Direction` field, but only when it holds a compass word. Roughly half the
   values are destinations ("To Cork"), which yield no angle.
5. **previous** — the last heading we knew.

When none apply the heading is `null` and the map draws a plain dot rather than an arrow pointing at
a guess.

---

## Technologies

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Web / REST | Spring Web (RestClient, SseEmitter) |
| Persistence | Spring Data JPA + PostgreSQL, schema versioned with Flyway |
| HTML Templates | Thymeleaf |
| API Parsing | Jackson XML (`jackson-dataformat-xml`) |
| Caching | Caffeine (bounded, self-evicting) |
| Observability | Spring Boot Actuator + Micrometer/Prometheus |
| Frontend | Bootstrap 5 + Chart.js 4 |
| Build | Maven 3.8+ |
| Tests | JUnit 5, Mockito, Testcontainers (PostgreSQL) |

---

## Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL running locally on port `5432` with the `irishrail` database created

```sql
CREATE DATABASE irishrail;
```

An empty database is enough: **Flyway builds the schema on first start**
(`src/main/resources/db/migration`). Hibernate then runs with `ddl-auto=validate` and refuses to
start if the entities and the migrated schema disagree.

Databases created by the earlier `ddl-auto=update` are picked up as-is — `baseline-on-migrate`
records them at version 0 and the migrations, which are all `CREATE ... IF NOT EXISTS`, apply as a
no-op.

---

## How to run

```bash
git clone <repository-url>
cd RailDelayTracker
mvn spring-boot:run
```

The application starts on port `8080` by default.

---

## Configuration

File: `src/main/resources/application.properties`

Database credentials come from the environment (`DB_USERNAME` / `DB_PASSWORD`), falling back to
`postgres`/`postgres` for local development.

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/irishrail
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:postgres}

# Six independent @Scheduled tasks run — the default pool size of 1 would starve them.
spring.task.scheduling.pool.size=6

irishrail.api.all-stations-url=https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=D
irishrail.api.station-list-base-url=https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=
# The station code is appended as an encoded query parameter, so this value stops at NumMins.
irishrail.api.station-data-base-url=https://api.irishrail.ie/realtime/realtime.asmx/getStationDataByCodeXML_WithNumMins?NumMins=90
irishrail.api.current-trains-url=https://api.irishrail.ie/realtime/realtime.asmx/getCurrentTrainsXML

# Mandatory — the JDK default is infinite, which lets a slow upstream pin threads.
irishrail.api.connect-timeout-ms=4000
irishrail.api.read-timeout-ms=8000

irishrail.api.station-cache-ms=3600000
irishrail.api.board-cache-ms=35000
irishrail.api.train-positions-refresh-ms=10000

irishrail.collector.interval-ms=30000
irishrail.collector.threads=8
irishrail.collector.cycle-budget-seconds=120

irishrail.connolly.collection-station-types=D
irishrail.heuston.collection-station-types=M,S
irishrail.tracked-station-codes=CNLLY,HSTON
irishrail.analytics.aggregates.backfill-on-startup=true
irishrail.analytics.aggregates.refresh-ms=60000

irishrail.sse.heartbeat-ms=25000
irishrail.sse.max-clients=500

# Token bucket in front of /api/**, per client IP, per instance.
irishrail.rate-limit.enabled=true
irishrail.rate-limit.requests-per-minute=120
irishrail.rate-limit.burst=60

# tile.openstreetmap.org is not permitted for production traffic by the OSMF tile usage policy,
# and CARTO's basemaps need an API key since Aug 2026 (they return a placeholder image otherwise).
# Esri's light gray canvas is keyless; note its {z}/{y}/{x} order and zoom cap.
irishrail.map.tile-url=https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}
irishrail.map.tile-max-zoom=16
irishrail.map.refresh-ms=10000

irishrail.retention.days=30
```

Every `irishrail.*` key is bound to the validated record tree in
`config/IrishRailProperties.java`. A misspelled key or an out-of-range value (a negative pool size,
a zero timeout) fails the boot instead of silently falling back to a default.

---

## Pages

| Route | Description |
|---|---|
| `/` | Redirects to today's overview |
| `/get?stationCode=XXXX` | Live departure board for a station + analytics |
| `/overview` | Analytics for all collected stations |
| `/overview?stationCode=CNLLY` | Connolly analytics only |
| `/overview?stationCode=HSTON` | Heuston analytics only |
| `/journey` | Station-to-station live journey options |
| `/map` | Live train positions, coloured by delay and pointing along their heading |

---

## API Endpoints

| Endpoint | Description |
|---|---|
| `GET /api/trains?stationCode=XXXX` | Live data for a station |
| `GET /api/stations` | Tracked service stations |
| `GET /api/stations/all` | Every station with coordinates (map layer) |
| `GET /api/train-positions` | Live positions with heading, delay and next stop |
| `GET /api/trains/{trainCode}/history` | Recorded delay history for one train code |
| `GET /api/journey-options` | Matching live services between two stations |
| `GET /api/analytics/overview` | Full analytics dashboard (JSON) |
| `GET /api/analytics/summary` | Analytics summary with date filter |
| `GET /api/analytics/recent` | Recent delay snapshots |
| `GET /api/events` | SSE — real-time updates (25 s heartbeat) |
| `GET /actuator/health` | Liveness / readiness |
| `GET /actuator/prometheus` | Metrics scrape endpoint |

`/api/train-positions` is served entirely from an in-memory snapshot that a scheduled task
refreshes, so the upstream call rate is fixed regardless of how many clients have the map open.

**Input handling.** `stationCode` is validated against the station directory before it reaches a
cache key or the upstream URL, and `from`/`to` are bound as ISO dates. Anything else is a `400`
with an RFC 7807 body rather than a silently widened query. `/api/**` is throttled per client IP;
over the limit the answer is `429` with `Retry-After`.

**Metrics published** (beyond the JVM and HTTP defaults): `irishrail.upstream` (timer, tagged by
endpoint and outcome), `irishrail.collector.cycle`, `irishrail.collector.snapshots.saved`,
`irishrail.collector.state.entries`, `irishrail.sse.subscribers`, `irishrail.positions.tracked`,
`irishrail.ratelimit.rejected`.

---

## Database schema

| Table | Grain | Description |
|---|---|---|
| `trip` | journey | Each unique journey (train_code + train_date) |
| `trip_station_snapshot` | snapshot | Every observation of a train at a station. The only table that grows fast |
| `daily_station_route_metrics` | date × scope × station × route | Feeds the station ranking |
| `daily_trip_metrics` | date × scope × trip | Each trip's peak delay and where it happened. Feeds the dashboard summary, delay categories, destinations, routes, top-10 and daily delays |
| `daily_hourly_metrics` | date × scope × hour | Feeds the hourly delay analysis |

The three `daily_*` tables are written with UPSERTs, so re-running a date is idempotent. They are
**durable**: nothing drops or truncates them, which is what allows the raw snapshots to be trimmed
without losing history. Adding a column therefore needs a new Flyway migration — the DDL used to
live in an `ensureSchema()` method that ran on every boot.

### Migrations

| File | Contents |
|---|---|
| `V1__baseline_core_tables.sql` | `trip`, `trip_station_snapshot`, their key and FK |
| `V2__analytics_aggregate_tables.sql` | The three `daily_*` tables and their indexes |
| `V3__query_performance_indexes.sql` | BRIN, partial and expression indexes; autovacuum tuning |

The delayed-trip threshold (5 minutes) is baked into the partial index in `V3`, and a migration
cannot read a Java constant. `DelayCategorySqlTest` fails the build if
`DelayLimits.DELAYED_THRESHOLD_MINUTES` is changed without a new migration to rebuild that index.

---

## Data retention

Measured on a live database, the cost of one day of history:

| | Per day | 30 days | 1 year |
|---|---|---|---|
| `trip_station_snapshot` (~120k rows/day, ~278 B each) | ~33 MB | ~1.0 GB | ~12 GB |
| the three `daily_*` aggregates | ~440 kB | ~13 MB | ~160 MB |

Aggregates are ~75× cheaper per day of history, so the two are trimmed on **separate** cutoffs:

```properties
irishrail.retention.days=30            # raw snapshots
irishrail.retention.startup-delay-ms=45000  # the sweep also runs once after boot
irishrail.retention.aggregate-days=0   # 0 = keep aggregate history forever
```

30 days of raw data covers every date filter the UI offers (today / yesterday / last 7 / last 30)
and keeps the drill-down features — recent delays, per-train history — fully populated. Everything
date-ranged reads the aggregates, so "all time" charts keep working far beyond the raw window.

Raw snapshots are deleted on a whole-day boundary, so no partially-trimmed day is ever left behind
and the startup backfill can always safely re-derive any day still present.

> **`DELETE` does not shrink Postgres data files.** It frees space for reuse by later inserts, so
> lowering the retention stops growth but does not return disk to the OS. Run
> `scripts/maintenance/reclaim-space.sql` once for that — it also drops two orphaned tables and
> tunes autovacuum for the nightly delete. Read it before running it: it takes exclusive locks.

---

## Project structure

```
src/main/java/com/irishrail/
  IrishRailApplication.java
  config/
    IrishRailProperties.java     # Every irishrail.* setting, validated, one tree
    RestClientConfig.java        # Pooled HTTP client with mandatory timeouts + shared XmlMapper
    CollectorConfig.java         # Bounded pool for station fetches; SSE broadcast worker
    SchedulingConfig.java        # @EnableScheduling, switchable so tests can load a context
  controller/
    PageController.java          # Server-rendered pages
    ApiController.java           # JSON + SSE endpoints
  web/
    StationCodes.java            # Validates station codes at the edge
    ApiExceptionHandler.java     # RFC 7807 problem responses, scoped to the API
    SecurityHeadersFilter.java   # CSP, nosniff, referrer policy, frame options
    RateLimitFilter.java         # Per-client token bucket on /api/**
  model/
    TrainInfo.java
    TrainPosition.java           # getCurrentTrainsXML wire format
    LiveTrain.java               # What the map consumes
    AnalyticsView.java           # One analytics answer; the JSON contract for overview.js
    JourneyOptions.java          # Typed /api/journey-options payload
    TrainPositionsView.java      # Typed /api/train-positions payload
    StationPoint.java
    TrainHistory.java
    Trip.java
    TripStationSnapshot.java
    DelayCategory.java           # Delay bands; also generates the aggregate SQL
    ...
  repository/
    TripRepository.java
    TripStationSnapshotRepository.java
  service/
    IrishRailService.java        # Irish Rail API client; Caffeine station/board caches
    StationDirectory.java        # Exact name → station index over all 171 stations
    TrainPositionService.java    # Live positions, heading resolution, scheduled refresh
    DelayTrackingService.java    # Analytics queries against the database
    AnalyticsQueryService.java   # Assembles + caches one dashboard, for page and API alike
    TrainDelayScheduler.java     # Concurrent collection every 30 seconds
    AnalyticsAggregateService.java
    SnapshotEventService.java    # SSE for live updates
  util/
    PublicMessageParser.java     # Parses the three-line PublicMessage blob
    GeoUtils.java                # Bearing / distance / compass words

src/main/resources/
  application.properties
  db/migration/                  # Flyway — the only place schema DDL lives
  static/
    css/app.css                  # Shared tokens + page chrome
    js/app.js                    # escapeHtml, changeView, visibility-aware polling
  templates/
    fragments/layout.html        # Head assets (with SRI), analytics tag, site header
    trains.html
    overview.html
    journey.html
    map.html

src/test/java/com/irishrail/
  ...Test.java                   # Unit and web-slice tests; no database, run by surefire
  ...IT.java                     # Need PostgreSQL; run by failsafe under `mvn verify`
  support/PostgresIntegrationTest.java
```

---

## Testing

```bash
mvn test      # unit + web-slice tests, no database needed
mvn verify    # the above plus the integration tests
```

The integration tests need a real PostgreSQL, because the analytics layer is not portable SQL
(`DISTINCT ON`, `ON CONFLICT`, BRIN and partial indexes, advisory locks). They get one from
Testcontainers when Docker is available, and **skip themselves when it is not**, so `mvn verify`
stays green anywhere.

On a machine without Docker, point them at an existing server instead — the named database is
written to and truncated, so do not aim this at one you care about:

```bash
mvn verify -Dirishrail.test.datasource.url=jdbc:postgresql://localhost:5432/irishrail_test
```

What the integration suite actually pins down:

- the Flyway migrations build a working schema from nothing, and Hibernate's `validate` agrees
  with the result — which is the only check that the migrations and the `@Entity` classes still
  describe the same tables;
- the **incremental** roll-up the collector triggers produces the same aggregates as the full
  backfill. Nothing else compares the two, and the collector only ever uses the incremental path;
- aggregate history survives the deletion of the raw snapshots it was derived from.
