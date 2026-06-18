# PROMPT_HISTORY.md — Historique des sessions agent

Fichier de reprise de session. À lire en début de toute nouvelle session avant d'agir.
Met à jour ce fichier à la fin de chaque session de travail.

---

## État général du projet

| Champ | Valeur |
|---|---|
| Projet | Abrid — Assistant de mobilité multimodal Maroc |
| Stack | Java 21, Spring Boot 3.3.2, Maven |
| Package racine | `ma.mobility.abrid` |
| Version actuelle | `0.5.0` |
| Dernier lot validé | **Lot 3** |
| Prochain lot | **Lot 4** (Bus CTM/Supratours) |

---

## Lot 0 — Socle Spring Boot + CI ✅ VALIDÉ

### Ce qui a été fait
- Projet Spring Boot 3.3.2 / Java 21 initialisé
- Stack : Spring Web + Spring JDBC + SQLite (dev) + Commons CSV
- Modèle de domaine : `Station`, `Leg`, `Journey`, `Mode` (records Java 21)
- `GtfsLoader` : parsing ZIP GTFS, ingestion, rapport de couverture
- `SearchService` : résolution gare (insensible accents), trajets directs, 1 correspondance via hubs
- API REST : `/stations`, `/plan_trip`, `/schedule`
- `SchemaInitializer` : création schéma SQLite au démarrage
- `GlobalExceptionHandler` : `@ControllerAdvice` → ProblemDetail RFC 9457
- Tests : `SearchServiceTest` (18 tests, oracles §3), `TripControllerTest` (9 tests), `TimeUtilsTest` (11 tests)
- CI GitHub Actions : Spotless check + build + tests
- `docker-compose.yml` : service `worker` (ingestion) + service `api`

### Décisions techniques Lot 0
- SQLite + JdbcTemplate (pas JPA) — suffit pour la V0
- Temps = secondes depuis minuit (supporte les services passant minuit, ex: `25:20:00`)
- `TimeUtils.normalize()` : supprime accents pour la résolution de gare

---

## Lot 1 — ONCF production-grade ✅ VALIDÉ

**Date de session** : 2026-06-17  
**Tests** : 46/46 ✅ | ArchUnit ✅ | Compile ✅

### Ce qui a été fait

#### 1. Migration SQLite → PostgreSQL + PostGIS
- `pom.xml` : suppression `sqlite-jdbc`, ajout `postgresql`, `flyway-core`, `flyway-database-postgresql`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `micrometer-registry-prometheus`, `h2` (test), `spring-boot-testcontainers`, `testcontainers:junit-jupiter`, `testcontainers:postgresql`, `archunit-junit5`
- `SchemaInitializer.java` supprimé → remplacé par Flyway
- `application.properties` supprimé → `application.yml` (datasource PostgreSQL, Flyway, Actuator, Micrometer)
- `src/test/resources/application.properties` : H2 in-memory + Flyway désactivé + Spring SQL init

#### 2. Flyway migrations
- `V1__initial_schema.sql` : schéma complet (PostgreSQL 16), colonnes `meta_key`/`meta_value` dans `ingestion_meta` (évite les mots réservés H2 `key`/`value`)
- `V2__add_disruptions.sql` : table `disruptions` (retards, suppressions, déviations)
- `db/migration/postgresql/V3__add_postgis_geometry.sql` : extension PostGIS + colonne `geom GEOMETRY(POINT,4326)` sur `stations` + index GIST

#### 3. Porte de couverture (coverage gate)
- `GtfsLoader.ingest()` refactorisé en 4 phases : parse → computeCoverage → validateCoverageGate → persistTransactional
- La validation se produit **avant** toute modification de la base (base jamais corrompue)
- `InsufficientCoverageException` levée si couverture < seuil OU régression vs couverture actuelle
- Seuil configurable : `app.worker.coverage-threshold` (défaut 50%)
- `GtfsLoader.ParsedGtfs` (record) : représentation mémoire du ZIP avant persistance

#### 4. StoreRepository → PostgreSQL-compatible
- Upserts : DELETE + INSERT dans `@Transactional` (compatible H2 2.2.224 qui ne supporte pas `ON CONFLICT DO UPDATE SET EXCLUDED.*`)
- `ON CONFLICT DO NOTHING` conservé pour `station_aliases` (H2 le supporte)
- Nouvelles méthodes : `insertDisruption`, `findActiveDisruptions`, `findActiveDisruptionsByRoute`, `purgeExpiredDisruptions`

