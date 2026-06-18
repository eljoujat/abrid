# Abrid — Multimodal Mobility Assistant for Morocco

> **Abrid** ("path" in Amazigh) is a trip planning assistant for Morocco, designed to answer: *"how do I get from A to B?"* — combining **ONCF trains**, **buses**, **grand taxis** and **urban transit**, with responses in darija.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 LTS |
| Framework | Spring Boot 3.3.2 |
| Build tool | Maven 3.9 (wrapper `./mvnw` committed) |
| Database (prod) | PostgreSQL 16 + PostGIS |
| Database (tests) | H2 in-memory (PostgreSQL mode) |
| Migrations | Flyway |
| Routing engine | OpenTripPlanner 2.6 (optional) |
| Circuit breaker | Resilience4j |
| Tests | JUnit 5 + AssertJ + MockMvc + Testcontainers |
| Architecture guard | ArchUnit |
| Observability | Spring Actuator + Micrometer + Prometheus |
| Containerization | Docker + docker-compose |

## Quick Start

### Docker Compose (recommended)

```bash
git clone https://github.com/eljoujat/abrid.git && cd abrid

# Copy configuration
cp .env.example .env
# Edit .env if port 5432 is already in use: DB_EXPOSED_PORT=5433

# Start PostgreSQL + ingest GTFS data
docker compose up db worker -d

# Start the REST API
docker compose up api -d

# Check health
curl http://localhost:8080/actuator/health
```

### Local development (without Docker)

```bash
# Start PostgreSQL only
docker compose up db -d

# Run Spring Boot
export DB_HOST=localhost DB_PORT=5433 DB_USER=abrid DB_PASSWORD=abrid DB_NAME=abrid
export INGEST_ON_STARTUP=true
./mvnw spring-boot:run
```

### Build & test

```bash
./mvnw test           # Unit tests (H2, ~20s)
./mvnw verify         # Unit + integration tests (Testcontainers, ~2min, requires Docker)
```

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | App health + data freshness indicator |
| `GET /stations?q=tanger` | List stations with optional name filter |
| `GET /plan_trip?from_station=Tanger-Ville&to_station=Casa-Voyageurs&travel_date=2025-08-30` | Plan a trip |
| `GET /schedule?station=Rabat-Ville&travel_date=2025-08-30` | Departure board for a station |
| `GET /disruptions?routeId=AL_BORAQ_TNG_CASA` | Active disruptions |

## Package Structure

```
ma.mobility.abrid/
├── core/
│   ├── model/      # Domain: Station, Leg, Journey, Mode (immutable records)
│   ├── time/       # TimeUtils: seconds-since-midnight, handles >24:00:00
│   ├── store/      # StoreRepository (JdbcTemplate)
│   ├── loader/     # GtfsLoader — ONLY layer that knows the GTFS format
│   └── search/     # JourneySearchPort, SearchService (SQL), OtpJourneySearchService
├── api/            # TripController, GlobalExceptionHandler, DTOs
├── realtime/       # Disruption, DisruptionService, DisruptionCollector
├── config/         # OtpConfig, IngestionScheduler, DataFreshnessHealthIndicator
└── ingestion/      # IngestRunner (startup)
```

## Environment Variables

Copy `.env.example` to `.env` and adapt:

```env
DB_HOST=localhost
DB_PORT=5432
DB_EXPOSED_PORT=5433   # change if 5432 is already taken
DB_USER=abrid
DB_PASSWORD=abrid      # change in production
DB_NAME=abrid

GTFS_URL=https://github.com/newsbubbles/rail_maroc_oncf/raw/main/oncf_gtfs.zip
RESPECT_FEED_DATES=false   # set to true in production
COVERAGE_THRESHOLD=50      # minimum coverage % to accept an ingestion

OTP_ENABLED=false          # set to true to activate OpenTripPlanner routing
PORT=8080
```

## Core Principles

1. **Strictly descending dependencies**: `api → search → persistence → loader`
2. **Only `loader` knows the source format** (GTFS, scraping, future APIs)
3. **Multimodal domain model by default**: a `Leg` can be train, bus or taxi
4. **Data honesty**: never invent a trip or schedule — missing data → explicit HTTP 404
5. **Idempotent ingestion**: two runs with the same source = identical DB state
6. **Time in seconds since midnight**: handles overnight services (e.g. `25:20:00`)

These rules are **automatically enforced by ArchUnit** on every build.

## Coverage Gate

Every ingestion is validated *in memory before* touching the database:

```
parse GTFS ZIP → compute coverage → compare vs threshold & current
                                          ↓ OK           ↓ degraded
                                   atomic persist    InsufficientCoverageException
                                   (purge + insert)   (DB unchanged)
```

## Delivered Lots

| Lot | Description | Status |
|-----|-------------|--------|
| **Lot 0** | Spring Boot scaffold, SQL engine, CI, oracles | ✅ Done |
| **Lot 1** | PostgreSQL/PostGIS, Flyway, coverage gate, disruptions | ✅ Done |
| **Lot 2** | OpenTripPlanner 2.x, circuit breaker, N transfers | ✅ Done |
| **Lot 3** | Agent skill + darija (MCP/Spring AI) | 🔜 Next |
| **Lot 4** | Bus integration (CTM/Supratours) | 🔜 Planned |
| **Lot 5** | Grand taxis (crowdsourced data) | 🔜 Planned |

## Documentation

Full documentation available on the [GitHub Wiki](https://github.com/eljoujat/abrid/wiki):

- [Quick Start](https://github.com/eljoujat/abrid/wiki/Quick-Start)
- [API Reference](https://github.com/eljoujat/abrid/wiki/API-Reference)
- [Architecture](https://github.com/eljoujat/abrid/wiki/Architecture)
- [Configuration](https://github.com/eljoujat/abrid/wiki/Configuration)
- [OpenTripPlanner](https://github.com/eljoujat/abrid/wiki/OpenTripPlanner)
- [ONCF Data](https://github.com/eljoujat/abrid/wiki/ONCF-Data)
- [Development](https://github.com/eljoujat/abrid/wiki/Development)
- [Roadmap](https://github.com/eljoujat/abrid/wiki/Roadmap)
