"""Loader GTFS — téléchargement, parsing, ingestion, rapport de couverture.

Seule couche autorisée à connaître le format GTFS.
Expose uniquement des objets du modèle de domaine vers l'extérieur.
"""

from __future__ import annotations

import csv
import io
import logging
import sqlite3
import unicodedata
import zipfile
from dataclasses import dataclass, field
from datetime import datetime
from urllib.request import urlopen

from oncf_transit.core.store import Store
from oncf_transit.core.time_utils import gtfs_time_to_seconds

logger = logging.getLogger(__name__)

# URL du flux GTFS communautaire (source de dev)
GTFS_DEV_URL = (
    "https://github.com/newsbubbles/rail_maroc_oncf/raw/main/oncf_gtfs.zip"
)


@dataclass
class CoverageReport:
    """Rapport de couverture des données ingérées."""

    total_routes: int = 0
    routes_with_trips: int = 0
    routes_without_trips: list[str] = field(default_factory=list)
    total_trips: int = 0
    total_stop_times: int = 0
    total_stations: int = 0
    source_url: str = ""
    ingested_at: str = ""

    @property
    def coverage_pct(self) -> float:
        if self.total_routes == 0:
            return 0.0
        return round(100 * self.routes_with_trips / self.total_routes, 1)

    def __str__(self) -> str:
        lines = [
            "=== Rapport de couverture GTFS ===",
            f"Source      : {self.source_url}",
            f"Ingéré à    : {self.ingested_at}",
            f"Lignes total: {self.total_routes}",
            f"Avec trajets: {self.routes_with_trips} ({self.coverage_pct}%)",
            f"Sans trajets: {len(self.routes_without_trips)}",
        ]
        for r in self.routes_without_trips:
            lines.append(f"  - {r}")
        lines += [
            f"Trajets     : {self.total_trips}",
            f"Horaires    : {self.total_stop_times}",
            f"Gares       : {self.total_stations}",
        ]
        return "\n".join(lines)


def _normalize(text: str) -> str:
    """Normalise une chaîne : minuscules, sans accents, sans tirets."""
    nfkd = unicodedata.normalize("NFKD", text.lower())
    ascii_str = nfkd.encode("ascii", "ignore").decode("ascii")
    return ascii_str.replace("-", " ").replace("_", " ").strip()


def _read_zip_csv(zf: zipfile.ZipFile, filename: str) -> list[dict[str, str]]:
    """Lit un fichier CSV depuis un ZipFile, retourne une liste de dicts."""
    try:
        with zf.open(filename) as f:
            content = f.read().decode("utf-8-sig")
        reader = csv.DictReader(io.StringIO(content))
        return list(reader)
    except KeyError:
        logger.warning("Fichier %s absent du GTFS.", filename)
        return []


def download_gtfs(url: str = GTFS_DEV_URL, timeout: int = 60) -> bytes:
    """Télécharge le flux GTFS et retourne le contenu brut du ZIP."""
    logger.info("Téléchargement GTFS depuis %s …", url)
    with urlopen(url, timeout=timeout) as resp:  # noqa: S310
        data: bytes = resp.read()
    logger.info("Téléchargement terminé (%d octets).", len(data))
    return data


def ingest_gtfs(
    store: Store,
    gtfs_zip: bytes,
    source_url: str = GTFS_DEV_URL,
    respect_feed_dates: bool = False,
) -> CoverageReport:
    """Ingère un flux GTFS dans le store.

    Idempotent : un second appel avec le même zip aboutit au même état.
    Toutes les tables sont purgées avant réingestion (rebuild complet).

    Args:
        store: Store connecté.
        gtfs_zip: Contenu brut du fichier ZIP GTFS.
        source_url: URL source (pour le rapport et les métadonnées).
        respect_feed_dates: Si False, ignore les bornes de validité (mode dev).

    Returns:
        CoverageReport décrivant ce qui a été ingéré.
    """
    report = CoverageReport(
        source_url=source_url,
        ingested_at=datetime.utcnow().isoformat(),
    )
    conn = store.conn

    with zipfile.ZipFile(io.BytesIO(gtfs_zip)) as zf:
        # --- Purge complète (idempotence) ---
        _purge_tables(conn)

        # --- Gares (stops) ---
        stops = _read_zip_csv(zf, "stops.txt")
        _ingest_stops(conn, stops)
        report.total_stations = len(stops)

        # --- Lignes (routes) ---
        routes = _read_zip_csv(zf, "routes.txt")
        _ingest_routes(conn, routes)
        report.total_routes = len(routes)

        # --- Calendrier ---
        calendar = _read_zip_csv(zf, "calendar.txt")
        _ingest_calendar(conn, calendar)
        calendar_dates = _read_zip_csv(zf, "calendar_dates.txt")
        _ingest_calendar_dates(conn, calendar_dates)

        # --- Trajets (trips) ---
        trips = _read_zip_csv(zf, "trips.txt")
        _ingest_trips(conn, trips)

        # --- Horaires (stop_times) ---
        stop_times = _read_zip_csv(zf, "stop_times.txt")
        _ingest_stop_times(conn, stop_times)
        report.total_stop_times = len(stop_times)

    # --- Rapport de couverture ---
    route_ids_with_trips = {
        row[0]
        for row in conn.execute(
            "SELECT DISTINCT route_id FROM trips"
        ).fetchall()
    }
    report.routes_with_trips = len(route_ids_with_trips)
    report.total_trips = conn.execute(
        "SELECT COUNT(*) FROM trips"
    ).fetchone()[0]
    all_route_ids = {
        row[0] for row in conn.execute("SELECT id FROM routes").fetchall()
    }
    report.routes_without_trips = sorted(all_route_ids - route_ids_with_trips)

    # --- Métadonnées ---
    store.set_meta("source_url", source_url)
    store.set_meta("ingested_at", report.ingested_at)
    store.set_meta(
        "respect_feed_dates", "true" if respect_feed_dates else "false"
    )
    store.set_meta(
        "coverage_pct", str(report.coverage_pct)
    )

    conn.commit()
    logger.info("Ingestion terminée. %s", report)
    return report


