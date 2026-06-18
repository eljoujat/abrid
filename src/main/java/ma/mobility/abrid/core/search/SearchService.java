package ma.mobility.abrid.core.search;

import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Leg;
import ma.mobility.abrid.core.model.Mode;
import ma.mobility.abrid.core.model.Station;
import ma.mobility.abrid.core.store.StoreRepository;
import ma.mobility.abrid.core.time.TimeUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Moteur de recherche de trajets — multimodal, sans connaissance du format source.
 *
 * <p>Niveaux de recherche :
 * <ol>
 *   <li>Résolution de gare : texte libre → Station (insensible aux accents)</li>
 *   <li>Trajets directs : même trip passant par A puis B</li>
 *   <li>Correspondance simple : A→hub→B (hubs principaux)</li>
 * </ol>
 *
 * <p>Fallback SQL pour {@link JourneySearchPort} : toujours disponible,
 * utilisé quand OTP est désactivé ou en erreur.
 */
@Service
public class SearchService implements JourneySearchPort {

    /** Hubs utilisés pour la correspondance simple (remplacé par OTP au Lot 2). */
    private static final List<String> HUB_IDS = List.of(
        "CASA_VOYAGEURS", "CASA_PORT", "RABAT_VILLE", "KENITRA", "FES", "MEKNES", "SALE"
    );

    /** Marge minimale de correspondance en secondes (10 min). */
    private static final int TRANSFER_MARGIN_SEC = 600;

    private final StoreRepository store;

    @Value("${gtfs.respect-feed-dates:false}")
    private boolean respectFeedDates;

    public SearchService(StoreRepository store) {
        this.store = store;
    }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    /**
     * Résout un nom de gare en objet Station.
     *
     * <p>Ordre de résolution :
     * <ol>
     *   <li>ID exact</li>
     *   <li>Nom exact (insensible à la casse)</li>
     *   <li>Nom normalisé (sans accents)</li>
     *   <li>Alias normalisé</li>
     *   <li>Correspondance partielle sur le nom</li>
     * </ol>
     *
     * @throws StationNotFoundException si aucune gare ne correspond.
     * @throws AmbiguousStationException si plusieurs gares correspondent.
     */
    public Station resolveStation(String query) {
        var all = store.getAllStations();
        String qNorm = TimeUtils.normalize(query);

        // 1. ID exact
        for (var row : all) {
            if (str(row, "id").equalsIgnoreCase(query)) return toStation(row);
        }

        // 2. Nom exact (insensible à la casse)
        for (var row : all) {
            if (str(row, "name").equalsIgnoreCase(query)) return toStation(row);
        }

        // 3. Nom normalisé exact
        var byNorm = all.stream()
            .filter(r -> TimeUtils.normalize(str(r, "name")).equals(qNorm))
            .toList();
        if (byNorm.size() == 1) return toStation(byNorm.getFirst());
        if (byNorm.size() > 1) throw new AmbiguousStationException(
            query, byNorm.stream().map(r -> str(r, "name")).toList());

        // 4. Alias normalisé
        for (var row : all) {
            var aliases = store.getAliases(str(row, "id"));
            if (aliases.contains(qNorm)) return toStation(row);
        }

        // 5. Partiel sur le nom
        var partial = all.stream()
            .filter(r -> TimeUtils.normalize(str(r, "name")).contains(qNorm))
            .toList();
        if (partial.size() == 1) return toStation(partial.getFirst());
        if (partial.size() > 1) throw new AmbiguousStationException(
            query, partial.stream().map(r -> str(r, "name")).toList());

        throw new StationNotFoundException(query);
    }

    /**
     * Cherche les trajets directs entre deux gares à une date donnée.
     *
     * @return Liste triée par heure de départ. Vide si aucun direct.
     */
    public List<Journey> findDirectTrips(
            Station from, Station to, LocalDate date, int minDepSec) {

        var rows = store.findDirectStopTimes(from.id(), to.id(), minDepSec);
        var journeys = new ArrayList<Journey>();

        for (var row : rows) {
            String serviceId = str(row, "service_id");
            if (!isServiceActive(serviceId, date)) continue;

            String routeId  = str(row, "route_id");
            String routeName = store.getRoute(routeId)
                .map(r -> coalesce(str(r, "short_name"), str(r, "long_name"), routeId))
                .orElse(routeId);

            var leg = new Leg(
                from, to,
                (int) row.get("dep_sec"),
                (int) row.get("arr_sec"),
                Mode.TRAIN,
                str(row, "trip_id"),
                routeId,
                routeName,
                str(row, "headsign"),
                null
            );
            journeys.add(new Journey(
                List.of(leg),
                store.getMeta("source_url").orElse(""),
                store.getMeta("ingested_at").orElse("")
            ));
        }
        return journeys;
    }

