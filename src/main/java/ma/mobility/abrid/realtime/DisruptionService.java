package ma.mobility.abrid.realtime;

import ma.mobility.abrid.core.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service de gestion des perturbations temps réel.
 *
 * <p>Expose les perturbations actives issues de la table {@code disruptions},
 * alimentée par {@link DisruptionCollector} (collecte périodique).
 *
 * <p>Ne génère jamais de perturbation fictive.
 * Si aucune donnée n'est disponible, la liste est vide.
 */
@Service
public class DisruptionService {

    private static final Logger log = LoggerFactory.getLogger(DisruptionService.class);

    private final StoreRepository store;

    public DisruptionService(StoreRepository store) {
        this.store = store;
    }

    /**
     * Retourne toutes les perturbations actives à l'instant donné.
     *
     * @param at Instant de référence (injecter {@code Clock} pour les tests).
     * @return Liste de perturbations actives, triée par gravité puis heure de début.
     */
    public List<Disruption> findActive(Instant at) {
        return store.findActiveDisruptions(at).stream()
            .map(this::toDisruption)
            .toList();
    }

    /**
     * Retourne les perturbations actives sur une ligne donnée.
     *
     * @param routeId Identifiant de la ligne.
     * @param at      Instant de référence.
     */
    public List<Disruption> findActiveByRoute(String routeId, Instant at) {
        return store.findActiveDisruptionsByRoute(routeId, at).stream()
            .map(this::toDisruption)
            .toList();
    }

    /**
     * Enregistre une nouvelle perturbation.
     * Utilisé par le collecteur et (Lot 3) par le skill d'administration.
     *
     * @param disruption Perturbation à persister (id doit être null).
     * @return Perturbation avec son id généré.
     */
    public Disruption save(Disruption disruption) {
        long id = store.insertDisruption(
            disruption.routeId(),
            disruption.stopId(),
            disruption.type().name(),
            disruption.severity().name(),
            disruption.description(),
            disruption.startsAt(),
            disruption.endsAt(),
            disruption.source()
        );
        log.info("Perturbation enregistrée : id={} type={} route={}",
            id, disruption.type(), disruption.routeId());
        return new Disruption(
            id,
            disruption.routeId(),
            disruption.stopId(),
            disruption.type(),
            disruption.severity(),
            disruption.description(),
            disruption.startsAt(),
            disruption.endsAt(),
            disruption.source()
        );
    }

    /**
     * Supprime les perturbations expirées (nettoyage périodique).
     *
     * @param before Timestamp avant lequel purger.
     * @return Nombre de perturbations supprimées.
     */
    public int purgeExpired(Instant before) {
        int count = store.purgeExpiredDisruptions(before);
        if (count > 0) {
            log.info("{} perturbation(s) expirée(s) supprimée(s).", count);
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Mapping DB → domaine
    // -------------------------------------------------------------------------

    private Disruption toDisruption(Map<String, Object> row) {
        return new Disruption(
            toLong(row.get("id")),
            str(row, "route_id"),
            str(row, "stop_id"),
            parseEnum(DisruptionType.class,     str(row, "type"),     DisruptionType.DELAY),
            parseEnum(DisruptionSeverity.class, str(row, "severity"), DisruptionSeverity.LOW),
            str(row, "description"),
            toInstant(row.get("starts_at")),
            toInstant(row.get("ends_at")),
            str(row, "source")
        );
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        return null;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> cls, String value, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Enum.valueOf(cls, value.toUpperCase()); } catch (Exception e) { return fallback; }
    }
}
