"""Utilitaires de manipulation du temps pour les horaires de transport.

Convention : le temps est exprimé en **secondes depuis minuit du jour de service**.
Cette convention permet de gérer les services passant minuit sans ambiguïté
(ex. GTFS "25:20:00" = 91200 secondes, soit 01:20 le lendemain).
"""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta

_GTFS_TIME_RE = re.compile(r"^(\d{1,3}):([0-5]\d):([0-5]\d)$")


def gtfs_time_to_seconds(gtfs_time: str) -> int:
    """Convertit une chaîne de temps GTFS en secondes depuis minuit.

    Accepte les horaires > 24:00:00 (service passant minuit).

    >>> gtfs_time_to_seconds("08:30:00")
    30600
    >>> gtfs_time_to_seconds("25:20:00")
    91200
    """
    m = _GTFS_TIME_RE.match(gtfs_time.strip())
    if not m:
        raise ValueError(f"Format de temps GTFS invalide : {gtfs_time!r}")
    h, mn, s = int(m.group(1)), int(m.group(2)), int(m.group(3))
    return h * 3600 + mn * 60 + s


def seconds_to_hhmm(seconds: int) -> str:
    """Formate des secondes en 'HH:MM' pour l'affichage.

    Supporte les valeurs > 86400 (service passant minuit).

    >>> seconds_to_hhmm(30600)
    '08:30'
    >>> seconds_to_hhmm(91200)
    '25:20'
    """
    h = seconds // 3600
    mn = (seconds % 3600) // 60
    return f"{h:02d}:{mn:02d}"


def seconds_to_display(seconds: int) -> str:
    """Formate en 'HH:MM' avec indication J+1 si passé minuit.

    >>> seconds_to_display(30600)
    '08:30'
    >>> seconds_to_display(91200)
    '01:20 (J+1)'
    """
    if seconds >= 86400:
        real = seconds - 86400
        h = real // 3600
        mn = (real % 3600) // 60
        return f"{h:02d}:{mn:02d} (J+1)"
    return seconds_to_hhmm(seconds)


def service_date_to_date(service_date: str) -> date:
    """Convertit une date GTFS 'YYYYMMDD' en objet date.

    >>> service_date_to_date("20240901")
    datetime.date(2024, 9, 1)
    """
    return datetime.strptime(service_date, "%Y%m%d").date()


def date_to_service_date(d: date) -> str:
    """Convertit un objet date en chaîne GTFS 'YYYYMMDD'.

    >>> date_to_service_date(date(2024, 9, 1))
    '20240901'
    """
    return d.strftime("%Y%m%d")


# Jours de la semaine dans l'ordre GTFS (0=lundi, 6=dimanche)
_WEEKDAY_NAMES = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def is_service_active(
    d: date,
    start_date: date,
    end_date: date,
    weekdays: list[bool],
    respect_feed_dates: bool = True,
) -> bool:
    """Détermine si un service GTFS est actif à la date donnée.

    Args:
        d: Date à tester.
        start_date: Début de validité du service.
        end_date: Fin de validité du service.
        weekdays: Liste de 7 booléens [lun, mar, mer, jeu, ven, sam, dim].
        respect_feed_dates: Si False, ignore les bornes de validité (mode dev).

    Returns:
        True si le service opère ce jour.
    """
    if respect_feed_dates:
        if d < start_date or d > end_date:
            return False
    return weekdays[d.weekday()]


def add_days(d: date, n: int) -> date:
    """Ajoute n jours à une date."""
    return d + timedelta(days=n)
