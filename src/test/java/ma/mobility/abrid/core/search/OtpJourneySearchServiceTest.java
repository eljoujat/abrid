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
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du mapping OTP → domaine.
 * Aucun accès réseau : RestClient mocké.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class OtpJourneySearchServiceTest {

    /** Date de référence : lundi 2 septembre 2024. */
    private static final LocalDate DATE = LocalDate.of(2024, 9, 2);
    private static final ZoneId TZ      = ZoneId.of("Africa/Casablanca");

    // Minuit le 2024-09-02 à Casablanca = 2024-09-01T23:00:00Z (UTC-1 en été)
    // Calculé : DATE.atStartOfDay(TZ).toInstant().toEpochMilli()
    private static final long MIDNIGHT_MS;

    static {
        MIDNIGHT_MS = DATE.atStartOfDay(TZ).toInstant().toEpochMilli();
    }

    @Mock StoreRepository store;
    @Mock RestClient      otpClient;
    @Mock SearchService   sqlFallback;

    OtpJourneySearchService service;

    static final Station TANGER = new Station(
        "TANGER", "Tanger-Ville", 35.769, -5.800, Mode.TRAIN, List.of()
    );
    static final Station CASA = new Station(
        "CASA_VOYAGEURS", "Casa-Voyageurs", 33.590, -7.620, Mode.TRAIN, List.of()
    );

    @BeforeEach
    void setUp() {
        service = new OtpJourneySearchService(otpClient, sqlFallback, store, new ObjectMapper());
        // Reflexion sur le champ timezone (normalement injecté par @Value)
        try {
            var f = OtpJourneySearchService.class.getDeclaredField("timezone");
            f.setAccessible(true);
            f.set(service, "Africa/Casablanca");
            var f2 = OtpJourneySearchService.class.getDeclaredField("feedId");
            f2.setAccessible(true);
            f2.set(service, "1");
        } catch (Exception e) { throw new RuntimeException(e); }

        when(store.getMeta("source_url")).thenReturn(Optional.of("otp://test"));
        when(store.getMeta("ingested_at")).thenReturn(Optional.of("2024-09-02T00:00:00Z"));
        when(store.getAllStations()).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // Tests de mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Parse une réponse OTP avec un itinéraire direct RAIL")
    void parseDirectRailItinerary() {
        long depMs = MIDNIGHT_MS + 8 * 3600 * 1000L;   // 08:00
        long arrMs = MIDNIGHT_MS + 12 * 3600 * 1000L;  // 12:00

        String json = otpPlanJson(depMs, arrMs, "RAIL",
            "Tanger-Ville", "1:TANGER", 35.769, -5.800,
            "Casa-Voyageurs", "1:CASA_VOYAGEURS", 33.590, -7.620,
            "AL BORAQ", "Tanger - Casa", "1:T001");

        List<Journey> journeys = service.parseOtpResponse(json, DATE, TANGER, CASA);

        assertThat(journeys).hasSize(1);
        var j = journeys.getFirst();
        assertThat(j.legs()).hasSize(1);

        var leg = j.legs().getFirst();
        assertThat(leg.mode()).isEqualTo(Mode.TRAIN);
        assertThat(leg.departureSec()).isEqualTo(8 * 3600);    // 08:00 = 28800s
        assertThat(leg.arrivalSec()).isEqualTo(12 * 3600);     // 12:00 = 43200s
        assertThat(leg.durationSeconds()).isEqualTo(4 * 3600); // 4h
        assertThat(leg.routeName()).isEqualTo("AL BORAQ");
        assertThat(leg.tripId()).isEqualTo("T001");            // feedId strippé
    }

    @Test
    @DisplayName("Horaire passant minuit : depSec > 86400")
    void parseNightCrossing() {
        long depMs = MIDNIGHT_MS + 23 * 3600 * 1000L;               // 23:00
        long arrMs = MIDNIGHT_MS + 25 * 3600 * 1000L + 20 * 60000L; // 25:20 = 01:20+1j

        String json = otpPlanJson(depMs, arrMs, "RAIL",
            "Tanger-Ville", "1:TANGER", 35.769, -5.800,
            "Casa-Voyageurs", "1:CASA_VOYAGEURS", 33.590, -7.620,
            "ONCF", "Tanger Night", "1:TN01");

        List<Journey> journeys = service.parseOtpResponse(json, DATE, TANGER, CASA);

        assertThat(journeys).hasSize(1);
        var leg = journeys.getFirst().legs().getFirst();
        assertThat(leg.departureSec()).isEqualTo(23 * 3600);           // 82800
        assertThat(leg.arrivalSec()).isEqualTo(25 * 3600 + 20 * 60); // 91200
        assertThat(leg.arrivalSec()).isGreaterThan(86400);
    }

    @Test
    @DisplayName("Réponse OTP vide → liste vide (jamais NoDataException ici)")
    void parseEmptyPlan() {
        String json = """
            {"plan": {"itineraries": []}}
            """;
        List<Journey> result = service.parseOtpResponse(json, DATE, TANGER, CASA);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Réponse OTP malformée → liste vide (pas d'exception)")
    void parseMalformedJson() {
        List<Journey> result = service.parseOtpResponse("{invalid}", DATE, TANGER, CASA);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Réponse OTP sans nœud 'plan' → liste vide")
    void parseMissingPlanNode() {
        List<Journey> result = service.parseOtpResponse("{\"error\": \"no route\"}", DATE, TANGER, CASA);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Mode BUS → Leg.mode = BUS")
    void parseBusMode() {
        long depMs = MIDNIGHT_MS + 9 * 3600 * 1000L;
        long arrMs = MIDNIGHT_MS + 11 * 3600 * 1000L;
        String json = otpPlanJson(depMs, arrMs, "BUS",
            "Gare Routière", "1:GR1", 35.0, -5.0,
            "Bus Station", "1:BS1", 33.0, -7.0,
            "CTM", "Tanger-Casa", "1:BT01");

        List<Journey> journeys = service.parseOtpResponse(json, DATE, TANGER, CASA);
        assertThat(journeys.getFirst().legs().getFirst().mode()).isEqualTo(Mode.BUS);
    }

    @Test
    @DisplayName("Strip feedId : '1:TANGER' → 'TANGER', 'feed:ID:TANGER' → 'ID:TANGER'")
    void stripFeedIdTest() throws Exception {
        var m = OtpJourneySearchService.class.getDeclaredMethod("stripFeedId", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, "1:TANGER")).isEqualTo("TANGER");
        assertThat(m.invoke(service, "TANGER")).isEqualTo("TANGER");
        assertThat(m.invoke(service, "")).isEqualTo("");
    }

    @Test
    @DisplayName("Coordonnées manquantes → délègue au SQL fallback")
    void missingCoordinatesFallsBackToSql() {
        Station noCoords = new Station("X", "Inconnue", null, null, Mode.TRAIN, List.of());
        when(sqlFallback.planTrip(any(), any(), any(), anyInt())).thenReturn(List.of());

        // Ne doit pas lever d'exception, appelle sqlFallback
        assertThatCode(() -> service.planTrip(noCoords, CASA, DATE, 0))
            .doesNotThrowAnyException();
        verify(sqlFallback).planTrip(eq(noCoords), eq(CASA), eq(DATE), eq(0));
    }

    // -------------------------------------------------------------------------
    // Helper : construire un JSON OTP plan minimal
    // -------------------------------------------------------------------------

    static String otpPlanJson(long depMs, long arrMs, String mode,
                               String fromName, String fromStopId, double fromLat, double fromLon,
                               String toName,   String toStopId,   double toLat,   double toLon,
                               String routeShort, String routeLong, String tripId) {
        return """
            {
              "plan": {
                "itineraries": [{
                  "duration": %d,
                  "legs": [{
                    "mode": "%s",
                    "startTime": %d,
                    "endTime": %d,
                    "from": {"name":"%s","stopId":"%s","lat":%f,"lon":%f,"departure":%d},
                    "to":   {"name":"%s","stopId":"%s","lat":%f,"lon":%f,"arrival":%d},
                    "routeShortName": "%s",
                    "routeLongName":  "%s",
                    "routeId": "%s",
                    "tripId":  "%s"
                  }]
                }]
              }
            }
            """.formatted(
            (arrMs - depMs) / 1000,
            mode, depMs, arrMs,
            fromName, fromStopId, fromLat, fromLon, depMs,
            toName,   toStopId,   toLat,   toLon,   arrMs,
            routeShort, routeLong,
            tripId.replace("T", "R"), tripId
        );
    }
}
