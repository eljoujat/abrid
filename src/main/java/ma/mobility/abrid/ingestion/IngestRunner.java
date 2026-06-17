package ma.mobility.abrid.ingestion;

import ma.mobility.abrid.config.IngestionScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Lance l'ingestion GTFS au démarrage si {@code INGEST_ON_STARTUP=true}.
 * Délègue l'exécution à {@link IngestionScheduler} (métriques + logging centralisés).
 *
 * <p>En mode worker ({@code WORKER_MODE=true}), quitte l'application après ingestion.
 * En mode API ({@code WORKER_MODE=false}), continue de servir les requêtes.
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final IngestionScheduler scheduler;

    @Value("${app.ingest-on-startup:false}")
    private boolean ingestOnStartup;

    @Value("${app.worker-mode:false}")
    private boolean workerMode;

    public IngestRunner(IngestionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ingestOnStartup) return;

        log.info("Ingestion GTFS au démarrage…");
        scheduler.runIngestion();

        if (workerMode) {
            log.info("Mode worker one-shot : arrêt après ingestion.");
            System.exit(0);
        }
    }
}
