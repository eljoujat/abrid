package ma.mobility.abrid.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active le support des annotations {@code @Scheduled}.
 * Utilisé pour l'ingestion périodique (IngestionScheduler)
 * et la collecte temps réel (DisruptionCollector).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // Activation uniquement — pas de bean à déclarer
}
