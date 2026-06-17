package ma.mobility.abrid.core.search;

/**
 * OD non couvert ou aucun trajet disponible.
 * NE JAMAIS inventer un résultat — lever cette exception à la place.
 */
public class NoDataException extends RuntimeException {
    public NoDataException(String from, String to, String date) {
        super("Aucun trajet trouvé de '%s' vers '%s' le %s. OD non couvert par les données actuelles."
            .formatted(from, to, date));
    }
}
