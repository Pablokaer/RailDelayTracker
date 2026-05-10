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

Two endpoints are used:

| Endpoint | Returns |
|---|---|
| `getAllStationsXML_WithStationType?StationType=D` | All DART stations |
| `getStationDataByCodeXML_WithNumMins?NumMins=90&StationCode=XXXX` | Trains expected in the next 90 minutes at a given station |

The API returns **XML**, which is deserialized using Jackson XML. Each response includes scheduled time, actual time, minutes late, origin, destination, and train type.

Data is collected automatically every **30 seconds** during DART operating hours (06:00–00:30), using Spring's `@Scheduled`. Each snapshot of a train at a station is persisted to the database to build the analytics history.

Filters applied during collection:
- Only DART stations (`StationType=D`)
- Trains of type `bus` are discarded
- Trains whose origin or destination contains "Heuston" are discarded (intercity line, not DART)

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

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/irishrail
spring.datasource.username=postgres
spring.datasource.password=postgres

irishrail.api.all-stations-url=https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=D
irishrail.api.station-data-base-url=https://api.irishrail.ie/realtime/realtime.asmx/getStationDataByCodeXML_WithNumMins?NumMins=90&StationCode=

irishrail.retention.days=90
```

---

## Pages

| Route | Description |
|---|---|
| `/` | Redirects to today's overview |
| `/get?stationCode=XXXX` | Live departure board for a station + analytics |
| `/overview` | Analytics for the full DART network |
| `/overview?stationCode=XXXX` | Analytics filtered by station |

---

## API Endpoints

| Endpoint | Description |
|---|---|
| `GET /api/trains?stationCode=XXXX` | Live data for a station |
| `GET /api/stations` | List of all DART stations |
| `GET /api/analytics/overview` | Full analytics dashboard (JSON) |
| `GET /api/analytics/summary` | Analytics summary with date filter |
| `GET /api/analytics/recent` | Recent delay snapshots |
| `GET /api/events` | SSE — real-time updates |

---

## Database schema

| Table | Description |
|---|---|
| `trip` | Each unique journey (train_code + train_date) |
| `trip_station_snapshot` | Snapshots of each train at each station over time |
| `train_delay_records` | Historical record of captured delays |

---

## Project structure

```
src/main/java/com/irishrail/
  IrishRailApplication.java
  controller/
    TrainController.java
  model/
    TrainInfo.java
    TrainDelayRecord.java
    Trip.java
    TripStationSnapshot.java
    DelayCategory.java
    ...
  repository/
    TripRepository.java
    TripStationSnapshotRepository.java
    TrainDelayRepository.java
  service/
    IrishRailService.java        # Fetches data from the Irish Rail API
    DelayTrackingService.java    # Analytics queries against the database
    TrainDelayScheduler.java     # Automatic collection every 30 seconds
    SnapshotEventService.java    # SSE for live updates

src/main/resources/
  application.properties
  templates/
    trains.html
    overview.html
```
