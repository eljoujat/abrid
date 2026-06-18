package ma.mobility.abrid.api;

import ma.mobility.abrid.api.dto.*;
import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Leg;
import ma.mobility.abrid.core.model.Station;
import ma.mobility.abrid.core.search.JourneySearchPort;
import ma.mobility.abrid.core.search.SearchService;
import ma.mobility.abrid.core.store.StoreRepository;
import ma.mobility.abrid.core.time.TimeUtils;
import ma.mobility.abrid.realtime.Disruption;
import ma.mobility.abrid.realtime.DisruptionService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur REST — aucune logique métier ici.
 * Tout est délégué aux services de la couche search et realtime.
 */
@RestController
public class TripController {

    private final SearchService      search;
    private final StoreRepository    store;
    private final DisruptionService  disruptionService;
    private final JourneySearchPort  journeySearch;

    public TripController(SearchService search,
                          StoreRepository store,
                          DisruptionService disruptionService,
                          JourneySearchPort journeySearch) {
        this.search            = search;
        this.store             = store;
        this.disruptionService = disruptionService;
        this.journeySearch     = journeySearch;
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
                row.get("lat")  != null ? Double.parseDouble(row.get("lat").toString())  : null,
                row.get("lon")  != null ? Double.parseDouble(row.get("lon").toString())  : null,
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
        var from       = search.resolveStation(fromStation);
        var to         = search.resolveStation(toStation);
        // Routing via OTP (si actif) ou SQL fallback
        var journeys   = journeySearch.planTrip(from, to, date, 0);

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
            .filter(row -> isActive(row.get("service_id").toString(), date))
            .map(row -> Map.of(
                "tripId",    row.get("trip_id").toString(),
                "routeId",   row.get("route_id").toString(),
                "headsign",  row.get("headsign").toString(),
                "departure", TimeUtils.secondsToDisplay(
                    toInt(row.get("departure_seconds")))
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
    // /disruptions
    // -------------------------------------------------------------------------

    /**
     * Retourne les perturbations actives.
     *
     * @param routeId Filtre optionnel par identifiant de ligne.
     */
    @GetMapping("/disruptions")
    public List<DisruptionDto> disruptions(
            @RequestParam(required = false) String routeId) {

        Instant now = Instant.now();
        List<Disruption> disruptions = routeId != null && !routeId.isBlank()
            ? disruptionService.findActiveByRoute(routeId, now)
            : disruptionService.findActive(now);

        return disruptions.stream().map(this::toDto).toList();
    }

    // -------------------------------------------------------------------------
    // Convertisseurs domaine → DTO
    // -------------------------------------------------------------------------

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

    private DisruptionDto toDto(Disruption d) {
        return new DisruptionDto(
            d.id(),
            d.routeId(),
            d.stopId(),
            d.type().name(),
            d.severity().name(),
            d.description(),
            d.startsAt(),
            d.endsAt(),
            d.source()
        );
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private boolean isActive(String serviceId, LocalDate date) {
        try {
            var exceptions = store.getCalendarExceptions(serviceId);
            String dateStr = TimeUtils.toGtfsDate(date);
            for (var exc : exceptions) {
                if (dateStr.equals(exc.get("date").toString())) {
                    return toInt(exc.get("exception_type")) == 1;
                }
            }
            return store.getCalendar(serviceId).map(cal -> {
                boolean[] wd = {
                    intBool(cal, "monday"),    intBool(cal, "tuesday"),
                    intBool(cal, "wednesday"), intBool(cal, "thursday"),
                    intBool(cal, "friday"),    intBool(cal, "saturday"),
                    intBool(cal, "sunday")
                };
                String s = cal.get("start_date") != null ? cal.get("start_date").toString() : "";
                String e = cal.get("end_date")   != null ? cal.get("end_date").toString()   : "";
                LocalDate start = s.isBlank() ? date : TimeUtils.parseGtfsDate(s);
                LocalDate end   = e.isBlank() ? date : TimeUtils.parseGtfsDate(e);
                return TimeUtils.isServiceActive(date, start, end, wd, false);
            }).orElse(true);
        } catch (Exception ex) {
            return true;
        }
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l)    return l.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean intBool(Map<String, Object> row, String key) {
        return toInt(row.get(key)) != 0;
    }
}
