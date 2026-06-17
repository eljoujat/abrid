package ma.mobility.abrid.core.loader;

import java.util.List;

/**
 * Rapport de couverture des données ingérées.
 * Exposé via /health et les logs d'ingestion.
 * NE PAS masquer les trous de données : les mesurer et les exposer.
 */
public record CoverageReport(
        int totalRoutes,
        int routesWithTrips,
        List<String> routesWithoutTrips,
        int totalTrips,
        int totalStopTimes,
        int totalStations,
        String sourceUrl,
        String ingestedAt
) {
    /** Pourcentage de lignes couvertes (avec au moins un trajet). */
    public double coveragePct() {
        if (totalRoutes == 0) return 0.0;
        return Math.round(1000.0 * routesWithTrips / totalRoutes) / 10.0;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("=== Rapport de couverture GTFS ===\n");
        sb.append("Source      : ").append(sourceUrl).append('\n');
        sb.append("Ingéré à    : ").append(ingestedAt).append('\n');
        sb.append("Lignes total: ").append(totalRoutes).append('\n');
        sb.append("Avec trajets: ").append(routesWithTrips)
          .append(" (").append(coveragePct()).append("%)\n");
        sb.append("Sans trajets: ").append(routesWithoutTrips.size()).append('\n');
        routesWithoutTrips.forEach(r -> sb.append("  - ").append(r).append('\n'));
        sb.append("Trajets     : ").append(totalTrips).append('\n');
        sb.append("Horaires    : ").append(totalStopTimes).append('\n');
        sb.append("Gares       : ").append(totalStations).append('\n');
        return sb.toString();
    }
}
