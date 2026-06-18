package ma.mobility.abrid.core.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Leg;
import ma.mobility.abrid.core.model.Mode;
import ma.mobility.abrid.core.model.Station;
import ma.mobility.abrid.core.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moteur de recherche de trajets via OpenTripPlanner 2.x.
 *
 * <p>Actif uniquement si {@code app.otp.enabled=true} (voir {@code application.yml}).
 * En cas d'échec (OTP down, timeout, réponse invalide), le circuit breaker
 * bascule automatiquement sur le {@link SearchService} SQL.
 *
 * <p>Utilise l'API REST plan d'OTP :
 * {@code GET /otp/routers/default/plan?fromPlace=...&toPlace=...}
 *
 * <p>Le mapping OTP → domaine respecte les principes du brief :
 * <ul>
 *   <li>Aucun concept GTFS brut n'est exposé hors de ce package</li>
 *   <li>Si OTP ne trouve rien → {@link NoDataException} (jamais inventer)</li>
 * </ul>
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.otp.enabled", havingValue = "true")
public class OtpJourneySearchService implements JourneySearchPort {

    private static final Logger log = LoggerFactory.getLogger(OtpJourneySearchService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final RestClient       otpClient;
    private final SearchService    sqlFallback;
    private final StoreRepository  store;
    private final ObjectMapper     mapper;

    @Value("${app.otp.gtfs-feed-id:1}")
    private String feedId;

    @Value("${app.otp.timezone:Africa/Casablanca}")
    private String timezone;

    public OtpJourneySearchService(RestClient otpClient,
                                   SearchService sqlFallback,
                                   StoreRepository store,
                                   ObjectMapper mapper) {
        this.otpClient   = otpClient;
        this.sqlFallback = sqlFallback;
        this.store       = store;
        this.mapper      = mapper;
    }

    // -------------------------------------------------------------------------
    // JourneySearchPort — point d'entrée principal avec circuit breaker
    // -------------------------------------------------------------------------

    /**
     * Planifie un trajet via OTP. En cas d'erreur, le circuit breaker appelle
     * automatiquement {@link #fallbackPlanTrip}.
     */
    @Override
    @CircuitBreaker(name = "otp", fallbackMethod = "fallbackPlanTrip")
    public List<Journey> planTrip(Station from, Station to, LocalDate date, int minDepSec) {

        if (from.lat() == null || from.lon() == null || to.lat() == null || to.lon() == null) {
            log.warn("OTP : coordonnées manquantes pour {} ou {}, fallback SQL.", from.name(), to.name());
            return sqlFallback.planTrip(from, to, date, minDepSec);
        }

        ZoneId tz       = ZoneId.of(timezone);
        ZonedDateTime midnight = date.atStartOfDay(tz);
        ZonedDateTime earliest = midnight.plusSeconds(minDepSec);

        String url = UriComponentsBuilder.fromPath("/otp/routers/default/plan")
            .queryParam("fromPlace",      "{fromName}::{fromLat},{fromLon}")
            .queryParam("toPlace",        "{toName}::{toLat},{toLon}")
            .queryParam("date",           date.format(DATE_FMT))
            .queryParam("time",           earliest.format(TIME_FMT))
            .queryParam("mode",           "RAIL,WALK")
            .queryParam("numItineraries", 10)
            .queryParam("arriveBy",       false)
            .build(Map.of(
                "fromName", from.name(), "fromLat", from.lat(), "fromLon", from.lon(),
                "toName",   to.name(),   "toLat",   to.lat(),   "toLon",   to.lon()
            )).toString();

        log.debug("OTP request : {}", url);

        String json = otpClient.get()
            .uri(url)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String.class);

        List<Journey> journeys = parseOtpResponse(json, date, from, to);

        if (journeys.isEmpty()) {
            throw new NoDataException(from.name(), to.name(), date.toString());
        }
        // Filtrer par minDepSec (OTP peut retourner des départs un peu avant)
        return journeys.stream()
            .filter(j -> j.departureSec() >= minDepSec)
            .toList();
    }

    /**
     * Fallback activé par le circuit breaker (OTP down, timeout, erreur réseau...).
     */
    @SuppressWarnings("unused")
    private List<Journey> fallbackPlanTrip(Station from, Station to, LocalDate date,
                                            int minDepSec, Throwable cause) {
        log.warn("OTP indisponible (circuit breaker) — fallback SQL. Cause : {}",
            cause.getMessage());
        return sqlFallback.planTrip(from, to, date, minDepSec);
    }

    // -------------------------------------------------------------------------
    // Mapping réponse OTP → domaine
    // -------------------------------------------------------------------------

    /**
     * Parse la réponse JSON OTP plan API et convertit en liste de {@link Journey}.
     * Les objets retournés n'exposent aucun concept GTFS brut.
     */
    List<Journey> parseOtpResponse(String json, LocalDate date, Station from, Station to) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode plan = root.path("plan");
            if (plan.isMissingNode()) {
                log.warn("OTP : réponse sans nœud 'plan' : {}", json.substring(0, Math.min(200, json.length())));
                return List.of();
            }

