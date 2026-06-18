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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moteur de recherche de trajets via OpenTripPlanner 2.x.
 *
 * <p>Actif uniquement si {@code app.otp.enabled=true}.
 * En cas d'échec, le circuit breaker bascule sur {@link SearchService} SQL.
 *
 * <h2>API utilisée</h2>
 * <p>OTP 2.5+ a supprimé l'ancienne API REST. On utilise l'API GraphQL GTFS :
 * {@code POST /otp/gtfs/v1}
 *
 * <h2>Résolution de gare</h2>
 * <p>Sans OSM, OTP ne peut pas router depuis des coordonnées GPS (pas de « nearest stop »).
 * On utilise donc le format {@code "Nom::feedId:stopId"} pour cibler directement
 * le stop GTFS dans {@code fromPlace} / {@code toPlace}.
 *
 * <h2>Conversion des temps</h2>
 * <p>OTP 2.6 retourne {@code scheduledTime} en ISO-8601 ({@code 2025-08-30T08:00:00+01:00}).
 * On convertit en secondes-depuis-minuit du jour de service.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.otp.enabled", havingValue = "true")
public class OtpJourneySearchService implements JourneySearchPort {

    private static final Logger log = LoggerFactory.getLogger(OtpJourneySearchService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Endpoint GraphQL OTP 2.5+ GTFS */
    private static final String GQL_PATH = "/otp/gtfs/v1";

    /** Fragment GraphQL réutilisable pour un leg de trajet */
    private static final String LEG_FRAGMENT = """
            legs {
              mode
              start { scheduledTime }
              end   { scheduledTime }
              from  { name stop { gtfsId } lat lon }
              to    { name stop { gtfsId } lat lon }
              route { shortName longName gtfsId }
              trip  { gtfsId }
            }
        """;

    private final RestClient      otpClient;
    private final SearchService   sqlFallback;
    private final StoreRepository store;
    private final ObjectMapper    mapper;

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
    // JourneySearchPort — point d'entrée avec circuit breaker
    // -------------------------------------------------------------------------

    @Override
    @CircuitBreaker(name = "otp", fallbackMethod = "fallbackPlanTrip")
    public List<Journey> planTrip(Station from, Station to, LocalDate date, int minDepSec) {

        ZoneId tz       = ZoneId.of(timezone);
        ZonedDateTime earliest = date.atStartOfDay(tz).plusSeconds(minDepSec);
        String timeStr  = earliest.format(TIME_FMT);

        // Format fromPlace : "Nom::feedId:stopId"  (routing direct par stop GTFS, sans OSM)
        String fromPlace = buildPlace(from);
        String toPlace   = buildPlace(to);

        String query = buildPlanQuery(fromPlace, toPlace, date.format(DATE_FMT), timeStr, 10);
        log.debug("OTP GraphQL : from={} to={} date={} time={}", fromPlace, toPlace,
            date.format(DATE_FMT), timeStr);

        String json = otpClient.post()
            .uri(GQL_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("query", query))
            .retrieve()
            .body(String.class);

        List<Journey> journeys = parseOtpResponse(json, date);

        if (journeys.isEmpty()) {
            throw new NoDataException(from.name(), to.name(), date.toString());
        }
        return journeys.stream()
            .filter(j -> j.departureSec() >= minDepSec)
            .toList();
    }

    @SuppressWarnings("unused")
    private List<Journey> fallbackPlanTrip(Station from, Station to, LocalDate date,
                                            int minDepSec, Throwable cause) {
        log.warn("OTP indisponible — fallback SQL. Cause : {}", cause.getMessage());
        return sqlFallback.planTrip(from, to, date, minDepSec);
    }

    // -------------------------------------------------------------------------
    // Construction de la requête GraphQL
    // -------------------------------------------------------------------------

    private static String buildPlanQuery(String fromPlace, String toPlace,
                                          String date, String time, int numItineraries) {
        // Échapper les guillemets pour l'insertion dans la query GraphQL
        return String.format("""
            {
              plan(
                fromPlace: "%s"
                toPlace:   "%s"
                date:      "%s"
                time:      "%s"
                numItineraries: %d
              ) {
                %s
              }
            }
            """, fromPlace, toPlace, date, time, numItineraries, LEG_FRAGMENT);
    }

    /**
     * Construit le fromPlace/toPlace OTP au format {@code "Nom::feedId:stopId"}.
     * Sans OSM, OTP ne peut pas résoudre des coordonnées → on cible le stop directement.
     */
    private String buildPlace(Station station) {
        return station.name() + "::" + feedId + ":" + station.id();
    }

    // -------------------------------------------------------------------------
    // Parsing de la réponse
    // -------------------------------------------------------------------------

    List<Journey> parseOtpResponse(String json, LocalDate date) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode plan = root.path("data").path("plan");
            if (plan.isMissingNode()) {
                log.warn("OTP : pas de nœud data.plan : {}", json.substring(0, Math.min(300, json.length())));
                return List.of();
            }

            String sourceUrl = store.getMeta("source_url").orElse("otp");
            String freshness = store.getMeta("ingested_at").orElse("");
            ZoneId tz        = ZoneId.of(timezone);

            List<Journey> journeys = new ArrayList<>();
            for (JsonNode itin : plan.path("itineraries")) {
                List<Leg> legs = parseLegs(itin.path("legs"), date, tz);
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

    private List<Leg> parseLegs(JsonNode legsNode, LocalDate date, ZoneId tz) {
        List<Leg> legs = new ArrayList<>();
        for (JsonNode leg : legsNode) {
            try {
                legs.add(parseLeg(leg, date, tz));
            } catch (Exception e) {
                log.debug("OTP : leg ignoré : {}", e.getMessage());
            }
        }
        return legs;
    }

    private Leg parseLeg(JsonNode leg, LocalDate serviceDate, ZoneId tz) {
        // OTP 2.6 retourne scheduledTime en ISO-8601 : "2025-08-30T08:00:00+01:00"
        String depIso = leg.path("start").path("scheduledTime").asText();
        String arrIso = leg.path("end").path("scheduledTime").asText();

        int depSec = isoToSecsSinceMidnight(depIso, serviceDate, tz);
        int arrSec = isoToSecsSinceMidnight(arrIso, serviceDate, tz);

        JsonNode fromNode = leg.path("from");
        JsonNode toNode   = leg.path("to");
        Station  legFrom  = stationFromNode(fromNode);
        Station  legTo    = stationFromNode(toNode);

        String routeId   = stripFeedId(leg.path("route").path("gtfsId").asText(""));
        String tripId    = stripFeedId(leg.path("trip").path("gtfsId").asText(""));
        String routeName = firstNonBlank(
            leg.path("route").path("shortName").asText(""),
            leg.path("route").path("longName").asText(""),
            routeId
        );
        String otpMode = leg.path("mode").asText("RAIL");

        return new Leg(legFrom, legTo, depSec, arrSec, parseMode(otpMode),
            tripId, routeId, routeName, null, null);
    }

    /**
     * Convertit une date/heure ISO-8601 en secondes depuis minuit du jour de service.
     * Gère les horaires passant minuit (valeur > 86400).
     */
    static int isoToSecsSinceMidnight(String iso, LocalDate serviceDate, ZoneId tz) {
        if (iso == null || iso.isBlank()) return 0;
        OffsetDateTime odt = OffsetDateTime.parse(iso);
        // Minuit du jour de service dans la timezone locale
        ZonedDateTime midnight = serviceDate.atStartOfDay(tz);
        long diffSec = odt.toEpochSecond() - midnight.toEpochSecond();
        return (int) diffSec;
    }

    private Station stationFromNode(JsonNode node) {
        String name   = node.path("name").asText("");
        double lat    = node.path("lat").asDouble(0);
        double lon    = node.path("lon").asDouble(0);
        String stopId = stripFeedId(node.path("stop").path("gtfsId").asText(""));

        if (!stopId.isBlank()) {
            // Chercher en DB pour avoir les alias et le nom canonique
            var found = store.getAllStations().stream()
                .filter(row -> stopId.equals(row.getOrDefault("id", "").toString()))
                .findFirst();
            if (found.isPresent()) {
                var row = found.get();
                return new Station(
                    row.get("id").toString(),
                    row.get("name").toString(),
                    row.get("lat") != null ? Double.parseDouble(row.get("lat").toString()) : null,
                    row.get("lon") != null ? Double.parseDouble(row.get("lon").toString()) : null,
                    Mode.TRAIN, List.of()
                );
            }
        }
        String id = stopId.isBlank() ? name.toUpperCase().replace(" ", "_") : stopId;
        return new Station(id, name, lat, lon, Mode.TRAIN, List.of());
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    String stripFeedId(String otpId) {
        if (otpId == null || otpId.isBlank()) return "";
        int colon = otpId.indexOf(':');
        return colon >= 0 ? otpId.substring(colon + 1) : otpId;
    }

    private static Mode parseMode(String otpMode) {
        return switch (otpMode.toUpperCase()) {
            case "RAIL", "TRAM", "SUBWAY", "FERRY" -> Mode.TRAIN;
            case "BUS", "COACH"                     -> Mode.BUS;
            case "TAXI", "SHARED_TAXI"              -> Mode.GRAND_TAXI;
            default                                 -> Mode.TRAIN;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
