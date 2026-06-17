package ma.mobility.abrid.core;

import ma.mobility.abrid.TestGtfsData;
import ma.mobility.abrid.core.loader.GtfsLoader;
import ma.mobility.abrid.core.search.NoDataException;
import ma.mobility.abrid.core.search.SearchService;
import ma.mobility.abrid.core.search.StationNotFoundException;
import ma.mobility.abrid.core.store.StoreRepository;
import ma.mobility.abrid.core.time.TimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests d'intégration du moteur de recherche — base H2 en mémoire (profil test).
 * Date de référence figée : lundi 2 septembre 2024.
 */
@SpringBootTest
@ActiveProfiles("test")
class SearchServiceTest {

    static final LocalDate REF_DATE = LocalDate.of(2024, 9, 2);

    // GTFS minimal — délégué au helper partagé
    static final String STOPS      = TestGtfsData.STOPS;
    static final String ROUTES     = TestGtfsData.ROUTES;
    static final String CALENDAR   = TestGtfsData.CALENDAR;
    static final String TRIPS      = TestGtfsData.TRIPS;
    static final String STOP_TIMES = TestGtfsData.STOP_TIMES;

    @Autowired SearchService  search;
    @Autowired StoreRepository store;
    @Autowired GtfsLoader     loader;

    @BeforeEach
    void loadTestData() throws Exception {
        loader.ingest(TestGtfsData.buildZip(), "test://minimal", false);
    }

    // -------------------------------------------------------------------------
    // Tests modèles de domaine
    // -------------------------------------------------------------------------

    @Test
    void legDuration() {
        assertThat(TimeUtils.gtfsTimeToSeconds("12:00:00") - TimeUtils.gtfsTimeToSeconds("08:00:00"))
            .isEqualTo(14400);
    }

    // -------------------------------------------------------------------------
    // Tests ingestion
    // -------------------------------------------------------------------------

    @Test
    void ingestCounts() {
        assertThat(store.countStations()).isEqualTo(4);
        assertThat(store.getAllRouteIds()).hasSize(3);
        assertThat(store.countTrips()).isEqualTo(3);
        assertThat(store.countStopTimes()).isEqualTo(6);
    }

    @Test
    void ingestCoverageFullWhenAllRoutesHaveTrips() {
        var all    = store.getAllRouteIds();
        var withT  = store.getRouteIdsWithTrips();
        assertThat(withT).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void ingestIdempotent() {
        // Deux ingestions successives → même état, pas de doublons
        assertThatCode(() -> loader.ingest(TestGtfsData.buildZip(), "test://minimal", false))
            .doesNotThrowAnyException();
        assertThat(store.countStations()).isEqualTo(4);
        assertThat(store.countTrips()).isEqualTo(3);
    }

    @Test
    void metaStoredAfterIngest() {
        assertThat(store.getMeta("source_url")).hasValue("test://minimal");
        assertThat(store.getMeta("ingested_at")).isPresent();
    }

    // -------------------------------------------------------------------------
    // Tests résolution gare
    // -------------------------------------------------------------------------

    @Test
    void resolveByExactName() {
        assertThat(search.resolveStation("Tanger").id()).isEqualTo("TANGER");
    }

    @Test
    void resolveCaseInsensitive() {
        assertThat(search.resolveStation("tanger").id()).isEqualTo("TANGER");
    }

    @Test
    void resolveWithoutAccent() {
        // "Fes" doit résoudre "Fès"
        assertThat(search.resolveStation("Fes").id()).isEqualTo("FES");
    }

    @Test
    void resolvePartial() {
        assertThat(search.resolveStation("Casa").id()).isEqualTo("CASA_VOYAGEURS");
    }

    @Test
    void resolveById() {
        assertThat(search.resolveStation("MARRAKECH").id()).isEqualTo("MARRAKECH");
    }

    @Test
    void resolveUnknownThrows() {
        assertThatThrownBy(() -> search.resolveStation("GareInexistante"))
            .isInstanceOf(StationNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Tests trajets directs
    // -------------------------------------------------------------------------

    @Test
    void directTangerCasa() {
        var from = search.resolveStation("Tanger");
        var to   = search.resolveStation("Casa");
        var journeys = search.findDirectTrips(from, to, REF_DATE, 0);
        assertThat(journeys).hasSize(1);
        assertThat(journeys.getFirst().legs().getFirst().departureSec())
            .isEqualTo(TimeUtils.gtfsTimeToSeconds("08:00:00"));
    }

    @Test
    void sensRespecteCasaVersTanger() {
        // Casa→Tanger ne doit PAS renvoyer le trajet Tanger→Casa
        var from = search.resolveStation("Casa");
        var to   = search.resolveStation("Tanger");
        var journeys = search.findDirectTrips(from, to, REF_DATE, 0);
        assertThat(journeys).isEmpty();
    }

    @Test
    void noDirectTangerMarrakech() {
        var from = search.resolveStation("Tanger");
        var to   = search.resolveStation("Marrakech");
        var journeys = search.findDirectTrips(from, to, REF_DATE, 0);
        assertThat(journeys).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Tests correspondances
    // -------------------------------------------------------------------------

    @Test
    void tangerMarrakechViaCasa() {
        var journeys = search.planTrip("Tanger", "Marrakech", REF_DATE);
        assertThat(journeys).isNotEmpty();
        var best = journeys.getFirst();
        assertThat(best.nbTransfers()).isEqualTo(1);
        assertThat(best.legs().get(0).fromStation().id()).isEqualTo("TANGER");
        assertThat(best.legs().get(0).toStation().id()).isEqualTo("CASA_VOYAGEURS");
        assertThat(best.legs().get(1).fromStation().id()).isEqualTo("CASA_VOYAGEURS");
        assertThat(best.legs().get(1).toStation().id()).isEqualTo("MARRAKECH");
    }

    @Test
    void transferMarginRespected() {
        var journeys = search.planTrip("Tanger", "Marrakech", REF_DATE);
        for (var j : journeys) {
            for (int i = 0; i < j.legs().size() - 1; i++) {
                int gap = j.legs().get(i + 1).departureSec() - j.legs().get(i).arrivalSec();
                assertThat(gap).as("Marge de correspondance insuffisante").isGreaterThanOrEqualTo(600);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Garde-fous données — NE JAMAIS inventer de résultat
    // -------------------------------------------------------------------------

    @Test
    void odNonCouvreLeveNoDataException() {
        // Fès→Marrakech : pas de trajet direct ni de correspondance dans le GTFS minimal
        assertThatThrownBy(() -> search.planTrip("Fes", "Marrakech", REF_DATE))
            .isInstanceOf(NoDataException.class);
    }

    @Test
    void gareInconnueLevedException() {
        assertThatThrownBy(() -> search.planTrip("GareFantôme", "Tanger", REF_DATE))
            .isInstanceOf(StationNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Utilitaires de test
    // -------------------------------------------------------------------------
    // (données test centralisées dans TestGtfsData)
}
