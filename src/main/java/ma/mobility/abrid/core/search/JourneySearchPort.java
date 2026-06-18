package ma.mobility.abrid.core.search;

import ma.mobility.abrid.core.model.Journey;
import ma.mobility.abrid.core.model.Station;

import java.time.LocalDate;
import java.util.List;

/**
 * Port de recherche de trajets — interface stable entre le contrôleur et les moteurs.
 *
 * <p>Deux implémentations :
 * <ol>
 *   <li>{@code SearchService} — moteur SQL (fallback, toujours disponible)</li>
 *   <li>{@code OtpJourneySearchService} — client OpenTripPlanner 2.x
 *       ({@code @Primary}, conditionné par {@code app.otp.enabled=true}).
 *       Bascule automatiquement sur le SQL si OTP est indisponible.</li>
 * </ol>
 *
 * <p>Le contrat est strictement multimodal : un {@link Journey} peut contenir
 * des legs TRAIN, BUS, TAXI ou WALK sans que l'appelant le sache.
 */
public interface JourneySearchPort {

    /**
     * Planifie un trajet entre deux gares déjà résolues.
     *
     * @param from      Gare de départ (coordonnées requises pour OTP).
     * @param to        Gare d'arrivée.
     * @param date      Date de voyage.
     * @param minDepSec Départ minimum en secondes depuis minuit (0 = sans contrainte).
     * @return Liste de trajets triés par heure de départ, jamais nulle.
     * @throws NoDataException si aucun trajet trouvé — NE PAS inventer.
     */
    List<Journey> planTrip(Station from, Station to, LocalDate date, int minDepSec);
}
