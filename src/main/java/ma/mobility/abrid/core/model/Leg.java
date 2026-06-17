package ma.mobility.abrid.core.model;

/**
 * Segment élémentaire d'un trajet (un seul véhicule / mode).
 *
 * <p>Le temps est exprimé en <strong>secondes depuis minuit du jour de service</strong>.
 * Cette convention permet de gérer les services passant minuit sans ambiguïté
 * (ex. GTFS "25:20:00" = 91200 secondes = 01:20 le lendemain).
 *
 * @param fareMad Tarif en dirhams — null si inconnu.
 */
public record Leg(
        Station fromStation,
        Station toStation,
        int departureSec,
        int arrivalSec,
        Mode mode,
        String tripId,
        String routeId,
        String routeName,
        String headsign,
        Double fareMad
) {
    /** Durée du segment en secondes. */
    public int durationSeconds() {
        return arrivalSec - departureSec;
    }
}
