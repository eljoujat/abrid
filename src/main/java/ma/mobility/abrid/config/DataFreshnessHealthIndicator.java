package ma.mobility.abrid.config;

import ma.mobility.abrid.core.store.StoreRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Indicateur de santé Actuator : fraîcheur des données ingérées.
 *
 * <p>Expose l'état via {@code /actuator/health} sous la clé {@code dataFreshness}.
 * Règles :
 * <ul>
 *   <li>UP si la dernière ingestion réussie date de moins de 48h</li>
 *   <li>DOWN si aucune ingestion n'a jamais eu lieu</li>
 *   <li>OUT_OF_SERVICE si la dernière ingestion date de plus de 48h</li>
 * </ul>
 */
@Component
public class DataFreshnessHealthIndicator implements HealthIndicator {

    /** Délai maximal acceptable entre deux ingestions. */
    private static final long MAX_FRESHNESS_HOURS = 48;

    private final StoreRepository store;

    public DataFreshnessHealthIndicator(StoreRepository store) {
        this.store = store;
    }

    @Override
    public Health health() {
        return store.getMeta("ingested_at").map(ingestedAtStr -> {
            try {
                Instant ingestedAt = Instant.parse(ingestedAtStr);
                long ageHours = ChronoUnit.HOURS.between(ingestedAt, Instant.now());
                double coveragePct = store.getMeta("coverage_pct")
                    .map(Double::parseDouble).orElse(0.0);
                String sourceUrl = store.getMeta("source_url").orElse("inconnu");

                if (ageHours > MAX_FRESHNESS_HOURS) {
                    return Health.outOfService()
                        .withDetail("ingestedAt",   ingestedAtStr)
                        .withDetail("ageHours",     ageHours)
                        .withDetail("maxAgeHours",  MAX_FRESHNESS_HOURS)
                        .withDetail("coveragePct",  coveragePct)
                        .withDetail("source",       sourceUrl)
                        .withDetail("message", "Données expirées — ingestion requise")
                        .build();
                }

                return Health.up()
                    .withDetail("ingestedAt",  ingestedAtStr)
                    .withDetail("ageHours",    ageHours)
                    .withDetail("coveragePct", coveragePct)
                    .withDetail("source",      sourceUrl)
                    .build();

            } catch (Exception e) {
                return Health.down()
                    .withDetail("error", "Format de date invalide : " + ingestedAtStr)
                    .build();
            }
        }).orElseGet(() ->
            Health.down()
                .withDetail("message", "Aucune ingestion enregistrée. Lancer l'ingestion initiale.")
                .build()
        );
    }
}
