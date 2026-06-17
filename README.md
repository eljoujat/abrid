# Abrid — Assistant de mobilité multimodal pour le Maroc

Moteur de planification de trajets couvrant train (ONCF), bus et grands taxis.
Conçu pour être piloté par un agent IA conversant en darija.

## Stack technique

| Composant | Technologie |
|---|---|
| Langage | Java 21 |
| Framework | Spring Boot 3.3 |
| Gestionnaire de dépendances | Maven 3.9 |
| Base de données (dev) | SQLite via JDBC |
| Base de données (prod, Lot 1) | PostgreSQL 16 + PostGIS |
| Tests | JUnit 5 + AssertJ + MockMvc |
| Conteneurisation | Docker (multi-stage) + docker-compose |

## Démarrage rapide

### Avec Maven (dev)

```bash
# 1. Compiler
make build

# 2. Ingérer les données GTFS
make ingest

# 3. Lancer l'API
make run
# → http://localhost:8080
# → http://localhost:8080/health
```

### Avec Docker (mode conteneur)

```bash
# Build + ingestion + API en un seul appel
make docker-up

# Logs
make docker-logs

# Arrêt
make docker-down
```

## Commandes disponibles

| Commande | Description |
|---|---|
| `make install` | Installe + compile (tests sautés) |
| `make build` | Compile et package le fat-jar |
| `make test` | Exécute tous les tests |
| `make run` | Serveur de développement |
| `make ingest` | Ingestion GTFS en mode worker |
| `make docker-build` | Build de l'image Docker |
| `make docker-up` | Stack complète (worker + api) |
| `make docker-down` | Arrêt de la stack |
| `make clean` | Nettoyage Maven + base |

## Architecture

```
src/main/java/ma/mobility/abrid/
├── AbridApplication.java
├── core/
│   ├── model/          # Domaine multimodal : Mode, Station, Leg, Journey
│   ├── time/           # TimeUtils : secondes depuis minuit, gestion >24:00:00
│   ├── store/          # SchemaInitializer, StoreRepository (JdbcTemplate)
│   ├── loader/         # GtfsLoader — seule couche connaissant le format GTFS
│   └── search/         # SearchService : résolution gare + routage
├── api/                # TripController, GlobalExceptionHandler, DTOs
└── ingestion/          # IngestRunner (ApplicationRunner)
```

## Endpoints API

| Endpoint | Description |
|---|---|
| `GET /health` | Santé + fraîcheur des données |
| `GET /stations?q=Tanger` | Liste des gares avec filtre optionnel |
| `GET /plan_trip?from_station=Tanger&to_station=Casa&travel_date=2024-09-02` | Planification |
| `GET /schedule?station=Tanger&travel_date=2024-09-02` | Départs depuis une gare |

## Variables d'environnement

Copier `.env.example` en `.env` :

```bash
DB_PATH=./data/oncf.db
GTFS_URL=https://github.com/newsbubbles/rail_maroc_oncf/raw/main/oncf_gtfs.zip
RESPECT_FEED_DATES=false   # true en prod
INGEST_ON_STARTUP=false
WORKER_MODE=false
PORT=8080
```

## Principe fondamental : honnêteté des données

**Jamais de trajet inventé.** Si un OD n'est pas couvert, l'API renvoie HTTP 404
avec un message explicite (`NoDataException`). La couverture est mesurée et
exposée via `/health`.

## Lots de travail

- **Lot 0** (livré) : socle Spring Boot, moteur GTFS SQLite, tests, Docker
- **Lot 1** : PostgreSQL + PostGIS, source maintenue, temps réel
- **Lot 2** : OpenTripPlanner (N correspondances)
- **Lot 3** : Skill agent + darija
- **Lot 4** : Bus (CTM/Supratours)
- **Lot 5** : Grands taxis (crowdsourcing)
