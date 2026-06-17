.PHONY: install build test run ingest docker-build docker-up docker-down clean

MVN := mvn

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
install:
	$(MVN) install -DskipTests

build:
	$(MVN) package -DskipTests

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
test:
	$(MVN) test

# ---------------------------------------------------------------------------
# Serveur de développement
# ---------------------------------------------------------------------------
run:
	$(MVN) spring-boot:run

# ---------------------------------------------------------------------------
# Ingestion GTFS (lance l'app en mode worker et quitte après ingestion)
# ---------------------------------------------------------------------------
ingest:
	INGEST_ON_STARTUP=true WORKER_MODE=true $(MVN) spring-boot:run \
	  -Dspring-boot.run.jvmArguments="-DINGEST_ON_STARTUP=true -DWORKER_MODE=true"

# ---------------------------------------------------------------------------
# Docker
# ---------------------------------------------------------------------------
docker-build:
	docker build -t abrid:latest .

docker-up:
	docker compose up --build

docker-down:
	docker compose down

docker-logs:
	docker compose logs -f api

# ---------------------------------------------------------------------------
# Nettoyage
# ---------------------------------------------------------------------------
clean:
	$(MVN) clean
	rm -f data/oncf.db
