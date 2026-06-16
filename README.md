# Abrid — Assistant de mobilité multimodal pour le Maroc

Moteur de planification de trajets couvrant train (ONCF), bus et grands taxis.
Conçu pour être piloté par un agent IA conversant en darija.

## Démarrage rapide

```bash
# 1. Installer les dépendances
make install

# 2. Ingérer les données (télécharge le flux GTFS communautaire)
make ingest

# 3. Lancer l'API
make run
# → http://localhost:8000
# → http://localhost:8000/docs (Swagger)
```

## Commandes disponibles

| Commande | Description |
|---|---|
| `make install` | Installe le projet + dépendances dev |
| `make lint` | Vérification ruff (lint + format) |
| `make format` | Reformatage automatique |
| `make type-check` | Vérification mypy (strict sur `core/`) |
| `make test` | Tests + couverture (seuil 80%) |
| `make test-real` | Tests contre le vrai flux GTFS (réseau requis) |
| `make ingest` | Ingestion GTFS depuis l'URL configurée |
| `make run` | Serveur de développement (rechargement auto) |
| `make docker-up` | Stack complète via docker-compose |

## Architecture

```
oncf_transit/
├── core/
│   ├── models.py        # Domaine multimodal : Station, Leg, Journey
│   ├── time_utils.py    # Temps en secondes depuis minuit (gère >24:00:00)
│   ├── store.py         # Accès SQLite (PostgreSQL au Lot 1)
│   ├── gtfs_loader.py   # Ingestion GTFS + rapport de couverture
│   └── search.py        # Résolution gare + routage (directs + 1 correspondance)
├── api/
│   └── main.py          # FastAPI : /health /stations /plan_trip /schedule
scripts/
└── ingest.py            # CLI d'ingestion
tests/
├── test_search.py       # Tests unitaires et API (base en mémoire)
└── test_real_gtfs.py    # Tests contre le vrai flux (opt-in)
```

## Endpoints API

- `GET /health` — santé + fraîcheur des données
- `GET /stations?q=Tanger` — liste des gares
- `GET /plan_trip?from_station=Tanger&to_station=Casa&travel_date=2024-09-02` — planification
- `GET /schedule?station=Tanger&travel_date=2024-09-02` — départs depuis une gare

## Principe fondamental : honnêteté des données

**Jamais de trajet inventé.** Si un OD n'est pas couvert, l'API renvoie HTTP 404
avec un message explicite. La couverture des lignes est mesurée et exposée
dans le rapport d'ingestion et via `/health`.

## Lots de travail

- **Lot 0** (courant) : socle industrialisé, CI, tests, SQLite
- **Lot 1** : PostgreSQL + PostGIS, source maintenue, temps réel
- **Lot 2** : OpenTripPlanner (routage complet, N correspondances)
- **Lot 3** : Skill agent + restitution darija
- **Lot 4** : Bus (CTM/Supratours)
- **Lot 5** : Grands taxis (crowdsourcing)

## Variables d'environnement

Copier `.env.example` en `.env` :

```
DB_PATH=./data/oncf.db
GTFS_URL=https://github.com/newsbubbles/rail_maroc_oncf/raw/main/oncf_gtfs.zip
RESPECT_FEED_DATES=false   # true en prod
```
