"""Moteur de recherche de trajets — multimodal, sans connaissance du format source.

Niveaux de recherche :
1. Résolution de gare : texte libre → Station (insensible aux accents)
2. Trajets directs : même trip passant par A puis B
3. Correspondance simple : A→hub→B (hubs principaux de Casa-Voyageurs, Rabat, Kénitra, Fès)

Ce moteur est le fallback SQLite. Il sera remplacé par un client OTP au Lot 2,
mais son interface (signatures + objets retournés) est stable.
"""

from __future__ import annotations

import logging
import sqlite3
import unicodedata
from datetime import date

from oncf_transit.core.models import Journey, Leg, Mode, Station
from oncf_transit.core.store import Store
from oncf_transit.core.time_utils import (
    date_to_service_date,
    is_service_active,
    service_date_to_date,
)

logger = logging.getLogger(__name__)

# Hubs utilisés pour la correspondance simple (Lot 0/1, remplacé par OTP au Lot 2)
HUB_STOP_IDS = [
    "CASA_VOYAGEURS",
    "CASA_PORT",
    "RABAT_VILLE",
    "KENITRA",
    "FES",
    "MEKNES",
    "SALE",
]


class SearchError(Exception):
    """Erreur métier de la recherche (gare inconnue, OD non couvert…)."""


class StationNotFoundError(SearchError):
    """Gare non trouvée."""


class NoDataError(SearchError):
    """OD non couvert ou aucun trajet disponible — NE PAS inventer de résultat."""


def _normalize(text: str) -> str:
    nfkd = unicodedata.normalize("NFKD", text.lower())
    ascii_str = nfkd.encode("ascii", "ignore").decode("ascii")
    return ascii_str.replace("-", " ").replace("_", " ").strip()


def resolve_station(store: Store, query: str) -> Station:
    """Résout un nom de gare en objet Station.

    Recherche dans cet ordre :
    1. Correspondance exacte sur l'ID.
    2. Correspondance exacte sur le nom (insensible à la casse).
    3. Correspondance normalisée (sans accents, sans tirets) sur le nom.
    4. Correspondance normalisée sur les alias.
    5. Correspondance partielle (LIKE) sur le nom.

    Raises:
        StationNotFoundError: si aucune gare ne correspond.
        SearchError: si plusieurs gares correspondent à un terme ambigu.
    """
    q_norm = _normalize(query)

    rows = store.get_all_stations()

    # 1. ID exact
    for row in rows:
        if row["id"].upper() == query.upper():
            return _row_to_station(store, row)

    # 2. Nom exact (insensible casse)
    for row in rows:
        if row["name"].lower() == query.lower():
            return _row_to_station(store, row)

    # 3. Nom normalisé exact
    matches = [r for r in rows if _normalize(r["name"]) == q_norm]
    if len(matches) == 1:
        return _row_to_station(store, matches[0])
    if len(matches) > 1:
        names = [r["name"] for r in matches]
        raise SearchError(
            f"Terme ambigu '{query}' : plusieurs gares correspondent : {names}. "
            "Précise le nom."
        )

    # 4. Alias normalisé
    for row in rows:
        aliases = store.get_station_aliases(row["id"])
        if q_norm in aliases:
            return _row_to_station(store, row)

    # 5. Partiel sur le nom
    partial = [r for r in rows if q_norm in _normalize(r["name"])]
    if len(partial) == 1:
        return _row_to_station(store, partial[0])
    if len(partial) > 1:
        names = [r["name"] for r in partial]
        raise SearchError(
            f"Terme ambigu '{query}' : plusieurs gares partielles : {names}. "
            "Précise le nom."
        )

    raise StationNotFoundError(f"Gare inconnue : '{query}'")


def _row_to_station(store: Store, row: sqlite3.Row) -> Station:
    return Station(
        id=row["id"],
        name=row["name"],
        lat=row["lat"],
        lon=row["lon"],
        aliases=store.get_station_aliases(row["id"]),
        mode=Mode(row["mode"]) if row["mode"] in Mode._value2member_map_ else Mode.TRAIN,
    )


def _is_service_active_for_date(
    store: Store,
    service_id: str,
    travel_date: date,
    respect_feed_dates: bool,
) -> bool:
    """Vérifie si un service est actif, en tenant compte des exceptions."""
    date_str = date_to_service_date(travel_date)

    # Exceptions ponctuelles (priorité sur le calendrier hebdo)
    exceptions = store.get_calendar_exceptions(service_id)
    for exc in exceptions:
        if exc["date"] == date_str:
            return bool(exc["exception_type"] == 1)  # 1=service ajouté, 2=supprimé

    # Calendrier hebdomadaire
    cal = store.get_calendar(service_id)
    if cal is None:
        # Pas de calendrier → on suppose actif (données incomplètes)
        return True

    weekdays = [
        bool(cal["monday"]),
        bool(cal["tuesday"]),
        bool(cal["wednesday"]),
        bool(cal["thursday"]),
        bool(cal["friday"]),
        bool(cal["saturday"]),
        bool(cal["sunday"]),
    ]
    start = service_date_to_date(cal["start_date"]) if cal["start_date"] else travel_date
    end = service_date_to_date(cal["end_date"]) if cal["end_date"] else travel_date

    return is_service_active(travel_date, start, end, weekdays, respect_feed_dates)


