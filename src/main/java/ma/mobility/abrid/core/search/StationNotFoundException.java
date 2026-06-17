package ma.mobility.abrid.core.search;

/** Levée quand une gare n'est pas trouvée dans la base. */
public class StationNotFoundException extends RuntimeException {
    public StationNotFoundException(String query) {
        super("Gare inconnue : '" + query + "'");
    }
}
