package ma.mobility.abrid.agent;

import java.util.List;

/**
 * Result returned by the {@code get_schedule} tool.
 *
 * @param status     "found" | "not_found" | "ambiguous" | "station_unknown" | "error".
 * @param station    Canonical station name.
 * @param date       Travel date (YYYY-MM-DD).
 * @param departures List of departure entries.
 * @param candidates Candidate names when status is "ambiguous".
 * @param message    Human-readable summary for LLM narration.
 */
public record ScheduleToolResult(
        String status,
        String station,
        String date,
        List<DepartureInfo> departures,
        List<String> candidates,
        String message
) {
    /** Single departure entry. */
    public record DepartureInfo(
            String departure,
            String routeName,
            String headsign,
            String tripId
    ) {}

    public static ScheduleToolResult found(String station, String date,
                                            List<DepartureInfo> departures) {
        return new ScheduleToolResult("found", station, date, departures, List.of(),
            departures.size() + " departure(s) from " + station + " on " + date + ".");
    }

    public static ScheduleToolResult notFound(String station, String date) {
        return new ScheduleToolResult("not_found", station, date, List.of(), List.of(),
            "No departures found from '" + station + "' on " + date + ".");
    }

    public static ScheduleToolResult ambiguous(String query, List<String> candidates) {
        return new ScheduleToolResult("ambiguous", query, null, List.of(), candidates,
            "Station '" + query + "' is ambiguous. Candidates: " + candidates + ".");
    }

    public static ScheduleToolResult stationUnknown(String query) {
        return new ScheduleToolResult("station_unknown", query, null, List.of(), List.of(),
            "Station '" + query + "' not found.");
    }
}
