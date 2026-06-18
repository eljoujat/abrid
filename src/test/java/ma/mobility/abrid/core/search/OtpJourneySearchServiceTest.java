package ma.mobility.abrid.core.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Mode;
import ma.mobility.abrid.core.model.Station;
import ma.mobility.abrid.core.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du mapping OTP → domaine (OTP 2.6 GraphQL).
 * Aucun accès réseau : RestClient mocké.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class OtpJourneySearchServiceTest {

    private static final LocalDate DATE = LocalDate.of(2025, 8, 30);
    private static final ZoneId    TZ   = ZoneId.of("Africa/Casablanca");

    @Mock StoreRepository store;
    @Mock RestClient      otpClient;
    @Mock SearchService   sqlFallback;

    OtpJourneySearchService service;

    static final Station TANGER = new Station(
        "TANGER_VILLE", "Tanger-Ville", 35.7643, -5.834, Mode.TRAIN, List.of()
    );
    static final Station CASA = new Station(
        "CASA_VOYAGEURS", "Casa-Voyageurs", 33.5979, -7.6191, Mode.TRAIN, List.of()
    );

    @BeforeEach
    void setUp() throws Exception {
        service = new OtpJourneySearchService(otpClient, sqlFallback, store, new ObjectMapper());
        var fTz = OtpJourneySearchService.class.getDeclaredField("timezone");
        fTz.setAccessible(true); fTz.set(service, "Africa/Casablanca");
        var fId = OtpJourneySearchService.class.getDeclaredField("feedId");
        fId.setAccessible(true); fId.set(service, "1");

        lenient().when(store.getMeta("source_url")).thenReturn(Optional.of("otp://test"));
        lenient().when(store.getMeta("ingested_at")).thenReturn(Optional.of("2025-08-30T00:00:00Z"));
        lenient().when(store.getAllStations()).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // Tests de mapping JSON OTP 2.6 → Journey
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Parse itinéraire RAIL direct — scheduledTime ISO 8601")
    void parseDirectRailItinerary() {
        // OTP 2.6 : scheduledTime = ISO 8601 avec timezone offset
        String json = otpGqlJson(
            "2025-08-30T08:00:00+01:00", "2025-08-30T12:10:00+01:00", "RAIL",
            "Tanger-Ville",    "1:TANGER_VILLE",    35.7643, -5.834,
            "Casa-Voyageurs",  "1:CASA_VOYAGEURS",  33.5979, -7.6191,
            "Al Boraq", "Tanger - Casa", "1:AB_TNG_CASA_0900"
        );

        List<Journey> journeys = service.parseOtpResponse(json, DATE);

        assertThat(journeys).hasSize(1);
        var leg = journeys.getFirst().legs().getFirst();

        assertThat(leg.mode()).isEqualTo(Mode.TRAIN);
        assertThat(leg.departureSec()).isEqualTo(8 * 3600);   // 08:00 = 28800s
        assertThat(leg.arrivalSec()).isEqualTo(12 * 3600 + 10 * 60);  // 12:10
        assertThat(leg.durationSeconds()).isEqualTo(4 * 3600 + 10 * 60);
        assertThat(leg.routeName()).isEqualTo("Al Boraq");
        assertThat(leg.tripId()).isEqualTo("AB_TNG_CASA_0900"); // feedId strippé
    }

    @Test
    @DisplayName("Horaire passant minuit : arrSec > 86400")
    void parseNightCrossing() {
        String json = otpGqlJson(
            "2025-08-30T23:00:00+01:00", "2025-08-31T01:20:00+01:00", "RAIL",
            "Tanger-Ville", "1:TANGER_VILLE", 35.7643, -5.834,
            "Casa-Voyageurs", "1:CASA_VOYAGEURS", 33.5979, -7.6191,
            "ONCF", "Night Train", "1:NT_001"
        );

        List<Journey> journeys = service.parseOtpResponse(json, DATE);

        var leg = journeys.getFirst().legs().getFirst();
        assertThat(leg.departureSec()).isEqualTo(23 * 3600);               // 82800
        assertThat(leg.arrivalSec()).isEqualTo(25 * 3600 + 20 * 60);      // 91200 > 86400
        assertThat(leg.arrivalSec()).isGreaterThan(86400);
    }

    @Test
    @DisplayName("Mode BUS → Leg.mode = BUS")
    void parseBusMode() {
        String json = otpGqlJson(
            "2025-08-30T09:00:00+01:00", "2025-08-30T11:00:00+01:00", "BUS",
            "Gare Routière", "1:GR1", 35.0, -5.0,
            "Bus Station", "1:BS1", 33.0, -7.0,
            "CTM", "Tanger-Casa", "1:BUS_001"
        );

        List<Journey> journeys = service.parseOtpResponse(json, DATE);
        assertThat(journeys.getFirst().legs().getFirst().mode()).isEqualTo(Mode.BUS);
    }

    @Test
    @DisplayName("Réponse OTP sans itinéraires → liste vide")
    void parseEmptyItineraries() {
        String json = """
            {"data": {"plan": {"itineraries": []}}}
            """;
        assertThat(service.parseOtpResponse(json, DATE)).isEmpty();
    }

    @Test
    @DisplayName("Réponse JSON malformée → liste vide, pas d'exception")
    void parseMalformedJson() {
        assertThat(service.parseOtpResponse("{invalid json", DATE)).isEmpty();
    }

    @Test
    @DisplayName("Nœud data.plan absent → liste vide")
    void parseMissingPlanNode() {
        assertThat(service.parseOtpResponse("{\"errors\":[{\"message\":\"no route\"}]}", DATE)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Tests isoToSecsSinceMidnight
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isoToSecsSinceMidnight : 08:00 Casablanca → 28800")
    void isoToSecsMidnightMorning() {
        int secs = OtpJourneySearchService.isoToSecsSinceMidnight(
            "2025-08-30T08:00:00+01:00", DATE, TZ
        );
        assertThat(secs).isEqualTo(8 * 3600); // 28800
    }

    @Test
    @DisplayName("isoToSecsSinceMidnight : heure passant minuit → > 86400")
    void isoToSecsNightCrossing() {
        int secs = OtpJourneySearchService.isoToSecsSinceMidnight(
            "2025-08-31T01:20:00+01:00", DATE, TZ
        );
        assertThat(secs).isEqualTo(25 * 3600 + 20 * 60); // 91200
    }

    // -------------------------------------------------------------------------
    // Tests utilitaires
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("stripFeedId : '1:TANGER_VILLE' → 'TANGER_VILLE'")
    void stripFeedId() {
        assertThat(service.stripFeedId("1:TANGER_VILLE")).isEqualTo("TANGER_VILLE");
        assertThat(service.stripFeedId("TANGER_VILLE")).isEqualTo("TANGER_VILLE");
        assertThat(service.stripFeedId("")).isEqualTo("");
    }

    // -------------------------------------------------------------------------
    // Helper : JSON OTP 2.6 GraphQL plan response
    // -------------------------------------------------------------------------

    static String otpGqlJson(
            String depIso, String arrIso, String mode,
            String fromName, String fromStopId, double fromLat, double fromLon,
            String toName,   String toStopId,   double toLat,   double toLon,
            String routeShort, String routeLong, String tripId) {

        long durationSec = java.time.Duration.between(
            java.time.OffsetDateTime.parse(depIso),
            java.time.OffsetDateTime.parse(arrIso)
        ).getSeconds();

        return """
            {
              "data": {
                "plan": {
                  "itineraries": [{
                    "duration": %d,
                    "legs": [{
                      "mode": "%s",
                      "start": {"scheduledTime": "%s"},
                      "end":   {"scheduledTime": "%s"},
                      "from":  {"name":"%s","stop":{"gtfsId":"%s"},"lat":%f,"lon":%f},
                      "to":    {"name":"%s","stop":{"gtfsId":"%s"},"lat":%f,"lon":%f},
                      "route": {"shortName":"%s","longName":"%s","gtfsId":"%s"},
                      "trip":  {"gtfsId":"%s"}
                    }]
                  }]
                }
              }
            }
            """.formatted(durationSec, mode, depIso, arrIso,
                fromName, fromStopId, fromLat, fromLon,
                toName,   toStopId,   toLat,   toLon,
                routeShort, routeLong, tripId.replace(":", "R:"), tripId);
    }
}
