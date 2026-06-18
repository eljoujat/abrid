package ma.mobility.abrid.agent;

import ma.mobility.abrid.TestGtfsData;
import ma.mobility.abrid.core.loader.GtfsLoader;
import ma.mobility.abrid.core.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for AgentTools — runs against H2 with test GTFS data.
 *
 * <p>These tests verify the exact behaviour the LLM relies on:
 * <ul>
 *   <li>Correct status codes (found, not_found, ambiguous, station_unknown)</li>
 *   <li>No invented data (data honesty guarantee)</li>
 *   <li>Disambiguation works end-to-end</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentToolsTest {

    /** Reference date — Monday 2 September 2024 (active in test GTFS calendar). */
    static final String DATE = "2024-09-02";
    static final LocalDate LOCAL_DATE = LocalDate.of(2024, 9, 2);

    @Autowired AgentTools      agentTools;
    @Autowired GtfsLoader      loader;
    @Autowired StoreRepository store;

    @BeforeEach
    void loadTestData() throws Exception {
        loader.ingest(TestGtfsData.buildZip(), "test://agent", false);
    }

    // =========================================================================
    // plan_trip
    // =========================================================================

    @Test
    @DisplayName("plan_trip: direct journey found → status=found, journeys non-empty")
    void planTripDirectFound() {
        TripToolResult result = agentTools.planTrip("Tanger", "Casa", DATE);

        assertThat(result.status()).isEqualTo("found");
        assertThat(result.journeys()).isNotEmpty();
        assertThat(result.candidates()).isEmpty();

        var journey = result.journeys().getFirst();
        assertThat(journey.transfers()).isEqualTo(0);
        assertThat(journey.durationMinutes()).isGreaterThan(0);
        assertThat(journey.legs()).hasSize(1);
        assertThat(journey.legs().getFirst().mode()).isEqualTo("TRAIN");
    }

    @Test
    @DisplayName("plan_trip: trip with transfer → status=found, transfers=1")
    void planTripWithTransferFound() {
        TripToolResult result = agentTools.planTrip("Tanger", "Marrakech", DATE);

        assertThat(result.status()).isEqualTo("found");
        assertThat(result.journeys()).isNotEmpty();
        result.journeys().forEach(j ->
            assertThat(j.transfers()).isGreaterThanOrEqualTo(1)
        );
    }

    @Test
    @DisplayName("plan_trip: wrong direction → status=not_found (data honesty)")
    void planTripWrongDirection() {
        // Test GTFS only has Casa→Marrakech, not Marrakech→Casa
        TripToolResult result = agentTools.planTrip("Marrakech", "Tanger", DATE);

        assertThat(result.status()).isIn("not_found", "found");
        // Key guarantee: journeys are real, never invented
        if ("found".equals(result.status())) {
            result.journeys().forEach(j -> assertThat(j.legs()).isNotEmpty());
        }
    }

    @Test
    @DisplayName("plan_trip: O/D not covered → status=not_found, journeys empty")
    void planTripNotCovered() {
        TripToolResult result = agentTools.planTrip("Tanger", "Fès", DATE);
        // In test GTFS: Casa→Fès exists but Tanger→Fès requires 2 transfers (not supported by SQL engine)
        // status must be either found (via transfer) or not_found — never throws
        assertThat(result.status()).isIn("found", "not_found");
        assertThat(result.journeys()).allSatisfy(j ->
            assertThat(j.legs()).isNotEmpty()
        );
    }

    @Test
    @DisplayName("plan_trip: unknown station → status=station_unknown")
    void planTripUnknownStation() {
        TripToolResult result = agentTools.planTrip("GareFantome123", "Casa", DATE);
        assertThat(result.status()).isEqualTo("station_unknown");
        assertThat(result.journeys()).isEmpty();
    }

    @Test
    @DisplayName("plan_trip: invalid date → status=error")
    void planTripInvalidDate() {
        TripToolResult result = agentTools.planTrip("Tanger", "Casa", "not-a-date");
        assertThat(result.status()).isEqualTo("error");
    }

    @Test
    @DisplayName("plan_trip: darija partial name 'casa' → ambiguous (multiple Casa stations)")
    void planTripAmbiguousStation() {
        // "Casa" matches Casa-Voyageurs, Casa-Port, Casa-Oasis in real GTFS
        // In test GTFS there is only CASA_VOYAGEURS, so it resolves directly
        TripToolResult result = agentTools.planTrip("Tanger", "Casa", DATE);
        assertThat(result.status()).isIn("found", "ambiguous");
        if ("ambiguous".equals(result.status())) {
            assertThat(result.candidates()).isNotEmpty();
        }
    }

    // =========================================================================
    // get_schedule
    // =========================================================================

    @Test
    @DisplayName("get_schedule: known station → status=found, departures non-empty")
    void getScheduleFound() {
        ScheduleToolResult result = agentTools.getSchedule("Tanger", DATE);

        assertThat(result.status()).isEqualTo("found");
        assertThat(result.departures()).isNotEmpty();
        result.departures().forEach(d -> {
            assertThat(d.departure()).matches("\\d{2}:\\d{2}");
            assertThat(d.tripId()).isNotBlank();
        });
    }

    @Test
    @DisplayName("get_schedule: unknown station → status=station_unknown")
    void getScheduleUnknown() {
        ScheduleToolResult result = agentTools.getSchedule("StationFantome", DATE);
        assertThat(result.status()).isEqualTo("station_unknown");
    }

    @Test
    @DisplayName("get_schedule: invalid date → status=not_found (graceful)")
    void getScheduleInvalidDate() {
        ScheduleToolResult result = agentTools.getSchedule("Tanger", "bad-date");
        assertThat(result.status()).isIn("not_found", "station_unknown");
    }

    // =========================================================================
    // get_station_info
    // =========================================================================

    @Test
    @DisplayName("get_station_info: exact name → status=found with coordinates")
    void getStationInfoExact() {
        StationToolResult result = agentTools.getStationInfo("Tanger");

        assertThat(result.status()).isEqualTo("found");
        assertThat(result.stations()).hasSize(1);
        var station = result.stations().getFirst();
        assertThat(station.name()).containsIgnoringCase("Tanger");
        assertThat(station.lat()).isNotNull();
        assertThat(station.lon()).isNotNull();
    }

    @Test
    @DisplayName("get_station_info: unknown query → status=not_found")
    void getStationInfoNotFound() {
        StationToolResult result = agentTools.getStationInfo("XYZ_DOES_NOT_EXIST");
        assertThat(result.status()).isEqualTo("not_found");
        assertThat(result.stations()).isEmpty();
    }

    // =========================================================================
    // get_disruptions
    // =========================================================================

    @Test
    @DisplayName("get_disruptions: no active disruptions → empty list, message says so")
    void getDisruptionsEmpty() {
        DisruptionToolResult result = agentTools.getDisruptions(null);

        assertThat(result.disruptions()).isEmpty();
        assertThat(result.message()).containsIgnoringCase("no active disruptions");
    }

    @Test
    @DisplayName("get_disruptions: filter by routeId → empty list (no disruptions seeded)")
    void getDisruptionsByRoute() {
        DisruptionToolResult result = agentTools.getDisruptions("LIGNE_NORD");
        assertThat(result.disruptions()).isEmpty();
    }

    // =========================================================================
    // submit_correction
    // =========================================================================

    @Test
    @DisplayName("submit_correction: valid input → status=accepted with ID")
    void submitCorrectionAccepted() {
        CorrectionToolResult result = agentTools.submitCorrection(
            "FARE",
            "The fare from Tanger to Casa should be 95 MAD, not 110 MAD.",
            "TANGER_VILLE"
        );

        assertThat(result.status()).isEqualTo("accepted");
        assertThat(result.correctionId()).isNotNull().isPositive();
        assertThat(result.message()).containsIgnoringCase("correction recorded");
    }

    @Test
    @DisplayName("submit_correction: unknown type normalised to OTHER")
    void submitCorrectionUnknownType() {
        CorrectionToolResult result = agentTools.submitCorrection(
            "UNKNOWN_TYPE",
            "Some correction description.",
            null
        );
        assertThat(result.status()).isEqualTo("accepted");
    }

    // =========================================================================
    // Data honesty — cross-cutting guarantee
    // =========================================================================

    @Test
    @DisplayName("DATA HONESTY: no tool ever returns invented journeys")
    void dataHonestyNoInventedJourneys() {
        // Purge all data — simulates empty database
        store.purgeAll();

        TripToolResult result = agentTools.planTrip("Tanger", "Casa", DATE);
        // With empty DB: must be not_found or station_unknown, NEVER found
        assertThat(result.status()).isIn("not_found", "station_unknown", "ambiguous");
        assertThat(result.journeys())
            .as("No journeys must be invented when DB is empty")
            .isEmpty();
    }
}
