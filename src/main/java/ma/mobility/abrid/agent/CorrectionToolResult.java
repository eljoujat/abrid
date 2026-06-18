package ma.mobility.abrid.agent;

/**
 * Result returned by the {@code submit_correction} tool.
 *
 * @param status         "accepted" | "error".
 * @param correctionId   Generated ID, present when status is "accepted".
 * @param message        Human-readable confirmation for LLM narration.
 */
public record CorrectionToolResult(
        String status,
        Long correctionId,
        String message
) {
    public static CorrectionToolResult accepted(long id) {
        return new CorrectionToolResult("accepted", id,
            "Correction recorded (id=" + id + "). It will be reviewed by the data team. Thank you!");
    }

    public static CorrectionToolResult error(String detail) {
        return new CorrectionToolResult("error", null,
            "Failed to record correction: " + detail);
    }
}
