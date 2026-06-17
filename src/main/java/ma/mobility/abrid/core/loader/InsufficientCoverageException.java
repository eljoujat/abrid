package ma.mobility.abrid.core.loader;

/**
 * Levée quand la couverture d'une nouvelle ingestion est en dessous du seuil
 * ou en dégradation par rapport à la couverture actuelle.
 *
 * <p>Lorsque cette exception est levée, la base n'a PAS été modifiée.
 * La validation se produit AVANT la phase de persistance.
 */
public class InsufficientCoverageException extends RuntimeException {

    public InsufficientCoverageException(String message) {
        super(message);
    }
}
