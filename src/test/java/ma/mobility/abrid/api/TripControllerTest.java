package ma.mobility.abrid.api;

import ma.mobility.abrid.TestGtfsData;
import ma.mobility.abrid.core.loader.GtfsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests des endpoints HTTP — base SQLite en mémoire. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripControllerTest {

    @Autowired MockMvc     mvc;
    @Autowired GtfsLoader  loader;

    @BeforeEach
    void loadData() throws Exception {
        loader.ingest(TestGtfsData.buildZip(), "test://api", false);
    }

    @Test
    void healthReturnsOk() throws Exception {
        mvc.perform(get("/actuator/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void listStationsReturnsAll() throws Exception {
        mvc.perform(get("/stations"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void listStationsFilter() throws Exception {
        mvc.perform(get("/stations").param("q", "Tanger"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].id").value("TANGER"));
    }

    @Test
    void planTripDirectReturns200() throws Exception {
        mvc.perform(get("/plan_trip")
               .param("from_station", "Tanger")
               .param("to_station",   "Casa")
               .param("travel_date",  "2024-09-02"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.journeys", hasSize(greaterThan(0))))
           .andExpect(jsonPath("$.journeys[0].nbTransfers").value(0));
    }

    @Test
    void planTripWithTransferReturns200() throws Exception {
        mvc.perform(get("/plan_trip")
               .param("from_station", "Tanger")
               .param("to_station",   "Marrakech")
               .param("travel_date",  "2024-09-02"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.journeys[0].nbTransfers").value(1));
    }

    @Test
    void planTripNoDataReturns404() throws Exception {
        // OD non couvert → 404, jamais un trajet inventé
        mvc.perform(get("/plan_trip")
               .param("from_station", "Fes")
               .param("to_station",   "Marrakech")
               .param("travel_date",  "2024-09-02"))
           .andExpect(status().isNotFound());
    }

    @Test
    void planTripUnknownStationReturns404() throws Exception {
        mvc.perform(get("/plan_trip")
               .param("from_station", "GareInexistante")
               .param("to_station",   "Tanger")
               .param("travel_date",  "2024-09-02"))
           .andExpect(status().isNotFound());
    }

    @Test
    void planTripInvalidDateReturns422() throws Exception {
        mvc.perform(get("/plan_trip")
               .param("from_station", "Tanger")
               .param("to_station",   "Casa")
               .param("travel_date",  "pas-une-date"))
           .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void scheduleReturnsDepartures() throws Exception {
        mvc.perform(get("/schedule")
               .param("station",     "Tanger")
               .param("travel_date", "2024-09-02"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.station.id").value("TANGER"))
           .andExpect(jsonPath("$.departures", hasSize(greaterThan(0))));
    }
}
