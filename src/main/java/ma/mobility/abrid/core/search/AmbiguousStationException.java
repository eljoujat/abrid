package ma.mobility.abrid.core.search;

import java.util.List;

/**
 * Thrown when a station query matches more than one station.
 * The agent tool uses {@link #getCandidates()} to ask the user to clarify.
 */
public class AmbiguousStationException extends RuntimeException {

    private final List<String> candidates;

    public AmbiguousStationException(String query, List<String> candidates) {
        super("Ambiguous station name '" + query + "': " + candidates
            + ". Please specify which one.");
        this.candidates = List.copyOf(candidates);
    }

    /** Station names that matched the query. */
    public List<String> getCandidates() {
        return candidates;
    }
}