    /**
     * Cherche les trajets avec exactement 1 correspondance via un hub.
     *
     * @return Liste triée par heure de départ.
     */
    public List<Journey> findTripsWithTransfer(
            Station from, Station to, LocalDate date) {

        var journeys = new ArrayList<Journey>();

        for (String hubId : HUB_IDS) {
            Station hub;
            try {
                hub = resolveStation(hubId);
            } catch (StationNotFoundException | AmbiguousStationException e) {
                continue;
            }
            if (hub.id().equals(from.id()) || hub.id().equals(to.id())) continue;

            var legs1 = findDirectTrips(from, hub, date, 0);
            if (legs1.isEmpty()) continue;

            var legs2 = findDirectTrips(hub, to, date, 0);
            if (legs2.isEmpty()) continue;

            for (var j1 : legs1) {
                int arrHub = j1.arrivalSec();
                for (var j2 : legs2) {
                    if (j2.departureSec() >= arrHub + TRANSFER_MARGIN_SEC) {
                        journeys.add(new Journey(
                            List.of(j1.legs().getFirst(), j2.legs().getFirst()),
                            j1.dataSource(),
                            j1.dataFreshnessDate()
                        ));
                        break; // premier valide pour ce j1
                    }
                }
            }
        }
        journeys.sort(Comparator.comparingInt(Journey::departureSec));
        return journeys;
    }

    /**
     * Implémentation de {@link JourneySearchPort} — moteur SQL.
     * Appelé directement si OTP est désactivé, ou en fallback circuit-breaker.
     */
    @Override
    public List<Journey> planTrip(Station from, Station to, LocalDate date, int minDepSec) {
        var journeys = findDirectTrips(from, to, date, minDepSec);
        if (journeys.isEmpty()) {
            journeys = findTripsWithTransfer(from, to, date);
        }
        if (journeys.isEmpty()) {
            throw new NoDataException(from.name(), to.name(), date.toString());
        }
        return journeys;
    }

    /**
     * Point d'entrée avec résolution de gare par nom (utilisé par le contrôleur
     * quand OTP est désactivé).
     *
     * @throws StationNotFoundException si A ou B est introuvable.
     * @throws NoDataException          si aucun trajet n'existe (NE PAS inventer).
     */
    public List<Journey> planTrip(String fromQuery, String toQuery, LocalDate date) {
        var from = resolveStation(fromQuery);
        var to   = resolveStation(toQuery);
        return planTrip(from, to, date, 0);
    }

    // -------------------------------------------------------------------------
    // Utilitaires internes
    // -------------------------------------------------------------------------

    private boolean isServiceActive(String serviceId, LocalDate date) {
        String dateStr = TimeUtils.toGtfsDate(date);

        // Exceptions ponctuelles (priorité sur le calendrier hebdo)
        for (var exc : store.getCalendarExceptions(serviceId)) {
            if (dateStr.equals(str(exc, "date"))) {
                return (int) exc.get("exception_type") == 1;
            }
        }

        // Calendrier hebdomadaire
        return store.getCalendar(serviceId).map(cal -> {
            boolean[] weekdays = {
                intBool(cal, "monday"), intBool(cal, "tuesday"),
                intBool(cal, "wednesday"), intBool(cal, "thursday"),
                intBool(cal, "friday"), intBool(cal, "saturday"),
                intBool(cal, "sunday")
            };
            String startStr = str(cal, "start_date");
            String endStr   = str(cal, "end_date");
            LocalDate start = startStr.isBlank() ? date : TimeUtils.parseGtfsDate(startStr);
            LocalDate end   = endStr.isBlank()   ? date : TimeUtils.parseGtfsDate(endStr);
            return TimeUtils.isServiceActive(date, start, end, weekdays, respectFeedDates);
        }).orElse(true); // pas de calendrier → supposé actif (données incomplètes)
    }

    private Station toStation(Map<String, Object> row) {
        String id   = str(row, "id");
        String mode = str(row, "mode");
        return new Station(
            id,
            str(row, "name"),
            dbl(row, "lat"),
            dbl(row, "lon"),
            modeOf(mode),
            store.getAliases(id)
        );
    }

    private static Mode modeOf(String mode) {
        try { return Mode.valueOf(mode); } catch (Exception e) { return Mode.TRAIN; }
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : v.toString();
    }

    private static Double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        if (v instanceof Double d) return d;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static boolean intBool(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return false;
        if (v instanceof Integer i) return i != 0;
        if (v instanceof Long l)    return l != 0;
        try { return Integer.parseInt(v.toString()) != 0; } catch (NumberFormatException e) { return false; }
    }

    private static String coalesce(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
