# RailDelayTracker

Painel de monitoramento de atrasos em tempo real da rede DART (Dublin Area Rapid Transit), consumindo a API publica do Irish Rail e persistindo historico no PostgreSQL.

---

## Tecnologias

- Java 21
- Spring Boot 3.2.5
- Spring Web + Spring Data JPA
- Thymeleaf (templates HTML)
- Jackson XML (desserializacao da API)
- PostgreSQL (persistencia de viagens e snapshots)
- Bootstrap 5 + Chart.js 4 (interface)

---

## Pre-requisitos

- Java 21+
- Maven 3.8+
- PostgreSQL rodando localmente na porta `5432` com banco `irishrail` criado

```sql
CREATE DATABASE irishrail;
```

---

## Como rodar

```bash
git clone <url-do-repositorio>
cd RailDelayTracker
mvn spring-boot:run
```

A aplicacao sobe na porta `8080` por padrao.

---

## Configuracao

Arquivo: `src/main/resources/application.properties`

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/irishrail
spring.datasource.username=postgres
spring.datasource.password=postgres

irishrail.api.all-stations-url=https://api.irishrail.ie/realtime/realtime.asmx/getAllStationsXML_WithStationType?StationType=D
irishrail.api.station-data-base-url=https://api.irishrail.ie/realtime/realtime.asmx/getStationDataByCodeXML_WithNumMins?NumMins=90&StationCode=

irishrail.retention.days=90
```

---

## Coleta de dados

O scheduler coleta dados automaticamente a cada **30 segundos** durante o horario de operacao DART (06:00-00:30).

Filtros aplicados na captura:
- Apenas estacoes DART (`StationType=D`)
- Trens do tipo `bus` sao descartados
- Trens com origem ou destino contendo "Heuston" sao descartados

---

## Paginas disponiveis

| Rota | Descricao |
|---|---|
| `/` | Painel ao vivo da estacao selecionada |
| `/overview` | Analytics da rede completa |
| `/overview?stationCode=XXXX` | Analytics filtrado por estacao |

---

## Endpoints de API

| Endpoint | Descricao |
|---|---|
| `GET /api/trains?stationCode=XXXX` | Dados ao vivo de uma estacao |
| `GET /api/analytics/overview` | Dados do dashboard (JSON) |
| `GET /api/events` | SSE — atualizacoes em tempo real |

---

## Estrutura do banco de dados

| Tabela | Descricao |
|---|---|
| `trip` | Cada viagem unica (train_code + train_date) |
| `trip_station_snapshot` | Snapshots de cada trem em cada estacao ao longo do tempo |
| `train_delay_records` | Registro historico de atrasos capturados |

---

## Estrutura do projeto

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
    ...
  repository/
    TripRepository.java
    TripStationSnapshotRepository.java
    TrainDelayRepository.java
  service/
    IrishRailService.java
    DelayTrackingService.java
    TrainDelayScheduler.java
    SnapshotEventService.java

src/main/resources/
  application.properties
  templates/
    trains.html
    overview.html
```
