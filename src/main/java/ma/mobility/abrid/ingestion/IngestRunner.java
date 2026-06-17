package ma.mobility.abrid.ingestion;

import ma.mobility.abrid.core.loader.GtfsLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Lance l'ingestion GTFS au démarrage si INGEST_ON_STARTUP=true.
 * En mode worker (WORKER_MODE=true), quitte l'application après ingestion.
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final GtfsLoader loader;

    @Value("${INGEST_ON_STARTUP:false}")
    private boolean ingestOnStartup;

    @Value("${WORKER_MODE:false}")
    private boolean workerMode;

    @Value("${gtfs.source.url:" + GtfsLoader.GTFS_DEV_URL + "}")
    private String gtfsUrl;

    @Value("${gtfs.respect-feed-dates:false}")
    private boolean respectFeedDates;

    public IngestRunner(GtfsLoader loader) {
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!ingestOnStartup) return;

        log.info("Ingestion GTFS démarrée (url={})…", gtfsUrl);
        try {
            var zip    = loader.download(gtfsUrl);
            var report = loader.ingest(zip, gtfsUrl, respectFeedDates);
            log.info("Ingestion réussie. Couverture : {}%", report.coveragePct());
        } catch (Exception e) {
            log.error("Ingestion échouée : {}", e.getMessage(), e);
            if (workerMode) System.exit(1);
            return;
        }

        if (workerMode) {
            log.info("Mode worker : arrêt après ingestion.");
            System.exit(0);
        }
    }
}
