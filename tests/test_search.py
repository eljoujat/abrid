"""Tests unitaires et d'intégration — Lot 0.

Couvre :
- time_utils : conversion GTFS, calendrier
- store : schéma, connexion, métadonnées
- models : domaine multimodal
- gtfs_loader : ingestion idempotente, rapport de couverture
- search : résolution gare, directs, sens, correspondance, couverture
- API : /health, /stations, /plan_trip, /schedule
- Garde-fou : OD non couvert → NoDataError, jamais de trajet inventé
"""

from __future__ import annotations

import io
import zipfile
from collections.abc import Generator
from datetime import date
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from oncf_transit.core.gtfs_loader import (
    ingest_gtfs,
)
from oncf_transit.core.models import Journey, Leg, Mode, Station
from oncf_transit.core.search import (
    NoDataError,
    StationNotFoundError,
    find_direct_trips,
    plan_trip,
    resolve_station,
)
from oncf_transit.core.store import Store
from oncf_transit.core.time_utils import (
    date_to_service_date,
    gtfs_time_to_seconds,
    is_service_active,
    seconds_to_display,
    seconds_to_hhmm,
    service_date_to_date,
)

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

# Date de référence figée : lundi 2 septembre 2024
REFERENCE_DATE = date(2024, 9, 2)
REFERENCE_DATE_STR = "20240902"


