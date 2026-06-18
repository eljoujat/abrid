package ma.mobility.abrid.core.search;

import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Leg;
import ma.mobility.abrid.core.model.Mode;
import ma.mobility.abrid.core.model.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Vérifie que le controller utilise bien {@link JourneySearchPort}
 * et que {@link SearchService} (SQL) est l'implémentation active quand OTP est désactivé.
 */
@SpringBootTest
@ActiveProfiles("test")
class JourneySearchPortTest {

    private static final LocalDate DATE = LocalDate.of(2024, 9, 2);

    static final Station TANGER = new Station(
        "TANGER", "Tanger-Ville", 35.769, -5.800, Mode.TRAIN, List.of()
    );
    static final Station CASA = new Station(
        "CASA_VOYAGEURS", "Casa-Voyageurs", 33.590, -7.620, Mode.TRAIN, List.of()
    );

    @Autowired
    JourneySearchPort journeySearchPort;

    @Autowired
    ma.mobility.abrid.core.store.StoreRepository store;

    @org.junit.jupiter.api.BeforeEach
    void purge() {
        store.purgeAll();
    }

    // -------------------------------------------------------------------------
    // Quand OTP est désactivé, JourneySearchPort = SearchService
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("JourneySearchPort est SearchService quand OTP désactivé (profil test)")
    void journeySearchPortIsSearchServiceWhenOtpDisabled() {
        // app.otp.enabled=false dans application.properties test
        // → OtpJourneySearchService non créé, SearchService est le bean actif
        assertThat(journeySearchPort).isInstanceOf(SearchService.class);
    }

    @Test
    @DisplayName("NoDataException si OD non couvert (base vide)")
    void noDataExceptionOnEmptyDb() {
        // Base vide en début de test (pas d'ingestion dans ce test)
        assertThatThrownBy(() -> journeySearchPort.planTrip(TANGER, CASA, DATE, 0))
            .isInstanceOf(NoDataException.class);
    }
}
