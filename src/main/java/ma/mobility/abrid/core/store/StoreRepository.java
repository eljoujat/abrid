package ma.mobility.abrid.core.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accès à la base de données — couche données uniquement.
 * Aucune logique métier ici : déléguer à SearchService.
 * Utilise JdbcTemplate (Core suffit, JPA non nécessaire).
 */
@Repository
public class StoreRepository {

    private final JdbcTemplate jdbc;

    public StoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Métadonnées d'ingestion
    // -------------------------------------------------------------------------

    public void setMeta(String key, String value) {
        jdbc.update(
            "INSERT OR REPLACE INTO ingestion_meta(key, value) VALUES (?, ?)",
            key, value
        );
    }

    public Optional<String> getMeta(String key) {
        var rows = jdbc.queryForList(
            "SELECT value FROM ingestion_meta WHERE key = ?", key
        );
        return rows.isEmpty() ? Optional.empty()
                : Optional.ofNullable((String) rows.getFirst().get("value"));
    }

    // -------------------------------------------------------------------------
    // Purge (idempotence)
    // -------------------------------------------------------------------------

    public void purgeAll() {
        for (String table : List.of(
            "stop_times", "calendar_dates", "calendar",
            "trips", "fares", "station_aliases", "stations", "routes"
        )) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    // -------------------------------------------------------------------------
    // Ingestion
    // -------------------------------------------------------------------------

    public void insertStation(String id, String name, Double lat, Double lon, String mode) {
        jdbc.update(
            "INSERT OR REPLACE INTO stations(id, name, lat, lon, mode) VALUES (?,?,?,?,?)",
            id, name, lat, lon, mode
        );
    }

    public void insertAlias(String stationId, String alias) {
        jdbc.update(
            "INSERT OR IGNORE INTO station_aliases(station_id, alias) VALUES (?,?)",
            stationId, alias
        );
    }

    public void insertRoute(String id, String shortName, String longName, String mode) {
        jdbc.update(
            "INSERT OR REPLACE INTO routes(id, short_name, long_name, mode) VALUES (?,?,?,?)",
            id, shortName, longName, mode
        );
    }

    public void insertTrip(String id, String routeId, String serviceId, String headsign) {
        jdbc.update(
            "INSERT OR REPLACE INTO trips(id, route_id, service_id, headsign) VALUES (?,?,?,?)",
            id, routeId, serviceId, headsign
        );
    }

    public void insertCalendar(String serviceId,
                               int mon, int tue, int wed, int thu, int fri, int sat, int sun,
                               String startDate, String endDate) {
        jdbc.update("""
            INSERT OR REPLACE INTO calendar
            (service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
             start_date, end_date)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, serviceId, mon, tue, wed, thu, fri, sat, sun, startDate, endDate);
    }

    public void insertCalendarDate(String serviceId, String date, int exceptionType) {
        jdbc.update(
            "INSERT OR REPLACE INTO calendar_dates(service_id, date, exception_type) VALUES (?,?,?)",
            serviceId, date, exceptionType
        );
    }

    public void insertStopTime(String tripId, String stopId, int seq, int depSec, int arrSec) {
        jdbc.update("""
            INSERT OR REPLACE INTO stop_times
            (trip_id, stop_id, stop_sequence, departure_seconds, arrival_seconds)
            VALUES (?,?,?,?,?)
            """, tripId, stopId, seq, depSec, arrSec);
    }

    // -------------------------------------------------------------------------
    // Lecture gares
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> getAllStations() {
        return jdbc.queryForList("SELECT * FROM stations");
    }

    public List<String> getAliases(String stationId) {
        return jdbc.queryForList(
            "SELECT alias FROM station_aliases WHERE station_id = ?", stationId
        ).stream()
         .map(r -> (String) r.get("alias"))
         .toList();
    }

    // -------------------------------------------------------------------------
    // Lecture horaires
    // -------------------------------------------------------------------------

    /**
     * Retourne les trajets directs de fromStop vers toStop (sens respecté).
     * Utilise une auto-jointure sur stop_times pour éviter le filtrage en Java.
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
}
