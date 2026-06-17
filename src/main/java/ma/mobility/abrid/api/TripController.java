package ma.mobility.abrid.api;

import ma.mobility.abrid.api.dto.*;
import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Leg;
import ma.mobility.abrid.core.model.Station;
import ma.mobility.abrid.core.search.SearchService;
import ma.mobility.abrid.core.store.StoreRepository;
import ma.mobility.abrid.core.time.TimeUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur REST — aucune logique métier ici.
 * Tout est délégué à SearchService et StoreRepository.
 */
@RestController
public class TripController {

    private final SearchService search;
    private final StoreRepository store;

    public TripController(SearchService search, StoreRepository store) {
        this.search = search;
        this.store  = store;
    }

    // -------------------------------------------------------------------------
    // /health
    // -------------------------------------------------------------------------

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status",       "ok",
            "lastIngestion", store.getMeta("ingested_at").orElse("jamais"),
            "coveragePct",   store.getMeta("coverage_pct").map(Double::parseDouble).orElse(0.0)
        );
    }

    // -------------------------------------------------------------------------
    // /stations
    // -------------------------------------------------------------------------

    @GetMapping("/stations")
    public List<StationDto> stations(
            @RequestParam(required = false) String q) {

        return store.getAllStations().stream()
            .filter(row -> q == null || row.get("name").toString().toLowerCase()
                .contains(q.toLowerCase()))
            .map(row -> new StationDto(
                row.get("id").toString(),
                row.get("name").toString(),
                row.get("lat") != null ? Double.parseDouble(row.get("lat").toString()) : null,
                row.get("lon") != null ? Double.parseDouble(row.get("lon").toString()) : null,
                row.get("mode").toString()
            ))
            .toList();
    }

    // -------------------------------------------------------------------------
    // /plan_trip
    // -------------------------------------------------------------------------

    @GetMapping("/plan_trip")
    public PlanTripResponse planTrip(
            @RequestParam("from_station") String fromStation,
            @RequestParam("to_station")   String toStation,
            @RequestParam("travel_date")  String travelDate) {

        LocalDate date = LocalDate.parse(travelDate);

        var from     = search.resolveStation(fromStation);
        var to       = search.resolveStation(toStation);
        var journeys = search.planTrip(fromStation, toStation, date);

        return new PlanTripResponse(
            journeys.stream().map(this::toDto).toList(),
            toDto(from),
            toDto(to),
            travelDate
        );
    }

    // -------------------------------------------------------------------------
    // /schedule
    // -------------------------------------------------------------------------

    @GetMapping("/schedule")
    public Map<String, Object> schedule(
            @RequestParam String station,
            @RequestParam("travel_date") String travelDate) {

        LocalDate date = LocalDate.parse(travelDate);
        var st = search.resolveStation(station);

        var departures = store.getStopTimesForStop(st.id()).stream()
            .filter(row -> {
                String svcId = row.get("service_id").toString();
                return isActive(svcId, date);
            })
            .map(row -> Map.of(
                "tripId",    row.get("trip_id").toString(),
                "routeId",   row.get("route_id").toString(),
                "headsign",  row.get("headsign").toString(),
                "departure", TimeUtils.secondsToDisplay(
                    Integer.parseInt(row.get("departure_seconds").toString()))
            ))
            .sorted(Comparator.comparing(m -> m.get("departure")))
            .toList();

        return Map.of(
            "station",    toDto(st),
            "travelDate", travelDate,
            "departures", departures
        );
    }

    // -------------------------------------------------------------------------
    // Convertisseurs domaine → DTO
    // -------------------------------------------------------------------------

    private PlanTripResponse toResponse(
            List<Journey> journeys, Station from, Station to, String date) {
        return new PlanTripResponse(
            journeys.stream().map(this::toDto).toList(),
            toDto(from), toDto(to), date
        );
    }

    private JourneyDto toDto(Journey j) {
        return new JourneyDto(
            j.legs().stream().map(this::toDto).toList(),
            j.totalDurationSeconds() / 60,
            j.nbTransfers(),
            TimeUtils.secondsToDisplay(j.departureSec()),
            TimeUtils.secondsToDisplay(j.arrivalSec()),
            j.totalFareMad().orElse(null),
            j.dataSource(),
            j.dataFreshnessDate()
        );
    }

    private LegDto toDto(Leg leg) {
        return new LegDto(
            toDto(leg.fromStation()),
            toDto(leg.toStation()),
            TimeUtils.secondsToDisplay(leg.departureSec()),
            TimeUtils.secondsToDisplay(leg.arrivalSec()),
            leg.durationSeconds() / 60,
            leg.mode().name(),
            leg.routeName(),
            leg.headsign(),
            leg.fareMad()
        );
    }

    private StationDto toDto(Station s) {
        return new StationDto(s.id(), s.name(), s.lat(), s.lon(), s.mode().name());
    }

    private boolean isActive(String serviceId, LocalDate date) {
        // Délégation à SearchService via réflexion interne — OK car même package
        // Pour éviter la duplication, on expose la logique calendrier via SearchService
        // Cette méthode est minimale pour /schedule
        try {
            var exceptions = store.getCalendarExceptions(serviceId);
            String dateStr = TimeUtils.toGtfsDate(date);
            for (var exc : exceptions) {
                if (dateStr.equals(exc.get("date").toString())) {
                    return (int) exc.get("exception_type") == 1;
                }
            }
            return store.getCalendar(serviceId).map(cal -> {
                boolean[] wd = {
                    intBool(cal, "monday"),    intBool(cal, "tuesday"),
                    intBool(cal, "wednesday"), intBool(cal, "thursday"),
                    intBool(cal, "friday"),    intBool(cal, "saturday"),
                    intBool(cal, "sunday")
                };
                String s  = cal.get("start_date") != null ? cal.get("start_date").toString() : "";
                String e  = cal.get("end_date")   != null ? cal.get("end_date").toString()   : "";
                LocalDate start = s.isBlank() ? date : TimeUtils.parseGtfsDate(s);
                LocalDate end   = e.isBlank() ? date : TimeUtils.parseGtfsDate(e);
                return TimeUtils.isServiceActive(date, start, end, wd, false);
            }).orElse(true);
        } catch (Exception ex) {
            return true;
        }
    }

    private static boolean intBool(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return false;
        if (v instanceof Integer i) return i != 0;
        if (v instanceof Long l)    return l != 0;
        try { return Integer.parseInt(v.toString()) != 0; } catch (NumberFormatException e) { return false; }
    }
}
