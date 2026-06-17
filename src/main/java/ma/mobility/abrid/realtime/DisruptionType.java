package ma.mobility.abrid.realtime;

/** Type de perturbation réseau. */
public enum DisruptionType {
    /** Retard sur un ou plusieurs trains. */
    DELAY,
    /** Suppression de train. */
    CANCELLATION,
    /** Déviation d'itinéraire. */
    DIVERSION
}
