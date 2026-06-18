package ma.mobility.abrid.agent;

import ma.mobility.abrid.core.search.AmbiguousStationException;
import ma.mobility.abrid.core.search.JourneySearchPort;
import ma.mobility.abrid.core.search.NoDataException;
import ma.mobility.abrid.core.search.SearchService;
import ma.mobility.abrid.core.search.StationNotFoundException;
import ma.mobility.abrid.core.store.StoreRepository;
import ma.mobility.abrid.core.time.TimeUtils;
import ma.mobility.abrid.realtime.Disruption;
import ma.mobility.abrid.realtime.DisruptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * MCP tool implementations for the Abrid mobility skill.
 *
 * <p>Each method is annotated with {@code @Tool} and exposed by the MCP server
 * as a callable function for any compatible LLM (Claude, GPT-4, etc.).
 *
 * <p><strong>Key guarantee:</strong> no method ever invents data.
 * When information is unavailable, the result carries an explicit status
 * ({@code "not_found"}, {@code "ambiguous"}, etc.) so the LLM can narrate
 * honestly in darija.
 *
 * <p><strong>Darija handling:</strong> station names are resolved with
 * {@link SearchService#resolveStation} which is accent-insensitive and handles
 * partial matches — making it tolerant of darija transcriptions
 * (e.g. {@code "dar bidaa"}, {@code "casablanca"}, {@code "Casa"}).
 */
@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final SearchService     searchService;
    private final JourneySearchPort journeySearch;
    private final DisruptionService disruptionService;
    private final StoreRepository   store;

    public AgentTools(SearchService searchService,
                      JourneySearchPort journeySearch,
                      DisruptionService disruptionService,
                      StoreRepository store) {
        this.searchService     = searchService;
        this.journeySearch     = journeySearch;
        this.disruptionService = disruptionService;
        this.store             = store;
    }

    // =========================================================================
    // Tool: plan_trip
    // =========================================================================

    @Tool(
        name        = "plan_trip",
        description = """
            Plan a trip between two stations in Morocco.
            Resolves station names in French, darija or Arabic (accent-insensitive, partial match).
            Returns journey options with departure times, duration and transfer details.
            If the station name is ambiguous, returns a list of candidates so the user can clarify.
            NEVER invents trips — if no route exists the status is 'not_found'.
            """
    )
    public TripToolResult planTrip(
            @ToolParam(description =
                "Departure station name in French, darija or without accents. "
                + "Examples: 'tanger', 'Fès', 'dar bidaa', 'casa voyageurs', 'RBAT'")
            String from,

            @ToolParam(description =
                "Arrival station name. Same format as 'from'.")
            String to,

            @ToolParam(description =
                "Travel date in YYYY-MM-DD format. Use today's date if the user does not specify one.")
            String date
    ) {
        log.info("plan_trip: from='{}' to='{}' date='{}'", from, to, date);

        LocalDate travelDate;
        try {
            travelDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return TripToolResult.error("Invalid date format '" + date + "'. Use YYYY-MM-DD.");
        }

        try {
            var fromStation = searchService.resolveStation(from);
            var toStation   = searchService.resolveStation(to);
            var journeys    = journeySearch.planTrip(fromStation, toStation, travelDate, 0);

            var journeyInfos = journeys.stream()
                .map(j -> new TripToolResult.JourneyInfo(
                    j.legs().getFirst().fromStation().name(),
                    j.legs().getLast().toStation().name(),
                    TimeUtils.secondsToDisplay(j.departureSec()),
                    TimeUtils.secondsToDisplay(j.arrivalSec()),
                    j.totalDurationSeconds() / 60,
                    j.nbTransfers(),
                    j.legs().stream().map(l -> new TripToolResult.LegInfo(
                        l.fromStation().name(),
                        l.toStation().name(),
                        TimeUtils.secondsToDisplay(l.departureSec()),
                        TimeUtils.secondsToDisplay(l.arrivalSec()),
                        l.durationSeconds() / 60,
                        l.mode().name(),
                        l.routeName()
                    )).toList(),
                    j.dataSource()
                ))
                .toList();

            return TripToolResult.found(journeyInfos);

        } catch (AmbiguousStationException e) {
            // Ask the user which station they mean
            String ambiguousQuery = e.getMessage().contains("'") ? from : from;
            return TripToolResult.ambiguous(ambiguousQuery, e.getCandidates());

        } catch (StationNotFoundException e) {
            return TripToolResult.stationUnknown(e.getMessage());

        } catch (NoDataException e) {
            return TripToolResult.notFound(from, to, date);

        } catch (Exception e) {
            log.error("plan_trip unexpected error: {}", e.getMessage(), e);
            return TripToolResult.error(e.getMessage());
        }
    }

    // =========================================================================
    // Tool: get_schedule
    // =========================================================================

    @Tool(
        name        = "get_schedule",
        description = """
            Get the departure board for a station on a given date.
            Returns all trains departing from that station, sorted by time.
            Useful when the user asks "what trains leave from X today?".
            """
    )
    public ScheduleToolResult getSchedule(
            @ToolParam(description = "Station name (French, darija, partial match allowed).")
            String station,

            @ToolParam(description = "Date in YYYY-MM-DD format.")
            String date
    ) {
        log.info("get_schedule: station='{}' date='{}'", station, date);

        LocalDate travelDate;
        try {
            travelDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ScheduleToolResult.notFound(station, date);
        }

        try {
            var st = searchService.resolveStation(station);
            var rows = store.getStopTimesForStop(st.id());

            var departures = rows.stream()
                .filter(row -> {
                    String svcId = row.get("service_id").toString();
                    return isServiceActive(svcId, travelDate);
                })
                .map(row -> new ScheduleToolResult.DepartureInfo(
                    TimeUtils.secondsToDisplay(toInt(row.get("departure_seconds"))),
                    row.get("route_id").toString(),
                    row.get("headsign").toString(),
                    row.get("trip_id").toString()
                ))
                .sorted(java.util.Comparator.comparing(ScheduleToolResult.DepartureInfo::departure))
                .toList();

            if (departures.isEmpty()) {
                return ScheduleToolResult.notFound(st.name(), date);
            }
            return ScheduleToolResult.found(st.name(), date, departures);

        } catch (AmbiguousStationException e) {
            return ScheduleToolResult.ambiguous(station, e.getCandidates());

        } catch (StationNotFoundException e) {
            return ScheduleToolResult.stationUnknown(station);
        }
    }

    // =========================================================================
    // Tool: get_station_info
    // =========================================================================

    @Tool(
        name        = "get_station_info",
        description = """
            Look up a station by name and return its details (id, coordinates, transport mode).
            Useful to confirm which station the user is referring to before planning a trip.
            Handles partial names and darija transcriptions.
            """
    )
    public StationToolResult getStationInfo(
            @ToolParam(description =
                "Station query: partial name, darija transcription or exact French name. "
                + "Example: 'tanger', 'kenitra', 'rbat', 'dar bidaa'")
            String query
    ) {
        log.info("get_station_info: query='{}'", query);

        try {
            var station = searchService.resolveStation(query);
            return StationToolResult.found(new StationToolResult.StationInfo(
                station.id(), station.name(), station.lat(), station.lon(), station.mode().name()
            ));

        } catch (AmbiguousStationException e) {
            var candidates = e.getCandidates().stream()
                .map(name -> {
                    try {
                        var s = searchService.resolveStation(name);
                        return new StationToolResult.StationInfo(
                            s.id(), s.name(), s.lat(), s.lon(), s.mode().name()
                        );
                    } catch (Exception ex) {
                        return new StationToolResult.StationInfo(name, name, null, null, "TRAIN");
                    }
                })
                .toList();
            return StationToolResult.ambiguous(query, candidates);

        } catch (StationNotFoundException e) {
            return StationToolResult.notFound(query);
        }
    }

    // =========================================================================
    // Tool: get_disruptions
    // =========================================================================

    @Tool(
        name        = "get_disruptions",
        description = """
            Get active disruptions (delays, cancellations, diversions) on the ONCF network.
            Can be filtered by route ID. Returns an empty list if no disruptions are active.
            Note: the real-time source (ONCF TRAFIC) is not yet connected;
            disruptions may be empty unless added manually.
            """
    )
    public DisruptionToolResult getDisruptions(
            @ToolParam(description =
                "Optional route ID to filter disruptions (e.g. 'AL_BORAQ_TNG_CASA'). "
                + "Pass null or empty string for all active disruptions.")
            String routeId
    ) {
        log.info("get_disruptions: routeId='{}'", routeId);

        Instant now = Instant.now();
        List<Disruption> disruptions = (routeId != null && !routeId.isBlank())
            ? disruptionService.findActiveByRoute(routeId, now)
            : disruptionService.findActive(now);

        if (disruptions.isEmpty()) {
            return DisruptionToolResult.none();
        }

        var infos = disruptions.stream()
            .map(d -> new DisruptionToolResult.DisruptionInfo(
                d.routeId(),
                d.type().name(),
                d.severity().name(),
                d.description(),
                d.startsAt() != null ? d.startsAt().toString() : null,
                d.endsAt()   != null ? d.endsAt().toString()   : null
            ))
            .toList();

        return DisruptionToolResult.found(infos);
    }

    // =========================================================================
    // Tool: submit_correction
    // =========================================================================

    @Tool(
        name        = "submit_correction",
        description = """
            Submit a data correction or improvement suggestion.
            Use this when the user reports: wrong fare, incorrect schedule, missing station,
            wrong route, or any other data inaccuracy.
            The correction is stored for review by the data team.
            Correction types: FARE | ROUTE | STATION | SCHEDULE | OTHER.
            """
    )
    public CorrectionToolResult submitCorrection(
            @ToolParam(description =
                "Correction type: FARE (wrong price), ROUTE (wrong route), "
                + "STATION (wrong/missing station), SCHEDULE (wrong timetable), OTHER.")
            String type,

            @ToolParam(description =
                "Detailed description of the correction in any language (French, darija, English). "
                + "Include as much context as possible: current value, expected value, source.")
            String description,

            @ToolParam(description =
                "Optional: the ID or name of the affected entity (station ID, route ID, etc.).")
            String dataSource
    ) {
        log.info("submit_correction: type='{}' source='{}'", type, dataSource);

        try {
            String normalizedType = normalizeType(type);
            long id = store.insertCorrection(normalizedType, description, dataSource);
            return CorrectionToolResult.accepted(id);
        } catch (Exception e) {
            log.error("submit_correction error: {}", e.getMessage(), e);
            return CorrectionToolResult.error(e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private boolean isServiceActive(String serviceId, LocalDate date) {
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
                    toIntBool(cal, "monday"),    toIntBool(cal, "tuesday"),
                    toIntBool(cal, "wednesday"), toIntBool(cal, "thursday"),
                    toIntBool(cal, "friday"),    toIntBool(cal, "saturday"),
                    toIntBool(cal, "sunday")
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

    private static boolean toIntBool(java.util.Map<String, Object> row, String key) {
        return toInt(row.get(key)) != 0;
    }

    private static String normalizeType(String raw) {
        if (raw == null) return "OTHER";
        return switch (raw.toUpperCase().trim()) {
            case "FARE"     -> "FARE";
            case "ROUTE"    -> "ROUTE";
            case "STATION"  -> "STATION";
            case "SCHEDULE" -> "SCHEDULE";
            default         -> "OTHER";
        };
    }
}