def _make_gtfs_zip(
    stops: str = "",
    routes: str = "",
    trips: str = "",
    stop_times: str = "",
    calendar: str = "",
    calendar_dates: str = "",
) -> bytes:
    """Construit un ZIP GTFS minimal en mémoire."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("stops.txt", stops or "stop_id,stop_name,stop_lat,stop_lon\n")
        zf.writestr("routes.txt", routes or "route_id,route_short_name,route_long_name\n")
        zf.writestr("trips.txt", trips or "trip_id,route_id,service_id,trip_headsign\n")
        zf.writestr(
            "stop_times.txt",
            stop_times or "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n",
        )
        zf.writestr(
            "calendar.txt",
            calendar or (
                "service_id,monday,tuesday,wednesday,thursday,"
                "friday,saturday,sunday,start_date,end_date\n"
            ),
        )
        zf.writestr("calendar_dates.txt", calendar_dates or "service_id,date,exception_type\n")
    return buf.getvalue()


# GTFS minimal avec 2 gares, 1 ligne, 1 trajet direct
MINIMAL_STOPS = (
    "stop_id,stop_name,stop_lat,stop_lon\n"
    "TANGER,Tanger,35.769,-5.800\n"
    "CASA_VOYAGEURS,Casa-Voyageurs,33.590,-7.620\n"
    "FES,Fès,34.036,-5.000\n"
    "MARRAKECH,Marrakech,31.630,-8.000\n"
)

MINIMAL_ROUTES = (
    "route_id,route_short_name,route_long_name\n"
    "LIGNE_NORD,L1,Tanger - Casa\n"
    "LIGNE_SUD,L2,Casa - Marrakech\n"
    "LIGNE_EST,L3,Casa - Fes\n"
)

MINIMAL_CALENDAR = (
    "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
    "SVC_TOUS,1,1,1,1,1,1,1,20240101,20251231\n"
)

# Trajet TNG→CASA direct
MINIMAL_TRIPS = (
    "trip_id,route_id,service_id,trip_headsign\n"
    "T001,LIGNE_NORD,SVC_TOUS,Casa-Voyageurs\n"
    "T002,LIGNE_SUD,SVC_TOUS,Marrakech\n"
    "T003,LIGNE_EST,SVC_TOUS,Fès\n"
)

MINIMAL_STOP_TIMES = (
    "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n"
    # T001 : Tanger 08:00 → Casa 12:00
    "T001,TANGER,1,08:00:00,08:00:00\n"
    "T001,CASA_VOYAGEURS,2,12:00:00,12:00:00\n"
    # T002 : Casa 13:00 → Marrakech 17:00
    "T002,CASA_VOYAGEURS,1,13:00:00,13:00:00\n"
    "T002,MARRAKECH,2,17:00:00,17:00:00\n"
    # T003 : Casa 14:00 → Fès 18:00
    "T003,CASA_VOYAGEURS,1,14:00:00,14:00:00\n"
    "T003,FES,2,18:00:00,18:00:00\n"
)


@pytest.fixture
def minimal_gtfs_zip() -> bytes:
    return _make_gtfs_zip(
        stops=MINIMAL_STOPS,
        routes=MINIMAL_ROUTES,
        trips=MINIMAL_TRIPS,
        stop_times=MINIMAL_STOP_TIMES,
        calendar=MINIMAL_CALENDAR,
    )


@pytest.fixture
def mem_store() -> Generator[Store, None, None]:
    """Store SQLite en mémoire, isolé par test."""
    store = Store(":memory:")
    store.connect()
    yield store
    store.close()


@pytest.fixture
def loaded_store(mem_store: Store, minimal_gtfs_zip: bytes) -> Store:
    """Store en mémoire avec le GTFS minimal chargé."""
    ingest_gtfs(mem_store, minimal_gtfs_zip, source_url="test://minimal")
    return mem_store


# ---------------------------------------------------------------------------
# Tests : time_utils
# ---------------------------------------------------------------------------


class TestTimeUtils:
    def test_gtfs_time_standard(self) -> None:
        assert gtfs_time_to_seconds("08:30:00") == 30600

    def test_gtfs_time_midnight_plus(self) -> None:
        """25:20:00 = 1h20 le lendemain."""
        assert gtfs_time_to_seconds("25:20:00") == 91200

    def test_gtfs_time_invalid(self) -> None:
        with pytest.raises(ValueError):
            gtfs_time_to_seconds("invalid")

    def test_seconds_to_hhmm(self) -> None:
        assert seconds_to_hhmm(30600) == "08:30"
        assert seconds_to_hhmm(91200) == "25:20"

    def test_seconds_to_display_normal(self) -> None:
        assert seconds_to_display(30600) == "08:30"

    def test_seconds_to_display_midnight_plus(self) -> None:
        result = seconds_to_display(91200)
        assert "01:20" in result
        assert "J+1" in result

    def test_service_date_roundtrip(self) -> None:
        d = date(2024, 9, 2)
        assert service_date_to_date(date_to_service_date(d)) == d

    def test_is_service_active_weekday(self) -> None:
        # REFERENCE_DATE = lundi 2 sept 2024
        weekdays = [True, True, True, True, True, False, False]  # lun-ven
        start = date(2024, 1, 1)
        end = date(2025, 12, 31)
        assert is_service_active(REFERENCE_DATE, start, end, weekdays)

    def test_is_service_active_outside_range(self) -> None:
        weekdays = [True] * 7
        start = date(2025, 1, 1)
        end = date(2025, 12, 31)
        assert not is_service_active(REFERENCE_DATE, start, end, weekdays, respect_feed_dates=True)

    def test_is_service_active_ignore_dates(self) -> None:
        """Mode dev : ignore les bornes."""
        weekdays = [True] * 7
        start = date(2025, 1, 1)
        end = date(2025, 12, 31)
        assert is_service_active(REFERENCE_DATE, start, end, weekdays, respect_feed_dates=False)


# ---------------------------------------------------------------------------
# Tests : models
# ---------------------------------------------------------------------------


class TestModels:
    def test_leg_duration(self) -> None:
        s1 = Station("A", "Gare A")
        s2 = Station("B", "Gare B")
        leg = Leg(
            from_station=s1,
            to_station=s2,
            departure_seconds=28800,  # 08:00
            arrival_seconds=43200,   # 12:00
            mode=Mode.TRAIN,
            trip_id="T1",
            route_id="R1",
            route_name="Ligne 1",
        )
        assert leg.duration_seconds == 14400

    def test_journey_nb_transfers(self) -> None:
        s1 = Station("A", "A")
        s2 = Station("B", "B")
        s3 = Station("C", "C")
        leg1 = Leg(s1, s2, 0, 3600, Mode.TRAIN, "T1", "R1", "L1")
        leg2 = Leg(s2, s3, 4200, 7200, Mode.TRAIN, "T2", "R1", "L1")
        j = Journey(legs=[leg1, leg2])
        assert j.nb_transfers == 1

    def test_journey_total_fare_none_if_missing(self) -> None:
        s1 = Station("A", "A")
        s2 = Station("B", "B")
        leg = Leg(s1, s2, 0, 3600, Mode.TRAIN, "T1", "R1", "L1", fare_mad=None)
        j = Journey(legs=[leg])
        assert j.total_fare_mad is None

    def test_journey_total_fare_sum(self) -> None:
        s1 = Station("A", "A")
        s2 = Station("B", "B")
        s3 = Station("C", "C")
        leg1 = Leg(s1, s2, 0, 3600, Mode.TRAIN, "T1", "R1", "L1", fare_mad=50.0)
        leg2 = Leg(s2, s3, 4200, 7200, Mode.TRAIN, "T2", "R1", "L1", fare_mad=80.0)
        j = Journey(legs=[leg1, leg2])
        assert j.total_fare_mad == 130.0


# ---------------------------------------------------------------------------
# Tests : store
# ---------------------------------------------------------------------------


class TestStore:
    def test_connect_creates_schema(self, mem_store: Store) -> None:
        tables = {
            row[0]
            for row in mem_store.conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        assert "stations" in tables
        assert "stop_times" in tables
        assert "calendar" in tables

    def test_set_get_meta(self, mem_store: Store) -> None:
        mem_store.set_meta("test_key", "test_value")
        assert mem_store.get_meta("test_key") == "test_value"

    def test_get_meta_missing(self, mem_store: Store) -> None:
        assert mem_store.get_meta("inexistant") is None


# ---------------------------------------------------------------------------
# Tests : gtfs_loader
# ---------------------------------------------------------------------------


class TestGtfsLoader:
    def test_ingest_counts(self, mem_store: Store, minimal_gtfs_zip: bytes) -> None:
        report = ingest_gtfs(mem_store, minimal_gtfs_zip, source_url="test://")
        assert report.total_stations == 4
        assert report.total_routes == 3
        assert report.total_trips == 3
        assert report.total_stop_times == 6

    def test_ingest_coverage_full(self, mem_store: Store, minimal_gtfs_zip: bytes) -> None:
        """Toutes les lignes ont des trajets → couverture 100%."""
        report = ingest_gtfs(mem_store, minimal_gtfs_zip)
        assert report.routes_without_trips == []
        assert report.coverage_pct == 100.0

    def test_ingest_coverage_partial(self, mem_store: Store) -> None:
        """Une ligne sans trajets est signalée dans le rapport."""
        routes = (
            "route_id,route_short_name,route_long_name\n"
            "LIGNE_VIDE,LV,Ligne sans horaires\n"
        )
        gtfs_zip = _make_gtfs_zip(
            stops=MINIMAL_STOPS,
            routes=routes,
        )
        report = ingest_gtfs(mem_store, gtfs_zip)
        assert "LIGNE_VIDE" in report.routes_without_trips
        assert report.coverage_pct == 0.0

    def test_ingest_idempotent(self, mem_store: Store, minimal_gtfs_zip: bytes) -> None:
        """Deux ingestions successives → même état (pas de doublons)."""
        report1 = ingest_gtfs(mem_store, minimal_gtfs_zip)
        report2 = ingest_gtfs(mem_store, minimal_gtfs_zip)
        assert report1.total_stations == report2.total_stations
        assert report1.total_trips == report2.total_trips
        # Vérifier qu'il n'y a pas de doublons en base
        count = mem_store.conn.execute("SELECT COUNT(*) FROM stations").fetchone()[0]
        assert count == 4

    def test_ingest_midnight_plus(self, mem_store: Store) -> None:
        """Les horaires > 24:00:00 sont correctement stockés."""
        stops = "stop_id,stop_name\nA,Gare A\nB,Gare B\n"
        routes = "route_id,route_short_name\nR1,R1\n"
        trips = "trip_id,route_id,service_id\nT1,R1,SVC1\n"
        stop_times = (
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n"
            "T1,A,1,23:50:00,23:50:00\n"
            "T1,B,2,25:10:00,25:10:00\n"
        )
        gtfs_zip = _make_gtfs_zip(stops=stops, routes=routes, trips=trips, stop_times=stop_times)
        report = ingest_gtfs(mem_store, gtfs_zip)
        assert report.total_stop_times == 2
        row = mem_store.conn.execute(
            "SELECT arrival_seconds FROM stop_times WHERE stop_id='B'"
        ).fetchone()
        assert row is not None
        assert row[0] == gtfs_time_to_seconds("25:10:00")

    def test_meta_stored_after_ingest(self, mem_store: Store, minimal_gtfs_zip: bytes) -> None:
        ingest_gtfs(mem_store, minimal_gtfs_zip, source_url="http://example.com/gtfs.zip")
        assert mem_store.get_meta("source_url") == "http://example.com/gtfs.zip"
        assert mem_store.get_meta("ingested_at") is not None


# ---------------------------------------------------------------------------
# Tests : search — résolution de gare
# ---------------------------------------------------------------------------


class TestResolveStation:
    def test_resolve_by_exact_name(self, loaded_store: Store) -> None:
        st = resolve_station(loaded_store, "Tanger")
        assert st.id == "TANGER"

    def test_resolve_case_insensitive(self, loaded_store: Store) -> None:
        st = resolve_station(loaded_store, "tanger")
        assert st.id == "TANGER"

    def test_resolve_without_accent(self, loaded_store: Store) -> None:
        """'Fes' doit résoudre 'Fès'."""
        st = resolve_station(loaded_store, "Fes")
        assert st.id == "FES"

    def test_resolve_partial(self, loaded_store: Store) -> None:
        st = resolve_station(loaded_store, "Casa")
        assert st.id == "CASA_VOYAGEURS"

    def test_resolve_by_id(self, loaded_store: Store) -> None:
        st = resolve_station(loaded_store, "MARRAKECH")
        assert st.id == "MARRAKECH"

    def test_resolve_unknown_raises(self, loaded_store: Store) -> None:
        with pytest.raises(StationNotFoundError):
            resolve_station(loaded_store, "GareInexistante")


# ---------------------------------------------------------------------------
# Tests : search — trajets directs
# ---------------------------------------------------------------------------


class TestDirectTrips:
    def test_direct_tanger_casa(self, loaded_store: Store) -> None:
        """Trajet direct Tanger→Casa connu du flux."""
        tanger = resolve_station(loaded_store, "Tanger")
        casa = resolve_station(loaded_store, "Casa")
        journeys = find_direct_trips(
            loaded_store, tanger, casa, REFERENCE_DATE, respect_feed_dates=False
        )
        assert len(journeys) == 1
        assert journeys[0].legs[0].departure_seconds == gtfs_time_to_seconds("08:00:00")
        assert journeys[0].legs[0].arrival_seconds == gtfs_time_to_seconds("12:00:00")

    def test_sens_respecte(self, loaded_store: Store) -> None:
        """Casa→Tanger ne doit PAS renvoyer le trajet Tanger→Casa."""
        casa = resolve_station(loaded_store, "Casa")
        tanger = resolve_station(loaded_store, "Tanger")
        journeys = find_direct_trips(
            loaded_store, casa, tanger, REFERENCE_DATE, respect_feed_dates=False
        )
        assert len(journeys) == 0

    def test_no_direct_tanger_marrakech(self, loaded_store: Store) -> None:
        """Aucun direct Tanger→Marrakech (correspondance nécessaire)."""
        tanger = resolve_station(loaded_store, "Tanger")
        marrakech = resolve_station(loaded_store, "Marrakech")
        journeys = find_direct_trips(
            loaded_store, tanger, marrakech, REFERENCE_DATE, respect_feed_dates=False
        )
        assert len(journeys) == 0


# ---------------------------------------------------------------------------
# Tests : search — correspondances
# ---------------------------------------------------------------------------


class TestTransferTrips:
    def test_tanger_marrakech_via_casa(self, loaded_store: Store) -> None:
        """Tanger→Marrakech : correspondance à Casa."""
        journeys = plan_trip(
            loaded_store, "Tanger", "Marrakech", REFERENCE_DATE, respect_feed_dates=False
        )
        assert len(journeys) > 0
        best = journeys[0]
        assert best.nb_transfers == 1
        # Leg 1 : Tanger→Casa
        assert best.legs[0].from_station.id == "TANGER"
        assert best.legs[0].to_station.id == "CASA_VOYAGEURS"
        # Leg 2 : Casa→Marrakech
        assert best.legs[1].from_station.id == "CASA_VOYAGEURS"
        assert best.legs[1].to_station.id == "MARRAKECH"

    def test_transfer_margin_respected(self, loaded_store: Store) -> None:
        """La correspondance respecte la marge minimale (10 min)."""
        journeys = plan_trip(
            loaded_store, "Tanger", "Marrakech", REFERENCE_DATE, respect_feed_dates=False
        )
        for j in journeys:
            if j.nb_transfers >= 1:
                for i in range(len(j.legs) - 1):
                    gap = j.legs[i + 1].departure_seconds - j.legs[i].arrival_seconds
                    assert gap >= 600, f"Marge de correspondance insuffisante : {gap}s"


# ---------------------------------------------------------------------------
# Tests : garde-fous données
# ---------------------------------------------------------------------------


class TestDataGuardrails:
    def test_no_data_raises_no_data_error(self, loaded_store: Store) -> None:
        """OD non couvert → NoDataError, jamais un trajet inventé."""
        with pytest.raises(NoDataError):
            plan_trip(
                loaded_store,
                "Fès",
                "Marrakech",
                REFERENCE_DATE,
                respect_feed_dates=False,
            )

    def test_unknown_station_raises(self, loaded_store: Store) -> None:
        with pytest.raises(StationNotFoundError):
            plan_trip(loaded_store, "GareFantôme", "Tanger", REFERENCE_DATE)

    def test_empty_db_raises_no_data(self, mem_store: Store) -> None:
        """Base vide → StationNotFoundError (pas de résultat inventé)."""
        with pytest.raises((StationNotFoundError, NoDataError)):
            plan_trip(mem_store, "Tanger", "Casa", REFERENCE_DATE)


# ---------------------------------------------------------------------------
# Tests : API HTTP
# ---------------------------------------------------------------------------


@pytest.fixture
def api_client(loaded_store: Store, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """Client de test API avec une base en mémoire."""
    from oncf_transit.api import main as api_module

    # Le store en mémoire ne doit pas se fermer entre deux requêtes
    monkeypatch.setattr(loaded_store, "close", lambda: None)
    monkeypatch.setattr(api_module, "_get_store", lambda: loaded_store)
    monkeypatch.setattr(api_module, "RESPECT_FEED_DATES", False)

    from oncf_transit.api.main import app
    return TestClient(app)


class TestAPI:
    def test_health_ok(self, api_client: TestClient) -> None:
        resp = api_client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"

    def test_list_stations(self, api_client: TestClient) -> None:
        resp = api_client.get("/stations")
        assert resp.status_code == 200
        stations = resp.json()
        assert len(stations) == 4
        ids = {s["id"] for s in stations}
        assert "TANGER" in ids
        assert "CASA_VOYAGEURS" in ids

    def test_list_stations_filter(self, api_client: TestClient) -> None:
        resp = api_client.get("/stations?q=Tanger")
        assert resp.status_code == 200
        stations = resp.json()
        assert len(stations) == 1
        assert stations[0]["id"] == "TANGER"

    def test_plan_trip_direct(self, api_client: TestClient) -> None:
        resp = api_client.get(
            "/plan_trip",
            params={
                "from_station": "Tanger",
                "to_station": "Casa",
                "travel_date": REFERENCE_DATE.isoformat(),
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        assert len(data["journeys"]) > 0
        assert data["journeys"][0]["nb_transfers"] == 0

    def test_plan_trip_with_transfer(self, api_client: TestClient) -> None:
        resp = api_client.get(
            "/plan_trip",
            params={
                "from_station": "Tanger",
                "to_station": "Marrakech",
                "travel_date": REFERENCE_DATE.isoformat(),
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        assert len(data["journeys"]) > 0
        assert data["journeys"][0]["nb_transfers"] == 1

    def test_plan_trip_no_data_returns_404(self, api_client: TestClient) -> None:
        """OD non couvert → 404, jamais un trajet inventé."""
        resp = api_client.get(
            "/plan_trip",
            params={
                "from_station": "Fes",
                "to_station": "Marrakech",
                "travel_date": REFERENCE_DATE.isoformat(),
            },
        )
        assert resp.status_code == 404
        detail = resp.json()["detail"].lower()
        # Soit OD non couvert, soit gare inconnue — jamais un trajet inventé
        assert any(
            kw in detail
            for kw in ("pas de données", "aucun trajet", "non couvert", "inconnu", "introuvable")
        )

    def test_plan_trip_unknown_station_404(self, api_client: TestClient) -> None:
        resp = api_client.get(
            "/plan_trip",
            params={
                "from_station": "GareInexistante",
                "to_station": "Tanger",
                "travel_date": REFERENCE_DATE.isoformat(),
            },
        )
        assert resp.status_code == 404

    def test_plan_trip_invalid_date_422(self, api_client: TestClient) -> None:
        resp = api_client.get(
            "/plan_trip",
            params={
                "from_station": "Tanger",
                "to_station": "Casa",
                "travel_date": "not-a-date",
            },
        )
        assert resp.status_code == 422

    def test_schedule_endpoint(self, api_client: TestClient) -> None:
        resp = api_client.get(
            "/schedule",
            params={
                "station": "Tanger",
                "travel_date": REFERENCE_DATE.isoformat(),
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["station"]["id"] == "TANGER"
        assert len(data["departures"]) > 0
