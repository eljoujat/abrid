package ma.mobility.abrid.api.dto;

import java.time.Instant;

/**
 * DTO immuable représentant une perturbation temps réel.
 * Retourné par {@code GET /disruptions}.
 */
public record DisruptionDto(
        Long id,
        String routeId,
        String stopId,
        String type,
        String severity,
        String description,
        Instant startsAt,
        Instant endsAt,
        String source
) {}
