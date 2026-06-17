-- V1__initial_schema.sql
-- Schéma principal Abrid — compatible H2 (MODE=PostgreSQL) et PostgreSQL 16
-- Aucune contrainte FK pour permettre l'ingestion GTFS en bulk sans ordre strict.
-- L'intégrité référentielle est gérée par la couche loader.

CREATE TABLE IF NOT EXISTS stations (
    id    VARCHAR(255) PRIMARY KEY,
    name  VARCHAR(255) NOT NULL,
    lat   DOUBLE PRECISION,
    lon   DOUBLE PRECISION,
    mode  VARCHAR(50)  NOT NULL DEFAULT 'TRAIN'
);

CREATE TABLE IF NOT EXISTS station_aliases (
    station_id  VARCHAR(255) NOT NULL,
    alias       VARCHAR(255) NOT NULL,
    PRIMARY KEY (station_id, alias)
);

CREATE TABLE IF NOT EXISTS routes (
    id          VARCHAR(255) PRIMARY KEY,
    short_name  VARCHAR(255),
    long_name   VARCHAR(255),
    mode        VARCHAR(50)  NOT NULL DEFAULT 'TRAIN'
);

CREATE TABLE IF NOT EXISTS trips (
    id          VARCHAR(255) PRIMARY KEY,
    route_id    VARCHAR(255) NOT NULL,
    service_id  VARCHAR(255) NOT NULL,
    headsign    VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS stop_times (
    trip_id            VARCHAR(255) NOT NULL,
    stop_id            VARCHAR(255) NOT NULL,
    stop_sequence      INTEGER      NOT NULL,
    departure_seconds  INTEGER      NOT NULL,
    arrival_seconds    INTEGER      NOT NULL,
    PRIMARY KEY (trip_id, stop_sequence)
);

CREATE INDEX IF NOT EXISTS idx_st_stop ON stop_times(stop_id);
CREATE INDEX IF NOT EXISTS idx_st_trip ON stop_times(trip_id);
-- Index composite pour la requête findDirectStopTimes (départ ≥ X depuis un stop)
CREATE INDEX IF NOT EXISTS idx_st_stop_dep ON stop_times(stop_id, departure_seconds);

CREATE TABLE IF NOT EXISTS calendar (
    service_id  VARCHAR(255) PRIMARY KEY,
    monday      SMALLINT     NOT NULL,
    tuesday     SMALLINT     NOT NULL,
    wednesday   SMALLINT     NOT NULL,
    thursday    SMALLINT     NOT NULL,
    friday      SMALLINT     NOT NULL,
    saturday    SMALLINT     NOT NULL,
    sunday      SMALLINT     NOT NULL,
    start_date  VARCHAR(10)  NOT NULL,
    end_date    VARCHAR(10)  NOT NULL
);

CREATE TABLE IF NOT EXISTS calendar_dates (
    service_id      VARCHAR(255) NOT NULL,
    date            VARCHAR(10)  NOT NULL,
    exception_type  SMALLINT     NOT NULL,
    PRIMARY KEY (service_id, date)
);

CREATE TABLE IF NOT EXISTS fares (
    route_id    VARCHAR(255) NOT NULL,
    from_stop   VARCHAR(255) NOT NULL,
    to_stop     VARCHAR(255) NOT NULL,
    fare_mad    DOUBLE PRECISION NOT NULL,
    fare_class  VARCHAR(50),
    PRIMARY KEY (route_id, from_stop, to_stop)
);

-- Métadonnées d'ingestion (non purgées entre ingestions)
CREATE TABLE IF NOT EXISTS ingestion_meta (
    meta_key    VARCHAR(255) PRIMARY KEY,
    meta_value  TEXT         NOT NULL
);