#### 5. Module realtime (perturbations)
- `Disruption.java` (record), `DisruptionType.java` (enum), `DisruptionSeverity.java` (enum)
- `DisruptionService.java` : CRUD perturbations
- `DisruptionCollector.java` : collecteur `@Scheduled` (stub — pas d'API ONCF disponible, TODO Lot 1-bis)
- `DisruptionDto.java` : DTO retourné par `/disruptions`

#### 6. Config package
- `SchedulingConfig.java` : `@EnableScheduling`
- `IngestionScheduler.java` : worker périodique `@Scheduled` + métriques Micrometer (`abrid.ingestion.success`, `abrid.ingestion.failure`)
- `DataFreshnessHealthIndicator.java` : indicateur Actuator — UP si ingestion < 48h, DOWN si jamais ingéré, OUT_OF_SERVICE sinon

#### 7. API REST mise à jour
- `TripController.java` : suppression `/health` (→ Actuator), ajout `GET /disruptions?routeId=`
- `GlobalExceptionHandler.java` : nettoyé (pas d'import depuis `loader`)

#### 8. IngestRunner simplifié
- Délègue à `IngestionScheduler.runIngestion()` (DRY + métriques centralisées)

#### 9. docker-compose.yml
- Service `db` : `postgis/postgis:16-3.4` avec healthcheck `pg_isready`
- Service `worker` : depends_on `db` (healthy)
- Service `api` : depends_on `db` (healthy) + `worker` (completed_successfully)
- Volume `pgdata` persistant

#### 10. Tests
- `src/test/resources/application.properties` : H2 in-memory (`MODE=PostgreSQL`), Flyway désactivé, Spring SQL init
- `src/test/resources/db/test-schema.sql` : schéma H2-compatible (colonnes `meta_key`/`meta_value`)
- `ArchUnitTest.java` : 7 règles ArchUnit + test JUnit programmatique
- `integration/IngestionIntegrationTest.java` : Testcontainers (`postgis/postgis:16-3.4`), idempotence, gate, `/disruptions`, PostGIS, Actuator

### Décisions techniques Lot 1

| Décision | Raison |
|---|---|
| DELETE+INSERT au lieu d'ON CONFLICT DO UPDATE | H2 2.2.224 ne supporte pas `EXCLUDED.*` pseudo-table |
| `meta_key`/`meta_value` dans ingestion_meta | `key` et `value` sont mots réservés en H2 |
| Flyway désactivé pour tests unitaires | H2 2.2.224 > version testée par Flyway 10.x (→ deadlock pool) |
| Schéma H2 séparé (`test-schema.sql`) | Permet tests unitaires rapides sans Docker |
| Stubs DisruptionCollector | Aucune API ONCF disponible, base légale à valider |
| Seuil couverture = 0 dans tests | Évite rejets lors des tests avec données partielles |

### Fichiers créés/modifiés Lot 1

**Créés** :
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V1__initial_schema.sql`
- `src/main/resources/db/migration/V2__add_disruptions.sql`
- `src/main/resources/db/migration/postgresql/V3__add_postgis_geometry.sql`
- `src/main/java/ma/mobility/abrid/realtime/Disruption.java`
- `src/main/java/ma/mobility/abrid/realtime/DisruptionType.java`
- `src/main/java/ma/mobility/abrid/realtime/DisruptionSeverity.java`
- `src/main/java/ma/mobility/abrid/realtime/DisruptionService.java`
- `src/main/java/ma/mobility/abrid/realtime/DisruptionCollector.java`
- `src/main/java/ma/mobility/abrid/core/loader/InsufficientCoverageException.java`
- `src/main/java/ma/mobility/abrid/config/SchedulingConfig.java`
- `src/main/java/ma/mobility/abrid/config/IngestionScheduler.java`
- `src/main/java/ma/mobility/abrid/config/DataFreshnessHealthIndicator.java`
- `src/main/java/ma/mobility/abrid/api/dto/DisruptionDto.java`
- `src/test/resources/application.properties` (remplace l'ancienne)
- `src/test/resources/application-test.yml`
- `src/test/resources/db/test-schema.sql`
- `src/test/java/ma/mobility/abrid/ArchUnitTest.java`
- `src/test/java/ma/mobility/abrid/integration/IngestionIntegrationTest.java`

**Modifiés** :
- `pom.xml` (0.2.0 → 0.3.0, nouvelles dépendances, JaCoCo, Failsafe)
- `src/main/java/ma/mobility/abrid/core/store/StoreRepository.java` (PostgreSQL compat)
- `src/main/java/ma/mobility/abrid/core/loader/GtfsLoader.java` (coverage gate, ParsedGtfs)
- `src/main/java/ma/mobility/abrid/api/TripController.java` (/disruptions, sans /health)
- `src/main/java/ma/mobility/abrid/api/GlobalExceptionHandler.java` (nettoyé)
- `src/main/java/ma/mobility/abrid/ingestion/IngestRunner.java` (délègue à IngestionScheduler)
- `docker-compose.yml` (ajout service db PostgreSQL/PostGIS)
- `.env.example` (variables DB ajoutées)

**Supprimés** :
- `src/main/resources/application.properties` (→ application.yml)
- `src/main/java/ma/mobility/abrid/core/store/SchemaInitializer.java` (→ Flyway)

---

## Lot 2 — Routage multimodal via OTP ✅ VALIDÉ

**Date de session** : 2026-06-18  
**Tests** : 56/56 ✅ | ArchUnit ✅ | Commit `e3bd481`

### Ce qui a été fait

#### Interface JourneySearchPort
- `JourneySearchPort` : interface dans `search`, stable entre controller et moteurs
- `SearchService` implémente `JourneySearchPort` (SQL, toujours disponible)
- `planTrip(Station, Station, LocalDate, int)` : signature avec stations déjà résolues
- `planTrip(String, String, LocalDate)` conservé pour compatibilité (délègue à l'interface)

#### OtpJourneySearchService (`@Primary`, `@ConditionalOnProperty`)
- Client `RestClient` → `GET /otp/routers/default/plan` (API REST OTP 2.x)
- Actif uniquement si `app.otp.enabled=true` (défaut `false`)
- Mapping JSON OTP → `Journey`/`Leg` domaine : aucun concept GTFS brut exposé
- Conversion epoch millis → secondes-depuis-minuit (gère horaires passant minuit)
- Strip feedId OTP : `"1:TANGER"` → `"TANGER"`
- Résolution gare : cherche en DB par stopId/name, sinon crée Station minimale
- `@CircuitBreaker(name="otp")` Resilience4j : fallback SQL si OTP down/timeout
- Fallback si coordonnées manquantes (stations sans lat/lon)

#### Circuit Breaker
- `resilience4j-spring-boot3:2.2.0` (en cache local, offline)
- Sliding window = 5, failure threshold = 50%, wait = 30s
- Timeout OTP configurable : `app.otp.read-timeout-ms` (défaut 10s)
- `NoDataException` ignorée par le circuit (pas un échec OTP, c'est une réponse normale)

#### Config
- `OtpConfig` : bean `RestClient` avec timeouts (`SimpleClientHttpRequestFactory`)
- `application.yml` : section `app.otp.*` + `resilience4j.*`
- `OTP_ENABLED=false` dans docker-compose (activer dans `.env` avec `OTP_ENABLED=true`)

#### docker-compose
- Service `otp` : `opentripplanner/opentripplanner:2.6.0`
- Volume `./data/otp/` contenant `oncf_gtfs.zip`
- Build graph au démarrage (`--build --serve`)
- Healthcheck sur `/otp/routers/default` avec `start_period=180s`
- Port exposé : `OTP_PORT` (défaut 8088)

#### Tests
- `OtpJourneySearchServiceTest` (8 tests) : mapping direct RAIL, minuit, BUS, JSON malformé, feedId strip, coordonnées manquantes
- `JourneySearchPortTest` (2 tests) : SearchService actif quand OTP off, NoDataException sur DB vide

### Décisions techniques Lot 2

| Décision | Raison |
|---|---|
| API REST OTP (pas GraphQL) | Plus stable entre versions OTP 2.x |
| `app.otp.enabled=false` par défaut | OTP lourd à démarrer, SQL suffisant en dev |
| `resilience4j-spring-boot3` (pas spring-cloud) | Déjà en cache local, évite le BOM spring-cloud |
| Timeout au niveau RestClient (pas TimeLimiter) | Synchronous MVC, pas besoin de Reactor |
| Coordonnées requises pour OTP | OTP routing nécessite lat/lon, fallback SQL si manquant |

### Fichiers créés Lot 2
- `src/main/java/ma/mobility/abrid/core/search/JourneySearchPort.java`
- `src/main/java/ma/mobility/abrid/core/search/OtpJourneySearchService.java`
- `src/main/java/ma/mobility/abrid/config/OtpConfig.java`
- `src/test/java/ma/mobility/abrid/core/search/OtpJourneySearchServiceTest.java`
- `src/test/java/ma/mobility/abrid/core/search/JourneySearchPortTest.java`
- `data/otp/oncf_gtfs.zip` (copie pour OTP)

### Comment activer OTP
```bash
# Dans .env :
OTP_ENABLED=true
OTP_PORT=8088

# Démarrer (OTP prend ~2-3 min pour builder le graphe)
docker compose up otp -d
# Attendre : curl http://localhost:8088/otp/routers/default

# Puis lancer l'API avec OTP activé
docker compose up api -d
```

---

### Pré-requis avant de démarrer
1. Lot 1 merge et déployé en staging ✓
2. `mvn verify` vert (inclut tests Testcontainers) ✓

### Plan technique Lot 2
1. Ajouter service `otp` dans `docker-compose.yml` (image `docker.io/opentripplanner/opentripplanner:2.6.0`)
2. Monter GTFS + extrait OSM Maroc dans OTP
3. Créer `search/OtpJourneySearchService.java` : client `RestClient` → API GraphQL OTP
4. Créer interface `JourneySearchPort` (même signature que `SearchService.planTrip`) 
5. `SearchService` devient l'implémentation SQL (fallback)
6. `@CircuitBreaker` (Resilience4j) sur l'appel OTP → fallback SearchService
7. Tests : parité OD directs entre OTP et SQL, cas multi-correspondances, OTP down → fallback

### Nouvelles dépendances Lot 2 (à justifier en PR)
- `spring-cloud-starter-circuitbreaker-resilience4j` : circuit breaker OTP
- `org.springframework.boot:spring-boot-starter-webflux` (optionnel si WebClient) OU utiliser RestClient (déjà disponible)

---

## Lot 3 — Skill agent + restitution darija 🔜

### Plan technique Lot 3
1. `SKILL.md` + schémas outils (`plan_trip`, `get_schedule`, `get_station_info`, `get_disruptions`)
2. Package `agent` : serveur MCP via Spring AI (`spring-ai-mcp-server`)
3. Outils darija → résolution gare → appel service → narration
4. Gestion désambiguïsation interactive

---

## Points d'attention / dettes techniques

| Item | Priorité | Lot |
|---|---|---|
| DisruptionCollector : stub à connecter à source réelle | Haute | 1-bis |
| Vérifier CGU ONCF avant tout scraping | **Bloquant** | 1-bis |
| Tests Testcontainers : nécessitent Docker local | - | CI |
| Flyway + H2 : utiliser schéma test séparé (test-schema.sql) | Info | permanent |
| `respectFeedDates=false` en dev (flux GTFS daté 2024-2025) | - | prod |
| ArchUnit : règle "pas de logique métier dans @RestController" à renforcer | Basse | 2 |

---

## Commandes utiles

```bash
# Build + tests unitaires
mvn test --no-transfer-progress

# Build + tests unitaires + intégration (Testcontainers, nécessite Docker)
mvn verify --no-transfer-progress

# Lancement local avec PostgreSQL docker-compose
docker compose up db -d
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Ingestion locale
INGEST_ON_STARTUP=true WORKER_MODE=true mvn spring-boot:run

# Stack complète
docker compose up --build
```

---

## Oracle de comportement (§3 du brief — non-régressif)

Ces assertions doivent toujours passer dans `SearchServiceTest` :

| Oracle | Test | Résultat attendu |
|---|---|---|
| Résolution `"casa"` | `SearchServiceTest.resolvePartial` | `AmbiguousStationException` (plusieurs gares Casa) |
| Direct Tanger→Casa | `directTangerCasa` | 1 trajet, départ 08:00, durée 4h |
| Sens respecté | `sensRespecteCasaVersTanger` | 0 trajet direct (Casa→Tanger dans la donnée test) |
| Correspondance Tanger→Marrakech | `tangerMarrakechViaCasa` | 1 trajet, 2 legs via Casa-Voyageurs |
| OD non couvert | `odNonCouvreLeveNoDataException` | `NoDataException` |
| Couverture 100% | `ingestCoverageFullWhenAllRoutesHaveTrips` | `coveragePct = 100.0` |

---

## Lot 3 — Darija Agent Skill (MCP) ✅ VALIDATED

**Session date**: 2026-06-18
**Tests**: 75/75 ✅ | ArchUnit 8 rules ✅ | Commit `82424e9`

### Delivered

- **Spring AI 1.0.0** MCP server (`spring-ai-starter-mcp-server-webmvc`) — offline cache
- **5 tools** in `agent/AgentTools.java`:
  - `plan_trip` — resolves station names (accent-insensitive, darija-tolerant)
  - `get_schedule` — departure board for a station
  - `get_station_info` — station lookup with disambiguation
  - `get_disruptions` — active disruptions filterable by routeId
  - `submit_correction` — crowdsourced corrections (Flyway V4)
- **ArchUnit rule 8**: agent package must not depend on loader
- **SKILL.md**: Claude Desktop config, darija examples, system prompt template
- **Data honesty oracle**: `AgentToolsTest.dataHonestyNoInventedJourneys`

### MCP endpoints
- SSE stream : `GET  http://localhost:8080/sse`
- Tool calls : `POST http://localhost:8080/mcp/message`

---

## Lot 4 — Bus CTM/Supratours 🔜

**Prerequisites**: validate legal basis for bus data (marKoub.ma partnership vs scraping)

**Technical plan**:
1. Bus GTFS (partnership or ToS-compliant source)
2. Ingest into same pipeline (mode=BUS)
3. OTP combines GTFS train + bus automatically
4. Bus fares in `fares` table
5. `get_fare_estimate` tool in AgentTools
