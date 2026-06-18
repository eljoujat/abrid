package ma.mobility.abrid.agent;

import java.util.List;

/**
 * Result returned by the {@code get_station_info} tool.
 *
 * @param status     "found" | "ambiguous" | "not_found".
 * @param stations   Matching stations (1 when found, N when ambiguous).
 * @param message    Human-readable summary.
 */
public record StationToolResult(
        String status,
        List<StationInfo> stations,
        String message
) {
    /** Compact station info for LLM consumption. */
    public record StationInfo(
            String id,
            String name,
            Double lat,
            Double lon,
            String mode
    ) {}

    public static StationToolResult found(StationInfo station) {
        return new StationToolResult("found", List.of(station),
            "Station found: " + station.name() + " (id=" + station.id() + ").");
    }

    public static StationToolResult ambiguous(String query, List<StationInfo> candidates) {
        return new StationToolResult("ambiguous", candidates,
            "Query '" + query + "' matches " + candidates.size() + " stations. "
                + "Ask the user which one they mean.");
    }

    public static StationToolResult notFound(String query) {
        return new StationToolResult("not_found", List.of(),
            "No station matching '" + query + "' found in the database.");
    }
}
