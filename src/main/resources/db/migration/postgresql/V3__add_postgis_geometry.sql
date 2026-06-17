-- V3__add_postgis_geometry.sql
-- Colonne géométrique PostGIS sur les gares.
-- UNIQUEMENT appliqué sur PostgreSQL (ce dossier n'est pas inclus dans le profil H2).
-- Permet les requêtes spatiales : gare la plus proche, isochrone, etc. (Lot 5).

CREATE EXTENSION IF NOT EXISTS postgis;

-- Ajout de la colonne géométrique POINT (SRID 4326 = WGS84)
ALTER TABLE stations ADD COLUMN IF NOT EXISTS geom GEOMETRY(POINT, 4326);

-- Backfill depuis lat/lon existants
UPDATE stations
SET    geom = ST_SetSRID(ST_MakePoint(lon, lat), 4326)
WHERE  lat IS NOT NULL
  AND  lon IS NOT NULL
  AND  geom IS NULL;

-- Index spatial R-tree (GIST)
CREATE INDEX IF NOT EXISTS idx_stations_geom ON stations USING GIST (geom);
