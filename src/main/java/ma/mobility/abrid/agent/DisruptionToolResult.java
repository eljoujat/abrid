package ma.mobility.abrid.agent;

import java.util.List;

/**
 * Result returned by the {@code get_disruptions} tool.
 *
 * @param disruptions Active disruptions at the time of the call.
 * @param message     Human-readable summary.
 */
public record DisruptionToolResult(
        List<DisruptionInfo> disruptions,
        String message
) {
    /** Compact disruption info for LLM narration. */
    public record DisruptionInfo(
            String routeId,
            String type,
            String severity,
            String description,
            String startsAt,
            String endsAt
    ) {}

    public static DisruptionToolResult none() {
        return new DisruptionToolResult(List.of(), "No active disruptions at this time.");
    }

    public static DisruptionToolResult found(List<DisruptionInfo> disruptions) {
        return new DisruptionToolResult(disruptions,
            disruptions.size() + " active disruption(s) found.");
    }
}
