package ma.mobility.abrid.core.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Initialise le schéma SQLite au démarrage.
 * Idempotent grâce aux clauses IF NOT EXISTS.
 * Seule couche qui connaît la structure physique de la base.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        log.info("Initialisation du schéma SQLite…");

        // Pragmas SQLite
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA foreign_keys=ON");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS stations (
                id    TEXT PRIMARY KEY,
                name  TEXT NOT NULL,
                lat   REAL,
                lon   REAL,
                mode  TEXT NOT NULL DEFAULT 'TRAIN'
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS station_aliases (
                station_id  TEXT NOT NULL REFERENCES stations(id),
                alias       TEXT NOT NULL,
                PRIMARY KEY (station_id, alias)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS routes (
                id          TEXT PRIMARY KEY,
                short_name  TEXT,
                long_name   TEXT,
                mode        TEXT NOT NULL DEFAULT 'TRAIN'
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS trips (
                id          TEXT PRIMARY KEY,
                route_id    TEXT NOT NULL REFERENCES routes(id),
                service_id  TEXT NOT NULL,
                headsign    TEXT
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS stop_times (
                trip_id            TEXT NOT NULL REFERENCES trips(id),
                stop_id            TEXT NOT NULL REFERENCES stations(id),
                stop_sequence      INTEGER NOT NULL,
                departure_seconds  INTEGER NOT NULL,
                arrival_seconds    INTEGER NOT NULL,
                PRIMARY KEY (trip_id, stop_sequence)
            )
            """);

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_st_stop ON stop_times(stop_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_st_trip ON stop_times(trip_id)");

        jdbc.execute("""
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
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calendar_dates (
                service_id      TEXT NOT NULL,
                date            TEXT NOT NULL,
                exception_type  INTEGER NOT NULL,
                PRIMARY KEY (service_id, date)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS fares (
                route_id    TEXT NOT NULL,
                from_stop   TEXT NOT NULL,
                to_stop     TEXT NOT NULL,
                fare_mad    REAL NOT NULL,
                fare_class  TEXT,
                PRIMARY KEY (route_id, from_stop, to_stop)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ingestion_meta (
                key    TEXT PRIMARY KEY,
                value  TEXT NOT NULL
            )
            """);

        log.info("Schéma SQLite prêt.");
    }
}
