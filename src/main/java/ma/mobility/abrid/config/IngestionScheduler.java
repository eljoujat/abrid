package ma.mobility.abrid.config;

import ma.mobility.abrid.core.loader.GtfsLoader;
import ma.mobility.abrid.core.loader.InsufficientCoverageException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker d'ingestion GTFS périodique.
 *
 * <p>Actif uniquement si {@code app.worker-mode=true}.
 * Utilise {@code @Scheduled(cron)} pour rejouer l'ingestion selon le cron configuré.
 *
 * <p>Métriques Micrometer exposées :
 * <ul>
 *   <li>{@code abrid.ingestion.success} — nombre d'ingestions réussies</li>
 *   <li>{@code abrid.ingestion.failure} — nombre d'échecs (réseau, couverture...)</li>
 * </ul>
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final GtfsLoader loader;
    private final Counter successCounter;
    private final Counter failureCounter;

    @Value("${gtfs.source.url:" + GtfsLoader.GTFS_DEV_URL + "}")
    private String gtfsUrl;

    @Value("${gtfs.respect-feed-dates:false}")
    private boolean respectFeedDates;

    @Value("${app.worker-mode:false}")
    private boolean workerMode;

    public IngestionScheduler(GtfsLoader loader, MeterRegistry meterRegistry) {
        this.loader = loader;
        this.successCounter = Counter.builder("abrid.ingestion.success")
            .description("Nombre d'ingestions GTFS réussies")
            .register(meterRegistry);
        this.failureCounter = Counter.builder("abrid.ingestion.failure")
            .description("Nombre d'ingestions GTFS échouées")
            .register(meterRegistry);
    }

    /**
     * Ingestion périodique déclenchée par cron.
     * N'est effective que si {@code app.worker-mode=true}.
     */
    @Scheduled(cron = "${app.worker.cron:0 0 3 * * *}")
    public void scheduledIngestion() {
        if (!workerMode) {
            log.debug("Mode worker désactivé — ingestion planifiée ignorée.");
            return;
        }
        log.info("Démarrage ingestion planifiée (cron)…");
        runIngestion();
    }

    /**
     * Exécute une ingestion et met à jour les métriques.
     * Peut être appelé manuellement (ex: IngestRunner au démarrage).
     */
    public void runIngestion() {
        try {
            var zip    = loader.download(gtfsUrl);
            var report = loader.ingest(zip, gtfsUrl, respectFeedDates);
            successCounter.increment();
            log.info("Ingestion planifiée réussie. Couverture : {}%", report.coveragePct());
        } catch (InsufficientCoverageException e) {
            failureCounter.increment();
            log.error("Ingestion rejetée — porte de couverture : {}", e.getMessage());
        } catch (Exception e) {
            failureCounter.increment();
            log.error("Ingestion planifiée échouée : {}", e.getMessage(), e);
        }
    }
}
