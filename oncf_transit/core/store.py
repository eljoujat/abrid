"""Store SQLite — schéma, connexion et requêtes de base.

Couche d'accès aux données uniquement.
Aucune logique métier ici : déléguer à search.py.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

# Chemin par défaut (surchargeable par variable d'env DB_PATH)
DEFAULT_DB_PATH = Path(__file__).parent.parent.parent / "data" / "oncf.db"

DDL = """
CREATE TABLE IF NOT EXISTS stations (
    id        TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    lat       REAL,
    lon       REAL,
    mode      TEXT NOT NULL DEFAULT 'train'
);

CREATE TABLE IF NOT EXISTS station_aliases (
    station_id  TEXT NOT NULL REFERENCES stations(id),
    alias       TEXT NOT NULL,
    PRIMARY KEY (station_id, alias)
);

CREATE TABLE IF NOT EXISTS routes (
    id         TEXT PRIMARY KEY,
    short_name TEXT,
    long_name  TEXT,
    mode       TEXT NOT NULL DEFAULT 'train'
);

CREATE TABLE IF NOT EXISTS trips (
    id          TEXT PRIMARY KEY,
    route_id    TEXT NOT NULL REFERENCES routes(id),
    service_id  TEXT NOT NULL,
    headsign    TEXT
);

CREATE TABLE IF NOT EXISTS stop_times (
    trip_id           TEXT NOT NULL REFERENCES trips(id),
    stop_id           TEXT NOT NULL REFERENCES stations(id),
    stop_sequence     INTEGER NOT NULL,
    departure_seconds INTEGER NOT NULL,
    arrival_seconds   INTEGER NOT NULL,
    PRIMARY KEY (trip_id, stop_sequence)
);

CREATE INDEX IF NOT EXISTS idx_stop_times_stop ON stop_times(stop_id);
CREATE INDEX IF NOT EXISTS idx_stop_times_trip ON stop_times(trip_id);

-- Calendrier hebdomadaire
CREATE TABLE IF NOT EXISTS calendar (
    service_id  TEXT PRIMARY KEY,
    monday      INTEGER NOT NULL,
    tuesday     INTEGER NOT NULL,
    wednesday   INTEGER NOT NULL,
    thursday    INTEGER NOT NULL,
    friday      INTEGER NOT NULL,
    saturday    INTEGER NOT NULL,
    sunday      INTEGER NOT NULL,
    start_date  TEXT NOT NULL,
    end_date    TEXT NOT NULL
);

-- Exceptions ponctuelles au calendrier
CREATE TABLE IF NOT EXISTS calendar_dates (
    service_id      TEXT NOT NULL,
    date            TEXT NOT NULL,
    exception_type  INTEGER NOT NULL,  -- 1=ajout, 2=suppression
    PRIMARY KEY (service_id, date)
);

-- Tarifs (optionnel, alimenté ultérieurement)
CREATE TABLE IF NOT EXISTS fares (
    route_id    TEXT NOT NULL,
    from_stop   TEXT NOT NULL,
    to_stop     TEXT NOT NULL,
    fare_mad    REAL NOT NULL,
    fare_class  TEXT,
    PRIMARY KEY (route_id, from_stop, to_stop)
);

-- Métadonnées d'ingestion
CREATE TABLE IF NOT EXISTS ingestion_meta (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);
"""


class Store:
    """Encapsule la connexion et l'accès à la base."""

    def __init__(self, db_path: str | Path = DEFAULT_DB_PATH) -> None:
        self.db_path = Path(db_path)
        self._conn: sqlite3.Connection | None = None

    def connect(self) -> Store:
        """Ouvre la connexion et crée le schéma si nécessaire. Idempotent."""
        if self._conn is not None:
            return self  # Déjà connecté — ne pas recréer
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.executescript(DDL)
        self._conn.commit()
        return self

    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("Store non connecté — appeler connect() d'abord.")
        return self._conn

    def close(self) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None

    def __enter__(self) -> Store:
        return self.connect()

    def __exit__(self, *_: object) -> None:
        self.close()

    # --- Métadonnées d'ingestion ---

    def set_meta(self, key: str, value: str) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO ingestion_meta(key, value) VALUES (?,?)",
            (key, value),
        )
        self.conn.commit()

    def get_meta(self, key: str) -> str | None:
        row = self.conn.execute(
            "SELECT value FROM ingestion_meta WHERE key=?", (key,)
        ).fetchone()
        return row["value"] if row else None

    # --- Lecture des gares ---

    def get_all_stations(self) -> list[sqlite3.Row]:
        return self.conn.execute("SELECT * FROM stations").fetchall()

    def get_station_aliases(self, station_id: str) -> list[str]:
        rows = self.conn.execute(
            "SELECT alias FROM station_aliases WHERE station_id=?", (station_id,)
        ).fetchall()
        return [r["alias"] for r in rows]

    # --- Lecture des horaires ---

    def get_stop_times_for_stop(self, stop_id: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            """
            SELECT st.*, t.route_id, t.service_id, t.headsign
            FROM stop_times st
            JOIN trips t ON st.trip_id = t.id
            WHERE st.stop_id = ?
            ORDER BY st.departure_seconds
            """,
            (stop_id,),
        ).fetchall()

    def get_stop_times_for_trip(self, trip_id: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            """
            SELECT st.*, s.name as stop_name
            FROM stop_times st
            JOIN stations s ON st.stop_id = s.id
            WHERE st.trip_id = ?
            ORDER BY st.stop_sequence
            """,
            (trip_id,),
        ).fetchall()

    def get_calendar(self, service_id: str) -> sqlite3.Row | None:
        row: sqlite3.Row | None = self.conn.execute(
            "SELECT * FROM calendar WHERE service_id=?", (service_id,)
        ).fetchone()
        return row

    def get_calendar_exceptions(self, service_id: str) -> list[sqlite3.Row]:
        rows: list[sqlite3.Row] = self.conn.execute(
            "SELECT * FROM calendar_dates WHERE service_id=?", (service_id,)
        ).fetchall()
        return rows

    def get_route(self, route_id: str) -> sqlite3.Row | None:
        row: sqlite3.Row | None = self.conn.execute(
            "SELECT * FROM routes WHERE id=?", (route_id,)
        ).fetchone()
        return row


def connect(db_path: str | Path = DEFAULT_DB_PATH) -> Store:
    """Raccourci : crée et connecte un Store."""
    return Store(db_path).connect()
