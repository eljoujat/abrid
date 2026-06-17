package ma.mobility.abrid.core.loader;

import ma.mobility.abrid.core.store.StoreRepository;
import ma.mobility.abrid.core.time.TimeUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loader GTFS — seule couche autorisée à connaître le format GTFS.
 *
 * <p>Le modèle de domaine, la recherche et l'API ne doivent jamais référencer
 * un concept GTFS brut (stop_id, trip_id dans les URL, etc.).
 *
 * <p>L'ingestion est <strong>idempotente</strong> : deux appels successifs
 * avec le même ZIP aboutissent au même état (purge complète + réingestion).
 */
@Service
public class GtfsLoader {

    private static final Logger log = LoggerFactory.getLogger(GtfsLoader.class);

    /** URL du flux GTFS communautaire (source de dev). */
    public static final String GTFS_DEV_URL =
        "https://github.com/newsbubbles/rail_maroc_oncf/raw/main/oncf_gtfs.zip";

    private static final Set<String> KNOWN_FILES = Set.of(
        "stops.txt", "routes.txt", "trips.txt", "stop_times.txt",
        "calendar.txt", "calendar_dates.txt"
    );

    private final StoreRepository store;

    @Value("${gtfs.source.url:" + GTFS_DEV_URL + "}")
    private String defaultSourceUrl;

    public GtfsLoader(StoreRepository store) {
        this.store = store;
    }

    // -------------------------------------------------------------------------
    // Téléchargement
    // -------------------------------------------------------------------------

