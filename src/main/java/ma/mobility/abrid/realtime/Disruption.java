package ma.mobility.abrid.realtime;

import java.time.Instant;

/**
 * Perturbation temps réel sur le réseau ONCF.
 * Objet de domaine immuable (record Java 21).
 *
 * @param id          Identifiant technique (null pour les objets non encore persistés).
 * @param routeId     Ligne concernée (null = toutes les lignes).
 * @param stopId      Gare concernée (null = toute la ligne).
 * @param type        Type de perturbation.
 * @param severity    Niveau de gravité.
 * @param description Description humaine (en français ou darija).
 * @param startsAt    Début de la perturbation (null = immédiate).
 * @param endsAt      Fin prévue (null = durée indéterminée).
 * @param source      Source de l'information (ex: "ONCF_TRAFIC", "manuel").
 */
public record Disruption(
        Long id,
        String routeId,
        String stopId,
        DisruptionType type,
        DisruptionSeverity severity,
        String description,
        Instant startsAt,
        Instant endsAt,
        String source
) {}