# ---------------------------------------------------------------------------
# Fonctions internes — connaissent le format GTFS, ne sont pas exposées
# ---------------------------------------------------------------------------


def _purge_tables(conn: sqlite3.Connection) -> None:
    """Vide toutes les tables dans le bon ordre (FK)."""
    tables = [
        "stop_times",
        "calendar_dates",
        "calendar",
        "trips",
        "fares",
        "station_aliases",
        "stations",
        "routes",
    ]
    for t in tables:
        conn.execute(f"DELETE FROM {t}")  # noqa: S608


def _ingest_stops(conn: sqlite3.Connection, stops: list[dict[str, str]]) -> None:
    for row in stops:
        stop_id = row["stop_id"]
        name = row.get("stop_name", "")
        lat = _float_or_none(row.get("stop_lat"))
        lon = _float_or_none(row.get("stop_lon"))
        conn.execute(
            "INSERT OR REPLACE INTO stations(id, name, lat, lon, mode) VALUES (?,?,?,?,?)",
            (stop_id, name, lat, lon, "train"),
        )
        # Alias normalisé pour résolution insensible aux accents
        alias = _normalize(name)
        if alias != name.lower():
            conn.execute(
                "INSERT OR IGNORE INTO station_aliases(station_id, alias) VALUES (?,?)",
                (stop_id, alias),
            )


def _ingest_routes(conn: sqlite3.Connection, routes: list[dict[str, str]]) -> None:
    for row in routes:
        conn.execute(
            "INSERT OR REPLACE INTO routes(id, short_name, long_name, mode) VALUES (?,?,?,?)",
            (
                row["route_id"],
                row.get("route_short_name", ""),
                row.get("route_long_name", ""),
                "train",
            ),
        )


def _ingest_calendar(
    conn: sqlite3.Connection, calendar: list[dict[str, str]]
) -> None:
    for row in calendar:
        conn.execute(
            """INSERT OR REPLACE INTO calendar
               (service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
                start_date, end_date)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            (
                row["service_id"],
                int(row.get("monday", 0)),
                int(row.get("tuesday", 0)),
                int(row.get("wednesday", 0)),
                int(row.get("thursday", 0)),
                int(row.get("friday", 0)),
                int(row.get("saturday", 0)),
                int(row.get("sunday", 0)),
                row.get("start_date", ""),
                row.get("end_date", ""),
            ),
        )


def _ingest_calendar_dates(
    conn: sqlite3.Connection, calendar_dates: list[dict[str, str]]
) -> None:
    for row in calendar_dates:
        conn.execute(
            """INSERT OR REPLACE INTO calendar_dates(service_id, date, exception_type)
               VALUES (?,?,?)""",
            (row["service_id"], row["date"], int(row["exception_type"])),
        )


def _ingest_trips(conn: sqlite3.Connection, trips: list[dict[str, str]]) -> None:
    for row in trips:
        conn.execute(
            "INSERT OR REPLACE INTO trips(id, route_id, service_id, headsign) VALUES (?,?,?,?)",
            (
                row["trip_id"],
                row["route_id"],
                row["service_id"],
                row.get("trip_headsign", ""),
            ),
        )


def _ingest_stop_times(
    conn: sqlite3.Connection, stop_times: list[dict[str, str]]
) -> None:
    for row in stop_times:
        dep = row.get("departure_time", "") or row.get("arrival_time", "")
        arr = row.get("arrival_time", "") or row.get("departure_time", "")
        try:
            dep_s = gtfs_time_to_seconds(dep)
            arr_s = gtfs_time_to_seconds(arr)
        except (ValueError, KeyError):
            logger.warning(
                "Horaire invalide ignoré : trip=%s seq=%s dep=%s arr=%s",
                row.get("trip_id"),
                row.get("stop_sequence"),
                dep,
                arr,
            )
            continue
        conn.execute(
            """INSERT OR REPLACE INTO stop_times
               (trip_id, stop_id, stop_sequence, departure_seconds, arrival_seconds)
               VALUES (?,?,?,?,?)""",
            (
                row["trip_id"],
                row["stop_id"],
                int(row["stop_sequence"]),
                dep_s,
                arr_s,
            ),
        )


def _float_or_none(value: str | None) -> float | None:
    if value is None or value.strip() == "":
        return None
    try:
        return float(value)
    except ValueError:
        return None
