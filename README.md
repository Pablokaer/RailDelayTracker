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
| Framework | Spring Boot 3.2.5 |
| Web / REST | Spring Web (RestTemplate, SseEmitter) |
| Persistence | Spring Data JPA + PostgreSQL |
| HTML Templates | Thymeleaf |
| API Parsing | Jackson XML (`jackson-dataformat-xml`) |
| Frontend | Bootstrap 5 + Chart.js 4 |
| Build | Maven 3.8+ |

---

## Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL running locally on port `5432` with the `irishrail` database created

```sql
CREATE DATABASE irishrail;
```

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
irishrail.api.station-data-base-url=https://api.irishrail.ie/realtime/realtime.asmx/getStationDataByCodeXML_WithNumMins?NumMins=90&StationCode=
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

# tile.openstreetmap.org is not permitted for production traffic by the OSMF tile usage policy.
irishrail.map.tile-url=https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png
irishrail.map.refresh-ms=10000

irishrail.retention.days=90
```

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

`/api/train-positions` is served entirely from an in-memory snapshot that a scheduled task
refreshes, so the upstream call rate is fixed regardless of how many clients have the map open.

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
without losing history. Adding a column therefore needs an explicit
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in `AnalyticsAggregateService.ensureSchema()`.

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
    RestClientConfig.java        # RestTemplate with mandatory timeouts
    CollectorConfig.java         # Bounded pool for concurrent station fetches
  controller/
    TrainController.java
  model/
    TrainInfo.java
    TrainPosition.java           # getCurrentTrainsXML wire format
    LiveTrain.java               # What the map consumes
    TrainHistory.java
    Trip.java
    TripStationSnapshot.java
    DelayCategory.java
    ...
  repository/
    TripRepository.java
    TripStationSnapshotRepository.java
    TrainDelayRepository.java
  service/
    IrishRailService.java        # Irish Rail API client, station lists cached 1 h
    StationDirectory.java        # Exact name → station index over all 171 stations
    TrainPositionService.java    # Live positions, heading resolution, scheduled refresh
    DelayTrackingService.java    # Analytics queries against the database
    TrainDelayScheduler.java     # Concurrent collection every 30 seconds
    AnalyticsAggregateService.java
    DatabaseIndexInitializer.java
    SnapshotEventService.java    # SSE for live updates
  util/
    PublicMessageParser.java     # Parses the three-line PublicMessage blob
    GeoUtils.java                # Bearing / distance / compass words

src/main/resources/
  application.properties
  static/
    css/app.css                  # Shared tokens + page chrome
    js/app.js                    # escapeHtml, changeView, visibility-aware polling
  templates/
    fragments/layout.html        # Head assets (with SRI), analytics tag, site header
    trains.html
    overview.html
    journey.html
    map.html

src/test/java/com/irishrail/     # 28 unit tests, no database required
```
