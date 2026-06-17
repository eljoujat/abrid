package ma.mobility.abrid.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Trajet complet, potentiellement multimodal (N legs, N modes).
 * Seul objet exposé par l'API et le skill.
 */
public record Journey(
        List<Leg> legs,
        String dataSource,
        String dataFreshnessDate
) {
    /** Durée totale en secondes (arrivée du dernier leg − départ du premier). */
    public int totalDurationSeconds() {
        if (legs.isEmpty()) return 0;
        return legs.getLast().arrivalSec() - legs.getFirst().departureSec();
    }

    /** Secondes depuis minuit du départ. */
    public int departureSec() {
        return legs.isEmpty() ? 0 : legs.getFirst().departureSec();
    }

    /** Secondes depuis minuit de l'arrivée. */
    public int arrivalSec() {
        return legs.isEmpty() ? 0 : legs.getLast().arrivalSec();
    }

    /** Nombre de correspondances (nb legs − 1). */
    public int nbTransfers() {
        return Math.max(0, legs.size() - 1);
    }

    /**
     * Tarif total en dirhams.
     * Vide si au moins un leg n'a pas de tarif connu.
     */
    public Optional<Double> totalFareMad() {
        if (legs.stream().anyMatch(l -> l.fareMad() == null)) {
            return Optional.empty();
        }
        return Optional.of(legs.stream().mapToDouble(Leg::fareMad).sum());
    }
}
