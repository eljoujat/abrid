"""CLI d'ingestion GTFS.

Usage :
    python -m scripts.ingest [--url URL] [--db DB_PATH] [--local-zip PATH]

Par défaut : télécharge le flux communautaire et construit data/oncf.db.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

# Assure que le package est importable depuis la racine
sys.path.insert(0, str(Path(__file__).parent.parent))

from oncf_transit.core.gtfs_loader import GTFS_DEV_URL, download_gtfs, ingest_gtfs
from oncf_transit.core.store import Store

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingestion GTFS → base Abrid")
    parser.add_argument("--url", default=GTFS_DEV_URL, help="URL du ZIP GTFS")
    parser.add_argument(
        "--local-zip", help="Chemin vers un ZIP GTFS local (ignore --url)"
    )
    parser.add_argument(
        "--db",
        default=str(Path(__file__).parent.parent / "data" / "oncf.db"),
        help="Chemin de la base SQLite cible",
    )
    parser.add_argument(
        "--respect-feed-dates",
        action="store_true",
        help="Respecter les bornes de validité du flux (mode prod)",
    )
    args = parser.parse_args()

    # Récupération du ZIP
    if args.local_zip:
        logger.info("Lecture du ZIP local : %s", args.local_zip)
        gtfs_zip = Path(args.local_zip).read_bytes()
        source_url = f"file://{Path(args.local_zip).resolve()}"
    else:
        gtfs_zip = download_gtfs(args.url)
        source_url = args.url

    # Ingestion
    store = Store(args.db)
    with store:
        report = ingest_gtfs(
            store,
            gtfs_zip,
            source_url=source_url,
            respect_feed_dates=args.respect_feed_dates,
        )

    print(report)

    if report.routes_without_trips:
        logger.warning(
            "%d ligne(s) sans horaires : %s",
            len(report.routes_without_trips),
            report.routes_without_trips,
        )
    logger.info("Base construite : %s", args.db)


if __name__ == "__main__":
    main()
