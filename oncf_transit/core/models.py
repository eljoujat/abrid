"""Modèle de domaine multimodal — indépendant du format source (GTFS, scrape, etc.)."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum


class Mode(StrEnum):
    """Modes de transport supportés."""
    TRAIN = "train"
    BUS = "bus"
    GRAND_TAXI = "grand_taxi"
    URBAN = "urban"
    WALK = "walk"


@dataclass
class Station:
    """Gare ou arrêt, tous modes confondus."""
    id: str
    name: str
    lat: float | None = None
    lon: float | None = None
    # Noms alternatifs pour la résolution (accents, dialecte, abréviations)
    aliases: list[str] = field(default_factory=list)
    mode: Mode = Mode.TRAIN


@dataclass
class Leg:
    """Segment élémentaire d'un trajet (un seul véhicule / mode).

    Le temps est en **secondes depuis minuit du jour de service**.
    Cela permet de gérer les circulations passant minuit (ex. 25:20:00 = 01:20 J+1).
    """
    from_station: Station
    to_station: Station
    departure_seconds: int   # secondes depuis minuit J0
    arrival_seconds: int     # secondes depuis minuit J0
    mode: Mode
    trip_id: str
    route_id: str
    route_name: str
    headsign: str = ""
    fare_mad: float | None = None   # Tarif en dirhams, None si inconnu

    @property
    def duration_seconds(self) -> int:
        return self.arrival_seconds - self.departure_seconds


@dataclass
class Journey:
    """Trajet complet, potentiellement multimodal (N legs, N modes).

    La Journey est le seul objet exposé par l'API et le skill.
    """
    legs: list[Leg]
    # Métadonnées de fiabilité
    data_source: str = ""          # ex. "gtfs_communautaire_rail_maroc"
    data_freshness_date: str = ""  # ISO-8601, date de l'ingestion source

    @property
    def total_duration_seconds(self) -> int:
        if not self.legs:
            return 0
        return self.legs[-1].arrival_seconds - self.legs[0].departure_seconds

    @property
    def departure_seconds(self) -> int:
        return self.legs[0].departure_seconds if self.legs else 0

    @property
    def arrival_seconds(self) -> int:
        return self.legs[-1].arrival_seconds if self.legs else 0

    @property
    def nb_transfers(self) -> int:
        return max(0, len(self.legs) - 1)

    @property
    def total_fare_mad(self) -> float | None:
        """Tarif total si tous les legs ont un tarif connu."""
        fares = [leg.fare_mad for leg in self.legs]
        if any(f is None for f in fares):
            return None
        return sum(f for f in fares if f is not None)
