package ma.mobility.abrid.agent;

import java.util.List;

/**
 * Result returned by the {@code plan_trip} tool.
 *
 * <p>The LLM reads {@code status} to decide how to narrate the response in darija:
 * <ul>
 *   <li>{@code "found"}        — journeys list is populated; narrate departure/arrival times.</li>
 *   <li>{@code "not_found"}    — no route exists; say honestly there is no connection.</li>
 *   <li>{@code "ambiguous"}    — station name matched several stops; ask the user to clarify.</li>
 *   <li>{@code "station_unknown"} — station name not recognized at all.</li>
 *   <li>{@code "error"}        — unexpected error; log and apologize.</li>
 * </ul>
 *
 * @param status     One of: found | not_found | ambiguous | station_unknown | error.
 * @param journeys   Non-null list of journeys (empty when status != "found").
 * @param candidates Candidate station names returned when status is "ambiguous".
 * @param message    Human-readable summary (English) for the LLM to translate/narrate.
 */
public record TripToolResult(
        String status,
        List<JourneyInfo> journeys,
        List<String> candidates,
        String message
) {
    /** Journey details for one trip option. */
    public record JourneyInfo(
            String from,
            String to,
            String departure,
            String arrival,
            int durationMinutes,
            int transfers,
            List<LegInfo> legs,
            String dataSource
    ) {}

    /** One segment of a journey (single vehicle / mode). */
    public record LegInfo(
            String from,
            String to,
            String departure,
            String arrival,
            int durationMinutes,
            String mode,
            String routeName
    ) {}

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static TripToolResult found(List<JourneyInfo> journeys) {
        int count = journeys.size();
        String msg = count == 1
            ? "1 journey found."
            : count + " journeys found. Earliest departure: "
                + (journeys.isEmpty() ? "N/A" : journeys.getFirst().departure()) + ".";
        return new TripToolResult("found", journeys, List.of(), msg);
    }

    public static TripToolResult notFound(String from, String to, String date) {
        return new TripToolResult("not_found", List.of(), List.of(),
            "No journey found from '" + from + "' to '" + to + "' on " + date
                + ". The O/D pair may not be covered by the current GTFS data.");
    }

    public static TripToolResult ambiguous(String query, List<String> candidates) {
        return new TripToolResult("ambiguous", List.of(), candidates,
            "Station name '" + query + "' is ambiguous. Candidates: " + candidates
                + ". Ask the user to specify which one.");
    }

    public static TripToolResult stationUnknown(String query) {
        return new TripToolResult("station_unknown", List.of(), List.of(),
            "Station '" + query + "' not found in the database.");
    }

    public static TripToolResult error(String detail) {
        return new TripToolResult("error", List.of(), List.of(),
            "Unexpected error: " + detail);
    }
}