def find_direct_trips(
    store: Store,
    from_station: Station,
    to_station: Station,
    travel_date: date,
    respect_feed_dates: bool = False,
    min_departure_seconds: int = 0,
) -> list[Journey]:
    """Cherche les trajets directs entre deux gares à une date donnée.

    Un trajet est direct si un même trip dessert A puis B (dans cet ordre).

    Returns:
        Liste de Journey triée par heure de départ. Vide = aucun trajet direct
        (ne lève pas d'exception — l'appelant décidera quoi faire).
    """
    conn = store.conn

    # Trips passant par A
    trips_from = conn.execute(
        "SELECT trip_id, stop_sequence, departure_seconds FROM stop_times WHERE stop_id=?",
        (from_station.id,),
    ).fetchall()

    journeys: list[Journey] = []

    for tf in trips_from:
        trip_id = tf["trip_id"]
        seq_from = tf["stop_sequence"]
        dep_s = tf["departure_seconds"]

        if dep_s < min_departure_seconds:
            continue

        # Ce trip passe-t-il par B après A ?
        row_to = conn.execute(
            """SELECT stop_sequence, arrival_seconds FROM stop_times
               WHERE trip_id=? AND stop_id=? AND stop_sequence > ?""",
            (trip_id, to_station.id, seq_from),
        ).fetchone()

        if row_to is None:
            continue

        # Vérification calendrier
        trip_row = conn.execute(
            "SELECT service_id, route_id, headsign FROM trips WHERE id=?",
            (trip_id,),
        ).fetchone()
        if trip_row is None:
            continue

        service_id = trip_row["service_id"]
        if not _is_service_active_for_date(
            store, service_id, travel_date, respect_feed_dates
        ):
            continue

        route = store.get_route(trip_row["route_id"])
        route_name = route["short_name"] or route["long_name"] if route else trip_row["route_id"]

        leg = Leg(
            from_station=from_station,
            to_station=to_station,
            departure_seconds=dep_s,
            arrival_seconds=row_to["arrival_seconds"],
            mode=Mode.TRAIN,
            trip_id=trip_id,
            route_id=trip_row["route_id"],
            route_name=route_name,
            headsign=trip_row["headsign"] or "",
        )
        source_url = store.get_meta("source_url") or ""
        ingested_at = store.get_meta("ingested_at") or ""
        journeys.append(
            Journey(legs=[leg], data_source=source_url, data_freshness_date=ingested_at)
        )

    journeys.sort(key=lambda j: j.departure_seconds)
    return journeys


def find_trips_with_transfer(
    store: Store,
    from_station: Station,
    to_station: Station,
    travel_date: date,
    respect_feed_dates: bool = False,
    min_departure_seconds: int = 0,
    transfer_margin_seconds: int = 600,  # 10 min minimum de correspondance
) -> list[Journey]:
    """Cherche les trajets avec exactement 1 correspondance via un hub.

    Returns:
        Liste de Journey triée par heure de départ totale.
    """
    journeys: list[Journey] = []

    for hub_id in HUB_STOP_IDS:
        # Récupère la Station hub (si elle existe dans la base)
        try:
            hub_station = resolve_station(store, hub_id)
        except (StationNotFoundError, SearchError):
            continue

        # Si la gare d'origine ou destination est le hub, pas de sens
        if hub_station.id == from_station.id or hub_station.id == to_station.id:
            continue

        # Segment 1 : from → hub
        legs1 = find_direct_trips(
            store,
            from_station,
            hub_station,
            travel_date,
            respect_feed_dates,
            min_departure_seconds,
        )
        if not legs1:
            continue

        # Segment 2 : hub → to
        legs2 = find_direct_trips(
            store,
            hub_station,
            to_station,
            travel_date,
            respect_feed_dates,
            min_departure_seconds=0,
        )
        if not legs2:
            continue

        # Combine : pour chaque leg1, le premier leg2 avec marge suffisante
        for j1 in legs1:
            arr_hub = j1.arrival_seconds
            for j2 in legs2:
                if j2.departure_seconds >= arr_hub + transfer_margin_seconds:
                    combined = Journey(
                        legs=[j1.legs[0], j2.legs[0]],
                        data_source=j1.data_source,
                        data_freshness_date=j1.data_freshness_date,
                    )
                    journeys.append(combined)
                    break  # on prend le premier valide pour ce j1

    journeys.sort(key=lambda j: j.departure_seconds)
    return journeys


def plan_trip(
    store: Store,
    from_query: str,
    to_query: str,
    travel_date: date,
    respect_feed_dates: bool = False,
) -> list[Journey]:
    """Point d'entrée principal : planifie un trajet de A vers B.

    Raises:
        StationNotFoundError: si A ou B est introuvable.
        NoDataError: si aucun trajet n'existe (NE PAS inventer de résultat).
    """
    from_station = resolve_station(store, from_query)
    to_station = resolve_station(store, to_query)

    # 1. Directs
    journeys = find_direct_trips(
        store, from_station, to_station, travel_date, respect_feed_dates
    )

    # 2. Une correspondance si aucun direct
    if not journeys:
        journeys = find_trips_with_transfer(
            store, from_station, to_station, travel_date, respect_feed_dates
        )

    if not journeys:
        raise NoDataError(
            f"Aucun trajet trouvé de '{from_station.name}' vers '{to_station.name}' "
            f"le {travel_date.isoformat()}. OD non couvert par les données actuelles."
        )

    return journeys
