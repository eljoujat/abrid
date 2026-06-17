package ma.mobility.abrid.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Collecteur de perturbations temps réel ONCF.
 *
 * <p><strong>État actuel (Lot 1) :</strong> stub. Aucune API officielle ONCF
 * n'est disponible. Le branchement sur ONCF TRAFIC (app ou scraping conforme
 * aux CGU) est prévu au Lot 1-bis après validation juridique.
 *
 * <p><strong>Pour brancher une vraie source :</strong> implémenter la méthode
 * {@link #collectFromOncfTrafic()} en injectant un {@code RestClient} ou
 * un scraper jsoup, puis appeler {@code disruptionService.save(...)} pour
 * chaque perturbation trouvée.
 *
 * <p>Le nettoyage des perturbations expirées est exécuté à chaque cycle.
 */
@Component
public class DisruptionCollector {

    private static final Logger log = LoggerFactory.getLogger(DisruptionCollector.class);

    private final DisruptionService disruptionService;

    public DisruptionCollector(DisruptionService disruptionService) {
        this.disruptionService = disruptionService;
    }

    /**
     * Collecte périodique — toutes les 5 minutes.
     * Configurable via {@code app.disruption.cron} (défaut: toutes les 5 min).
     */
    @Scheduled(fixedRateString = "${app.disruption.poll-rate-ms:300000}")
    public void collect() {
        log.debug("Cycle de collecte des perturbations ONCF…");
        try {
            collectFromOncfTrafic();
            // Nettoyage des perturbations expirées depuis plus de 24h
            disruptionService.purgeExpired(Instant.now().minus(24, ChronoUnit.HOURS));
        } catch (Exception e) {
            log.warn("Collecte des perturbations échouée : {}", e.getMessage());
        }
    }

    /**
     * À implémenter : scraping/API ONCF TRAFIC.
     *
     * <p>Base légale à documenter avant activation (CGU ONCF).
     * Utiliser {@code RestClient} + jsoup pour le parsing HTML.
     *
     * <p>TODO Lot 1-bis : brancher la source réelle.
     */
    private void collectFromOncfTrafic() {
        // Stub — pas de source disponible pour l'instant.
        // Ne rien inventer, ne rien insérer.
        log.debug("collectFromOncfTrafic : stub — aucune source configurée.");
    }
}
