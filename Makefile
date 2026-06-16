.PHONY: install lint format type-check test ingest run clean

# ---------------------------------------------------------------------------
# Installation
# ---------------------------------------------------------------------------
install:
	pip install -e ".[dev]"

# ---------------------------------------------------------------------------
# Qualité
# ---------------------------------------------------------------------------
lint:
	ruff check .
	ruff format --check .

format:
	ruff format .
	ruff check --fix .

type-check:
	mypy oncf_transit/core/

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
test:
	pytest

test-real:
	REAL_GTFS_TESTS=1 pytest tests/test_real_gtfs.py -v

# ---------------------------------------------------------------------------
# Ingestion
# ---------------------------------------------------------------------------
ingest:
	python -m scripts.ingest

ingest-local:
	python -m scripts.ingest --local-zip $(ZIP)

# ---------------------------------------------------------------------------
# Serveur de développement
# ---------------------------------------------------------------------------
run:
	uvicorn oncf_transit.api.main:app --reload --host 0.0.0.0 --port 8000

# ---------------------------------------------------------------------------
# Docker
# ---------------------------------------------------------------------------
docker-up:
	docker-compose up --build

docker-down:
	docker-compose down

# ---------------------------------------------------------------------------
# Nettoyage
# ---------------------------------------------------------------------------
clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -name "*.pyc" -delete
	rm -rf .coverage htmlcov .mypy_cache .ruff_cache dist build *.egg-info
