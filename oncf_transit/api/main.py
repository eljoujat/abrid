"""API FastAPI — point d'entrée HTTP.

Aucune logique métier ici : tout est délégué à core/.
"""

from __future__ import annotations

import os
from datetime import date
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

from oncf_transit.core import models
from oncf_transit.core import search as search_engine
from oncf_transit.core.store import Store, connect
from oncf_transit.core.time_utils import seconds_to_display

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

DB_PATH = Path(os.getenv("DB_PATH", Path(__file__).parent.parent.parent / "data" / "oncf.db"))
RESPECT_FEED_DATES = os.getenv("RESPECT_FEED_DATES", "false").lower() == "true"

app = FastAPI(
    title="Abrid — Assistant de mobilité Maroc",
    description="API de planification de trajets multimodaux (train, bus, grands taxis).",
    version="0.1.0",
)


def _get_store() -> Store:
    return connect(DB_PATH)


# ---------------------------------------------------------------------------
# Schémas Pydantic de réponse
# ---------------------------------------------------------------------------


class StationOut(BaseModel):
    id: str
    name: str
    lat: float | None = None
    lon: float | None = None
    mode: str


class LegOut(BaseModel):
    from_station: StationOut
    to_station: StationOut
    departure: str = Field(..., description="Heure de départ (HH:MM ou HH:MM J+1)")
    arrival: str = Field(..., description="Heure d'arrivée (HH:MM ou HH:MM J+1)")
    duration_minutes: int
    mode: str
    route_name: str
    headsign: str
    fare_mad: float | None = None


class JourneyOut(BaseModel):
    legs: list[LegOut]
    total_duration_minutes: int
    nb_transfers: int
    departure: str
    arrival: str
    total_fare_mad: float | None = None
    data_source: str
    data_freshness_date: str


class PlanTripResponse(BaseModel):
    journeys: list[JourneyOut]
    from_station: StationOut
    to_station: StationOut
    travel_date: str


class HealthResponse(BaseModel):
    status: str
    last_ingestion: str | None = None
    coverage_pct: float | None = None
    db_path: str


# ---------------------------------------------------------------------------
# Convertisseurs domaine → schémas API
# ---------------------------------------------------------------------------


def _station_out(s: models.Station) -> StationOut:
    return StationOut(id=s.id, name=s.name, lat=s.lat, lon=s.lon, mode=s.mode.value)


def _leg_out(leg: models.Leg) -> LegOut:
    return LegOut(
        from_station=_station_out(leg.from_station),
        to_station=_station_out(leg.to_station),
        departure=seconds_to_display(leg.departure_seconds),
        arrival=seconds_to_display(leg.arrival_seconds),
        duration_minutes=leg.duration_seconds // 60,
        mode=leg.mode.value,
        route_name=leg.route_name,
        headsign=leg.headsign,
        fare_mad=leg.fare_mad,
    )


def _journey_out(j: models.Journey) -> JourneyOut:
    return JourneyOut(
        legs=[_leg_out(leg) for leg in j.legs],
        total_duration_minutes=j.total_duration_seconds // 60,
        nb_transfers=j.nb_transfers,
        departure=seconds_to_display(j.departure_seconds),
        arrival=seconds_to_display(j.arrival_seconds),
        total_fare_mad=j.total_fare_mad,
        data_source=j.data_source,
        data_freshness_date=j.data_freshness_date,
    )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@app.get("/health", response_model=HealthResponse, tags=["infra"])
def health() -> HealthResponse:
    """Sonde de santé et de fraîcheur des données."""
    try:
        with _get_store() as store:
            last = store.get_meta("ingested_at")
            cov = store.get_meta("coverage_pct")
            return HealthResponse(
                status="ok",
                last_ingestion=last,
                coverage_pct=float(cov) if cov else None,
                db_path=str(DB_PATH),
            )
    except Exception as exc:
        return HealthResponse(status=f"error: {exc}", db_path=str(DB_PATH))


@app.get("/stations", response_model=list[StationOut], tags=["données"])
def list_stations(
    q: str | None = Query(None, description="Filtre par nom (optionnel)"),
) -> list[StationOut]:
    """Liste toutes les gares, avec filtre optionnel."""
    with _get_store() as store:
        rows = store.get_all_stations()
        results = []
        for row in rows:
            if q and q.lower() not in row["name"].lower():
                continue
            results.append(
                StationOut(
                    id=row["id"],
                    name=row["name"],
                    lat=row["lat"],
                    lon=row["lon"],
                    mode=row["mode"],
                )
            )
        return results


@app.get("/plan_trip", response_model=PlanTripResponse, tags=["planification"])
def plan_trip(
    from_station: str = Query(..., description="Gare de départ (nom ou ID)"),
    to_station: str = Query(..., description="Gare d'arrivée (nom ou ID)"),
    travel_date: str = Query(
        ..., description="Date de voyage (YYYY-MM-DD)"
    ),
) -> PlanTripResponse:
    """Planifie un trajet entre deux gares.

    Renvoie les trajets disponibles triés par heure de départ.
    Si aucun trajet n'existe, renvoie explicitement une erreur 404 (jamais de trajet inventé).
    """
    try:
        td = date.fromisoformat(travel_date)
    except ValueError as exc:
        raise HTTPException(
            status_code=422, detail="Format de date invalide, utiliser YYYY-MM-DD."
        ) from exc

    with _get_store() as store:
        try:
            from_st = search_engine.resolve_station(store, from_station)
            to_st = search_engine.resolve_station(store, to_station)
        except search_engine.StationNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except search_engine.SearchError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        try:
            journeys = search_engine.plan_trip(
                store, from_station, to_station, td, RESPECT_FEED_DATES
            )
        except search_engine.StationNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except search_engine.NoDataError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

        return PlanTripResponse(
            journeys=[_journey_out(j) for j in journeys],
            from_station=_station_out(from_st),
            to_station=_station_out(to_st),
            travel_date=travel_date,
        )


@app.get("/schedule", tags=["données"])
def schedule(
    station: str = Query(..., description="Nom ou ID de la gare"),
    travel_date: str = Query(..., description="Date (YYYY-MM-DD)"),
) -> dict[str, Any]:
    """Départs depuis une gare à une date donnée."""
    try:
        td = date.fromisoformat(travel_date)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail="Format de date invalide.") from exc

    with _get_store() as store:
        try:
            st = search_engine.resolve_station(store, station)
        except search_engine.StationNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except search_engine.SearchError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        rows = store.get_stop_times_for_stop(st.id)
        departures = []
        for row in rows:
            service_id = row["service_id"]
            active = search_engine._is_service_active_for_date(
                store, service_id, td, RESPECT_FEED_DATES
            )
            if not active:
                continue
            departures.append(
                {
                    "trip_id": row["trip_id"],
                    "route_id": row["route_id"],
                    "headsign": row["headsign"],
                    "departure": seconds_to_display(row["departure_seconds"]),
                }
            )

        departures.sort(key=lambda d: d["departure"])
        return {
            "station": _station_out(st).model_dump(),
            "travel_date": travel_date,
            "departures": departures,
        }
