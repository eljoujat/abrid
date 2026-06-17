package ma.mobility.abrid.core.search;

/** Levée quand un terme correspond à plusieurs gares (ambiguïté). */
public class AmbiguousStationException extends RuntimeException {
    public AmbiguousStationException(String query, java.util.List<String> names) {
        super("Terme ambigu '%s' : plusieurs gares correspondent : %s. Précise le nom."
            .formatted(query, names));
    }
}
