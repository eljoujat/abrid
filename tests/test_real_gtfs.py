"""Tests contre le vrai flux GTFS communautaire.

Ces tests téléchargent le flux réel — ils sont marqués 'real_gtfs'
et skippés par défaut en CI (pas de réseau) sauf si REAL_GTFS_TESTS=1.

Pour lancer localement :
    REAL_GTFS_TESTS=1 pytest tests/test_real_gtfs.py -v
"""

from __future__ import annotations

import os
from datetime import date

import pytest

from oncf_transit.core.gtfs_loader import GTFS_DEV_URL, download_gtfs, ingest_gtfs
from oncf_transit.core.search import NoDataError, plan_trip
from oncf_transit.core.store import Store

REAL_GTFS = os.getenv("REAL_GTFS_TESTS", "0") == "1"
pytestmark = pytest.mark.skipif(not REAL_GTFS, reason="REAL_GTFS_TESTS non activé")

# Date figée dans la plage connue du flux communautaire
TRAVEL_DATE = date(2024, 10, 15)  # mardi


@pytest.fixture(scope="module")
def real_store() -> Store:
    gtfs_zip = download_gtfs(GTFS_DEV_URL)
    store = Store(":memory:")
    store.connect()
    ingest_gtfs(store, gtfs_zip, respect_feed_dates=False)
    return store


def test_coverage_report_partial(real_store: Store) -> None:
    """Le flux communautaire a des lignes sans horaires — signalé, pas masqué."""
    cov = real_store.get_meta("coverage_pct")
    assert cov is not None
    # On sait que 4/9 lignes sont vides → couverture < 100%
    assert float(cov) < 100.0


def test_tanger_casa_direct(real_store: Store) -> None:
    """Tanger→Casa : trajet direct existant dans le flux."""
    journeys = plan_trip(real_store, "Tanger", "Casa", TRAVEL_DATE, respect_feed_dates=False)
    assert len(journeys) > 0
    assert any(j.nb_transfers == 0 for j in journeys)


def test_fes_marrakech_correspondance(real_store: Store) -> None:
    """Fès→Marrakech : requiert une correspondance (typiquement Casa)."""
    journeys = plan_trip(real_store, "Fès", "Marrakech", TRAVEL_DATE, respect_feed_dates=False)
    # S'il y a des données, il doit y avoir au moins 1 trajet avec correspondance
    # S'il n'y a pas de données (lignes vides), NoDataError est correct
    if journeys:
        assert any(j.nb_transfers >= 1 for j in journeys)


def test_od_non_couvert(real_store: Store) -> None:
    """Une ligne connue sans horaires renvoie NoDataError — jamais un trajet inventé."""
    # NADOR n'a pas d'horaires dans le flux communautaire
    with pytest.raises(NoDataError):
        plan_trip(
            real_store,
            "Nador",
            "Casa",
            TRAVEL_DATE,
            respect_feed_dates=False,
        )
