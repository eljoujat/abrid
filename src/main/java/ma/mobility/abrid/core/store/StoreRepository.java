package ma.mobility.abrid.core.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accès à la base de données — couche données uniquement.
 * Aucune logique métier ici : déléguer à SearchService.
 *
 * <p><strong>Stratégie d'upsert :</strong> DELETE + INSERT dans la même transaction.
 * Compatible PostgreSQL 16 et H2 2.x (sans les contraintes sur EXCLUDED / ON CONFLICT).
 * Sûr car toutes les opérations d'ingestion sont appelées dans un contexte
 * {@code @Transactional} après {@link #purgeAll()}.
 */
@Repository
public class StoreRepository {

    private final JdbcTemplate jdbc;

    public StoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Métadonnées d'ingestion
    // Colonnes : meta_key / meta_value  (key et value sont réservés en H2)
    // -------------------------------------------------------------------------

    public void setMeta(String key, String value) {
        jdbc.update("DELETE FROM ingestion_meta WHERE meta_key = ?", key);
        jdbc.update("INSERT INTO ingestion_meta(meta_key, meta_value) VALUES (?, ?)", key, value);
    }

    public Optional<String> getMeta(String key) {
        var rows = jdbc.queryForList(
            "SELECT meta_value FROM ingestion_meta WHERE meta_key = ?", key
        );
        return rows.isEmpty() ? Optional.empty()
                : Optional.ofNullable((String) rows.getFirst().get("meta_value"));
    }

    // -------------------------------------------------------------------------
    // Purge (idempotence — ne purge PAS ingestion_meta ni disruptions)
    // -------------------------------------------------------------------------

    public void purgeAll() {
        // Ordre respectant les contraintes logiques (tables feuilles d'abord)
        for (String table : List.of(
            "stop_times", "calendar_dates", "fares", "calendar",
            "trips", "station_aliases", "stations", "routes"
        )) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // -------------------------------------------------------------------------
    // Ingestion — gares
    // (purgeAll() est toujours appelé avant, donc pas de conflit possible)
    // -------------------------------------------------------------------------

    public void insertStation(String id, String name, Double lat, Double lon, String mode) {
        jdbc.update(
            "INSERT INTO stations(id, name, lat, lon, mode) VALUES (?,?,?,?,?)",
            id, name, lat, lon, mode
        );
    }

    public void insertAlias(String stationId, String alias) {
        // INSERT IGNORE / ON CONFLICT DO NOTHING : H2 supporte ON CONFLICT DO NOTHING
        jdbc.update("""
            INSERT INTO station_aliases(station_id, alias) VALUES (?,?)
            ON CONFLICT DO NOTHING
            """, stationId, alias);
    }

    // -------------------------------------------------------------------------
    // Ingestion — lignes, services, trajets
    // -------------------------------------------------------------------------

    public void insertRoute(String id, String shortName, String longName, String mode) {
        jdbc.update(
            "INSERT INTO routes(id, short_name, long_name, mode) VALUES (?,?,?,?)",
            id, shortName, longName, mode
        );
    }

    public void insertTrip(String id, String routeId, String serviceId, String headsign) {
        jdbc.update(
            "INSERT INTO trips(id, route_id, service_id, headsign) VALUES (?,?,?,?)",
            id, routeId, serviceId, headsign
        );
    }

    public void insertCalendar(String serviceId,
                               int mon, int tue, int wed, int thu, int fri, int sat, int sun,
                               String startDate, String endDate) {
        jdbc.update("""
            INSERT INTO calendar
            (service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
             start_date, end_date)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, serviceId, mon, tue, wed, thu, fri, sat, sun, startDate, endDate);
    }

    public void insertCalendarDate(String serviceId, String date, int exceptionType) {
        jdbc.update(
            "INSERT INTO calendar_dates(service_id, date, exception_type) VALUES (?,?,?)",
            serviceId, date, exceptionType
        );
    }

    public void insertStopTime(String tripId, String stopId, int seq, int depSec, int arrSec) {
        jdbc.update("""
            INSERT INTO stop_times
            (trip_id, stop_id, stop_sequence, departure_seconds, arrival_seconds)
            VALUES (?,?,?,?,?)
            """, tripId, stopId, seq, depSec, arrSec);
    }

    // -------------------------------------------------------------------------
    // Lecture — gares
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> getAllStations() {
        return jdbc.queryForList("SELECT * FROM stations ORDER BY name");
    }

    public List<String> getAliases(String stationId) {
        return jdbc.queryForList(
            "SELECT alias FROM station_aliases WHERE station_id = ?", stationId
        ).stream()
         .map(r -> (String) r.get("alias"))
         .toList();
    }

    // -------------------------------------------------------------------------
    // Lecture — horaires
    // -------------------------------------------------------------------------

    /**
     * Retourne les trajets directs de fromStop vers toStop (sens respecté).
     */
    public List<Map<String, Object>> findDirectStopTimes(
            String fromStopId, String toStopId, int minDepartureSec) {
        return jdbc.queryForList("""
            SELECT st_from.trip_id,
                   st_from.departure_seconds AS dep_sec,
                   st_to.arrival_seconds     AS arr_sec,
                   t.route_id,
                   t.service_id,
                   t.headsign
            FROM   stop_times st_from
            JOIN   stop_times st_to
                ON st_from.trip_id      = st_to.trip_id
               AND st_to.stop_id        = ?
               AND st_to.stop_sequence  > st_from.stop_sequence
            JOIN   trips t ON st_from.trip_id = t.id
            WHERE  st_from.stop_id            = ?
              AND  st_from.departure_seconds  >= ?
            ORDER  BY st_from.departure_seconds
            """, toStopId, fromStopId, minDepartureSec);
    }

    public List<Map<String, Object>> getStopTimesForStop(String stopId) {
        return jdbc.queryForList("""
            SELECT st.*, t.route_id, t.service_id, t.headsign
            FROM   stop_times st
            JOIN   trips t ON st.trip_id = t.id
            WHERE  st.stop_id = ?
            ORDER  BY st.departure_seconds
            """, stopId);
    }

    public Optional<Map<String, Object>> getCalendar(String serviceId) {
        var rows = jdbc.queryForList(
            "SELECT * FROM calendar WHERE service_id = ?", serviceId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> getCalendarExceptions(String serviceId) {
        return jdbc.queryForList(
            "SELECT * FROM calendar_dates WHERE service_id = ?", serviceId
        );
    }

    public Optional<Map<String, Object>> getRoute(String routeId) {
        var rows = jdbc.queryForList("SELECT * FROM routes WHERE id = ?", routeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    // -------------------------------------------------------------------------
    // Statistiques (rapport de couverture)
    // -------------------------------------------------------------------------

    public int countStations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stations", Integer.class);
    }

    public int countTrips() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class);
    }

    public int countStopTimes() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stop_times", Integer.class);
    }

    public List<String> getAllRouteIds() {
        return jdbc.queryForList("SELECT id FROM routes", String.class);
    }

    public List<String> getRouteIdsWithTrips() {
        return jdbc.queryForList("SELECT DISTINCT route_id FROM trips", String.class);
    }

    // -------------------------------------------------------------------------
    // Perturbations temps réel
    // -------------------------------------------------------------------------

    /**
     * Insère une perturbation et retourne son ID généré.
     */
    public long insertDisruption(String routeId, String stopId,
                                  String type, String severity, String description,
                                  Instant startsAt, Instant endsAt, String source) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                INSERT INTO disruptions
                (route_id, stop_id, type, severity, description, starts_at, ends_at, source)
                VALUES (?,?,?,?,?,?,?,?)
                """, new String[]{"id"});
            ps.setString(1, routeId);
            ps.setString(2, stopId);
            ps.setString(3, type);
            ps.setString(4, severity);
            ps.setString(5, description);
            ps.setObject(6, startsAt != null ? java.sql.Timestamp.from(startsAt) : null);
            ps.setObject(7, endsAt   != null ? java.sql.Timestamp.from(endsAt)   : null);
            ps.setString(8, source);
            return ps;
        }, keyHolder);
        return ((Number) keyHolder.getKeys().get("id")).longValue();
    }

    /**
     * Retourne les perturbations actives à l'instant donné.
     */
    public List<Map<String, Object>> findActiveDisruptions(Instant now) {
        var ts = java.sql.Timestamp.from(now);
        return jdbc.queryForList("""
            SELECT * FROM disruptions
            WHERE  (starts_at IS NULL OR starts_at <= ?)
              AND  (ends_at   IS NULL OR ends_at   >= ?)
            ORDER  BY severity, starts_at
            """, ts, ts);
    }

    /**
     * Retourne les perturbations actives filtrées par ligne.
     */
    public List<Map<String, Object>> findActiveDisruptionsByRoute(String routeId, Instant now) {
        var ts = java.sql.Timestamp.from(now);
        return jdbc.queryForList("""
            SELECT * FROM disruptions
            WHERE  route_id = ?
              AND  (starts_at IS NULL OR starts_at <= ?)
              AND  (ends_at   IS NULL OR ends_at   >= ?)
            ORDER  BY severity, starts_at
            """, routeId, ts, ts);
    }

    /**
     * Supprime les perturbations expirées (nettoyage périodique).
     */
    public int purgeExpiredDisruptions(Instant before) {
        return jdbc.update(
            "DELETE FROM disruptions WHERE ends_at IS NOT NULL AND ends_at < ?",
            java.sql.Timestamp.from(before)
        );
    }
}