    /** Télécharge le flux GTFS depuis l'URL et retourne le contenu brut du ZIP. Supporte file://. */
    public byte[] download(String url) throws IOException, InterruptedException {
        // Support fichier local (file:// ou chemin absolu)
        if (url.startsWith("file://")) {
            var path = java.nio.file.Path.of(URI.create(url));
            log.info("Lecture GTFS depuis fichier local : {}", path);
            return java.nio.file.Files.readAllBytes(path);
        }
        log.info("Téléchargement GTFS depuis {}…", url);
        var client  = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Téléchargement GTFS échoué, HTTP " + response.statusCode());
        }
        log.info("Téléchargement terminé ({} octets).", response.body().length);
        return response.body();
    }

    /** Télécharge depuis l'URL configurée. */
    public byte[] download() throws IOException, InterruptedException {
        return download(defaultSourceUrl);
    }

    // -------------------------------------------------------------------------
    // Ingestion
    // -------------------------------------------------------------------------

    /**
     * Ingère un flux GTFS dans la base.
     *
     * <p>Idempotent : purge toutes les tables avant réingestion.
     * Transactionnel : en cas d'erreur, la base n'est pas corrompue.
     *
     * @param gtfsZip          Contenu brut du fichier ZIP GTFS.
     * @param sourceUrl        URL source (pour les métadonnées).
     * @param respectFeedDates Si false, ignore les bornes de validité (mode dev).
     * @return Rapport de couverture.
     */
    @Transactional
    public CoverageReport ingest(byte[] gtfsZip, String sourceUrl, boolean respectFeedDates)
            throws IOException {

        log.info("Début ingestion GTFS (source={}, respectFeedDates={})…",
                sourceUrl, respectFeedDates);

        // Lecture de toutes les entrées du ZIP en mémoire
        Map<String, List<Map<String, String>>> entries = readZip(gtfsZip);

        // Purge complète (idempotence)
        store.purgeAll();

        // Ingestion dans l'ordre (contraintes FK)
        ingestStops(entries.getOrDefault("stops.txt", List.of()));
        ingestRoutes(entries.getOrDefault("routes.txt", List.of()));
        ingestCalendar(entries.getOrDefault("calendar.txt", List.of()));
        ingestCalendarDates(entries.getOrDefault("calendar_dates.txt", List.of()));
        ingestTrips(entries.getOrDefault("trips.txt", List.of()));
        ingestStopTimes(entries.getOrDefault("stop_times.txt", List.of()));

        // Métadonnées
        String now = Instant.now().toString();
        store.setMeta("source_url",          sourceUrl);
        store.setMeta("ingested_at",         now);
        store.setMeta("respect_feed_dates",  String.valueOf(respectFeedDates));

        // Rapport de couverture
        var allRoutes      = new HashSet<>(store.getAllRouteIds());
        var routesWithTrips = new HashSet<>(store.getRouteIdsWithTrips());
        var routesWithout   = allRoutes.stream()
                .filter(r -> !routesWithTrips.contains(r))
                .sorted()
                .toList();

        var report = new CoverageReport(
            allRoutes.size(),
            routesWithTrips.size(),
            routesWithout,
            store.countTrips(),
            store.countStopTimes(),
            store.countStations(),
            sourceUrl,
            now
        );

        store.setMeta("coverage_pct", String.valueOf(report.coveragePct()));

        log.info("Ingestion terminée.\n{}", report);

        if (!routesWithout.isEmpty()) {
            log.warn("{} ligne(s) sans horaires : {}", routesWithout.size(), routesWithout);
        }
        return report;
    }

    // -------------------------------------------------------------------------
    // Lecture du ZIP
    // -------------------------------------------------------------------------

    private Map<String, List<Map<String, String>>> readZip(byte[] gtfsZip) throws IOException {
        Map<String, List<Map<String, String>>> result = new HashMap<>();
        try (var zis = new ZipInputStream(new ByteArrayInputStream(gtfsZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (KNOWN_FILES.contains(name)) {
                    // Lecture de l'entrée complète avant de fermer le parser
                    byte[] data = zis.readAllBytes();
                    result.put(name, parseCsv(data));
                }
                zis.closeEntry();
            }
        }
        return result;
    }

    private List<Map<String, String>> parseCsv(byte[] data) throws IOException {
        var format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

        var rows = new ArrayList<Map<String, String>>();
        try (var parser = format.parse(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                rows.add(record.toMap());
            }
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Ingestion par entité — connaissent le format GTFS, non exposées
    // -------------------------------------------------------------------------

    private void ingestStops(List<Map<String, String>> stops) {
        for (var row : stops) {
            String id   = row.get("stop_id");
            String name = row.getOrDefault("stop_name", "");
            Double lat  = parseDouble(row.get("stop_lat"));
            Double lon  = parseDouble(row.get("stop_lon"));
            store.insertStation(id, name, lat, lon, "TRAIN");
            // Alias normalisé pour résolution insensible aux accents
            String alias = TimeUtils.normalize(name);
            if (!alias.equals(name.toLowerCase())) {
                store.insertAlias(id, alias);
            }
        }
    }

    private void ingestRoutes(List<Map<String, String>> routes) {
        for (var row : routes) {
            store.insertRoute(
                row.get("route_id"),
                row.getOrDefault("route_short_name", ""),
                row.getOrDefault("route_long_name", ""),
                "TRAIN"
            );
        }
    }

    private void ingestCalendar(List<Map<String, String>> calendar) {
        for (var row : calendar) {
            store.insertCalendar(
                row.get("service_id"),
                parseInt(row.get("monday")),
                parseInt(row.get("tuesday")),
                parseInt(row.get("wednesday")),
                parseInt(row.get("thursday")),
                parseInt(row.get("friday")),
                parseInt(row.get("saturday")),
                parseInt(row.get("sunday")),
                row.getOrDefault("start_date", ""),
                row.getOrDefault("end_date", "")
            );
        }
    }

    private void ingestCalendarDates(List<Map<String, String>> calendarDates) {
        for (var row : calendarDates) {
            store.insertCalendarDate(
                row.get("service_id"),
                row.get("date"),
                parseInt(row.get("exception_type"))
            );
        }
    }

    private void ingestTrips(List<Map<String, String>> trips) {
        for (var row : trips) {
            store.insertTrip(
                row.get("trip_id"),
                row.get("route_id"),
                row.get("service_id"),
                row.getOrDefault("trip_headsign", "")
            );
        }
    }

    private void ingestStopTimes(List<Map<String, String>> stopTimes) {
        int skipped = 0;
        for (var row : stopTimes) {
            String dep = coalesce(row.get("departure_time"), row.get("arrival_time"), "");
            String arr = coalesce(row.get("arrival_time"),  row.get("departure_time"), "");
            try {
                store.insertStopTime(
                    row.get("trip_id"),
                    row.get("stop_id"),
                    parseInt(row.get("stop_sequence")),
                    TimeUtils.gtfsTimeToSeconds(dep),
                    TimeUtils.gtfsTimeToSeconds(arr)
                );
            } catch (IllegalArgumentException e) {
                skipped++;
                log.debug("Horaire invalide ignoré : trip={} seq={} dep={} arr={}",
                    row.get("trip_id"), row.get("stop_sequence"), dep, arr);
            }
        }
        if (skipped > 0) {
            log.warn("{} horaire(s) invalide(s) ignoré(s).", skipped);
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires de parsing
    // -------------------------------------------------------------------------

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return null; }
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return 0; }
    }

    @SafeVarargs
    private static <T> T coalesce(T... values) {
        for (T v : values) if (v != null && !v.toString().isBlank()) return v;
        return null;
    }
}
