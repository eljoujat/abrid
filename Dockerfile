FROM python:3.11-slim

WORKDIR /app

# Dépendances système
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Dépendances Python
COPY pyproject.toml .
RUN pip install --no-cache-dir -e .

# Code source
COPY oncf_transit/ oncf_transit/
COPY scripts/ scripts/

# Dossier de données (volume en prod)
RUN mkdir -p data

# Sonde de santé
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

EXPOSE 8000

CMD ["uvicorn", "oncf_transit.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