            String sourceUrl    = store.getMeta("source_url").orElse("otp");
            String freshness    = store.getMeta("ingested_at").orElse("");
            ZoneId tz           = ZoneId.of(timezone);
            ZonedDateTime midnight = date.atStartOfDay(tz);
            long midnightMillis = midnight.toInstant().toEpochMilli();

            List<Journey> journeys = new ArrayList<>();
            for (JsonNode itin : plan.path("itineraries")) {
                List<Leg> legs = parseLegs(itin.path("legs"), midnightMillis, from, to);
                if (!legs.isEmpty()) {
                    journeys.add(new Journey(legs, sourceUrl, freshness));
                }
            }
            return journeys;

        } catch (Exception e) {
            log.warn("OTP : erreur parsing réponse : {}", e.getMessage());
            return List.of();
        }
    }

    private List<Leg> parseLegs(JsonNode legsNode, long midnightMillis,
                                 Station defaultFrom, Station defaultTo) {
        List<Leg> legs = new ArrayList<>();
        for (JsonNode legNode : legsNode) {
            try {
                legs.add(parseLeg(legNode, midnightMillis, defaultFrom, defaultTo));
            } catch (Exception e) {
                log.debug("OTP : leg ignoré : {}", e.getMessage());
            }
        }
        return legs;
    }

    private Leg parseLeg(JsonNode leg, long midnightMillis,
                          Station defaultFrom, Station defaultTo) {
        String otpMode = leg.path("mode").asText("RAIL");
        Mode mode      = parseMode(otpMode);

        // Temps en secondes depuis minuit du jour de service
        long startEpoch = leg.path("startTime").asLong();
        long endEpoch   = leg.path("endTime").asLong();
        int  depSec     = (int) ((startEpoch - midnightMillis) / 1000L);
        int  arrSec     = (int) ((endEpoch   - midnightMillis) / 1000L);

        // Gares from/to
        JsonNode fromNode = leg.path("from");
        JsonNode toNode   = leg.path("to");
        Station  legFrom  = stationFromNode(fromNode, defaultFrom);
        Station  legTo    = stationFromNode(toNode,   defaultTo);

        // Route / trip (IDs nettoyés du feedId)
        String routeId   = stripFeedId(leg.path("routeId").asText(""));
        String tripId    = stripFeedId(leg.path("tripId").asText(""));
        String routeName = firstNonBlank(
            leg.path("routeShortName").asText(""),
            leg.path("routeLongName").asText(""),
            routeId
        );

        return new Leg(legFrom, legTo, depSec, arrSec, mode, tripId, routeId, routeName, null, null);
    }

    private Station stationFromNode(JsonNode node, Station defaultStation) {
        String name   = node.path("name").asText("");
        double lat    = node.path("lat").asDouble(0);
        double lon    = node.path("lon").asDouble(0);
        String stopId = stripFeedId(node.path("stopId").asText(""));

        if (name.isBlank()) return defaultStation;

        // Chercher en DB pour avoir les alias et le nom canonique
        String finalStopId = stopId;
        String finalName   = name;
        double finalLat    = lat;
        double finalLon    = lon;
        return store.getAllStations().stream()
            .filter(row -> {
                Object rowId   = row.get("id");
                Object rowName = row.get("name");
                return (rowId   != null && rowId.toString().equals(finalStopId))
                    || (rowName != null && rowName.toString().equalsIgnoreCase(finalName));
            })
            .map(row -> new Station(
                row.get("id").toString(),
                row.get("name").toString(),
                row.get("lat")  != null ? Double.parseDouble(row.get("lat").toString())  : null,
                row.get("lon")  != null ? Double.parseDouble(row.get("lon").toString())  : null,
                Mode.TRAIN,
                List.of()
            ))
            .findFirst()
            .orElse(new Station(
                finalStopId.isBlank() ? finalName.toUpperCase().replace(" ", "_") : finalStopId,
                finalName, finalLat, finalLon, Mode.TRAIN, List.of()
            ));
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    /** Supprime le préfixe "feedId:" que OTP ajoute aux IDs GTFS. */
    private String stripFeedId(String otpId) {
        if (otpId == null || otpId.isBlank()) return "";
        int colon = otpId.indexOf(':');
        return colon >= 0 ? otpId.substring(colon + 1) : otpId;
    }

    private static Mode parseMode(String otpMode) {
        return switch (otpMode.toUpperCase()) {
            case "RAIL", "TRAM", "SUBWAY", "FERRY"  -> Mode.TRAIN;
            case "BUS", "COACH"                      -> Mode.BUS;
            case "TAXI", "SHARED_TAXI"               -> Mode.GRAND_TAXI;
            default                                  -> Mode.TRAIN;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
