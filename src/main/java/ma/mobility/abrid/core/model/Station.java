package ma.mobility.abrid.core.model;

import java.util.List;

/**
 * Gare ou arrêt, tous modes confondus.
 * Les aliases permettent la résolution insensible aux accents et aux dialectes.
 */
public record Station(
        String id,
        String name,
        Double lat,
        Double lon,
        Mode mode,
        List<String> aliases
) {
    public Station(String id, String name) {
        this(id, name, null, null, Mode.TRAIN, List.of());
    }
}
