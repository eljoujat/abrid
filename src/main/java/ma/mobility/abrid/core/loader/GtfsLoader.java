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

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/**
 * Loader GTFS — seule couche autorisée à connaître le format GTFS.
 *
 * <p>Le modèle de domaine, la recherche et l'API ne doivent jamais référencer
 * un concept GTFS brut (stop_id, trip_id dans les URL, etc.).
 *
 * <p>L'ingestion est <strong>idempotente</strong> : deux appels successifs
 * avec le même ZIP aboutissent au même état (purge complète + réingestion).
 *
 * <h2>Porte de couverture (Lot 1)</h2>
 * <p>L'ingestion est rejetée si la couverture du nouveau flux est inférieure
 * au seuil configuré ({@code app.worker.coverage-threshold}) ou inférieure
 * à la couverture actuelle. La validation se fait <strong>avant</strong>
 * toute modification de la base, garantissant que la base servie n'est
 * jamais corrompue.
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

    @Value("${gtfs.ssl.trust-all:false}")
    private boolean trustAll;

    @Value("${gtfs.proxy.url:}")
    private String proxyUrl;

    @Value("${gtfs.proxy.user:${PROXY_USER:}}")
    private String proxyUser;

    @Value("${gtfs.proxy.password:${PROXY_PASSWORD:}}")
    private String proxyPassword;

    @Value("${gtfs.download.connect-timeout:30}")
    private int connectTimeoutSec;

    @Value("${gtfs.download.read-timeout:120}")
    private int readTimeoutSec;

    /** Seuil minimum de couverture (%) en dessous duquel l'ingestion est rejetée. */
    @Value("${app.worker.coverage-threshold:50}")
    private double minCoveragePct;

    public GtfsLoader(StoreRepository store) {
        this.store = store;
    }

    // -------------------------------------------------------------------------
    // Téléchargement
    // -------------------------------------------------------------------------

    /**
     * Télécharge le flux GTFS depuis l'URL et retourne le contenu brut du ZIP.
     * Supporte {@code file://} pour les tests et le développement hors-réseau.
     */
    public byte[] download(String url) throws IOException, InterruptedException {
        if (url.startsWith("file://")) {
            var path = java.nio.file.Path.of(URI.create(url));
            log.info("Lecture GTFS depuis fichier local : {}", path);
            return java.nio.file.Files.readAllBytes(path);
        }
        log.info("Téléchargement GTFS depuis {}…", url);
        var builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(connectTimeoutSec));
        configureProxy(builder);
        if (trustAll) {
            log.warn("gtfs.ssl.trust-all=true : vérification SSL désactivée (dev only)");
            builder.sslContext(buildTrustAllSslContext());
        }
        var client  = builder.build();
        var request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(readTimeoutSec))
            .build();
        log.info("Téléchargement GTFS (connect-timeout={}s, read-timeout={}s)…",
            connectTimeoutSec, readTimeoutSec);
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
    // Ingestion — point d'entrée
    // -------------------------------------------------------------------------

    /**
     * Ingère un flux GTFS avec validation de la porte de couverture.
     *
     * <p>Algorithme :
     * <ol>
     *   <li>Parse le ZIP en mémoire (aucune écriture DB)</li>
     *   <li>Calcule la couverture du nouveau flux <em>en mémoire</em></li>
     *   <li>Compare avec la couverture actuelle et le seuil configuré</li>
     *   <li>Si validation OK → transaction atomique : purge + insert + meta</li>
     *   <li>Si validation KO → {@link InsufficientCoverageException} levée,
     *       la base n'est PAS modifiée</li>
     * </ol>
     *
     * @param gtfsZip          Contenu brut du fichier ZIP GTFS.
     * @param sourceUrl        URL source (pour les métadonnées).
     * @param respectFeedDates Si false, ignore les bornes de validité (mode dev).
     * @return Rapport de couverture de la nouvelle ingestion.
     * @throws InsufficientCoverageException si la couverture est insuffisante ou en dégradation.
     */
    public CoverageReport ingest(byte[] gtfsZip, String sourceUrl, boolean respectFeedDates)
            throws IOException {

        log.info("Début ingestion GTFS (source={}, respectFeedDates={})…",
            sourceUrl, respectFeedDates);

        // Phase 1 : parse en mémoire (ne touche pas la DB)
        ParsedGtfs parsed = readZip(gtfsZip);

        // Phase 2 : calcul de couverture AVANT toute modification DB
        String now = Instant.now().toString();
        CoverageReport newReport = computeCoverage(parsed, sourceUrl, now);

        log.info("Couverture calculée : {}% ({}/{} lignes avec horaires)",
            newReport.coveragePct(), newReport.routesWithTrips(), newReport.totalRoutes());

        // Phase 3 : porte de couverture (rejette sans modifier la DB si KO)
        validateCoverageGate(newReport);

        // Phase 4 : persistance atomique (transaction Spring)
        persistTransactional(parsed, sourceUrl, respectFeedDates, newReport, now);

        log.info("Ingestion terminée.\n{}", newReport);
        if (!newReport.routesWithoutTrips().isEmpty()) {
            log.warn("{} ligne(s) sans horaires : {}",
                newReport.routesWithoutTrips().size(), newReport.routesWithoutTrips());
        }
        return newReport;
    }

    // -------------------------------------------------------------------------
    // Phase 2 : calcul de couverture en mémoire
    // -------------------------------------------------------------------------

    private CoverageReport computeCoverage(ParsedGtfs parsed, String sourceUrl, String now) {
        Set<String> allRouteIds = new HashSet<>();
        for (var r : parsed.routes()) {
            String id = r.get("route_id");
            if (id != null && !id.isBlank()) allRouteIds.add(id);
        }

        Set<String> routeIdsWithTrips = new HashSet<>();
        for (var t : parsed.trips()) {
            String rid = t.get("route_id");
            if (rid != null && !rid.isBlank()) routeIdsWithTrips.add(rid);
        }

        List<String> routesWithout = allRouteIds.stream()
            .filter(id -> !routeIdsWithTrips.contains(id))
            .sorted()
            .toList();

        return new CoverageReport(
            allRouteIds.size(),
            routeIdsWithTrips.size(),
            routesWithout,
            parsed.trips().size(),
            parsed.stopTimes().size(),
            parsed.stops().size(),
            sourceUrl,
            now
        );
    }

    // -------------------------------------------------------------------------
    // Phase 3 : porte de couverture
    // -------------------------------------------------------------------------

    /**
     * Valide que le nouveau flux ne dégrade pas la couverture.
     *
     * @throws InsufficientCoverageException si la couverture est insuffisante.
     */
    private void validateCoverageGate(CoverageReport newReport) {
        double newCoverage = newReport.coveragePct();

        // Seuil absolu minimum
        if (newCoverage < minCoveragePct) {
            String msg = String.format(
                "Couverture insuffisante : %.1f%% < seuil minimum %.1f%%. Ingestion rejetée.",
                newCoverage, minCoveragePct);
            log.error(msg);
            throw new InsufficientCoverageException(msg);
        }

        // Dégradation par rapport à la couverture actuelle
        double currentCoverage = store.getMeta("coverage_pct")
            .map(s -> { try { return Double.parseDouble(s); } catch (Exception e) { return -1.0; } })
            .orElse(-1.0);

        if (currentCoverage >= 0 && newCoverage < currentCoverage) {
            String msg = String.format(
                "Dégradation de couverture détectée : %.1f%% → %.1f%%. Ingestion rejetée.",
                currentCoverage, newCoverage);
            log.error(msg);
            throw new InsufficientCoverageException(msg);
        }

        if (currentCoverage >= 0) {
            log.info("Couverture stable/améliorée : {:.1f}% → {:.1f}%",
                currentCoverage, newCoverage);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4 : persistance atomique (@Transactional)
    // -------------------------------------------------------------------------

    /**
     * Purge et réingère toutes les données dans une transaction atomique.
     * Si une erreur survient, la transaction est annulée et l'ancienne base est préservée.
     */
    @Transactional
    public void persistTransactional(ParsedGtfs parsed, String sourceUrl,
                                      boolean respectFeedDates,
                                      CoverageReport report, String now) {
        // Purge complète (tables de données seulement, pas ingestion_meta)
        store.purgeAll();

        // Ingestion dans l'ordre logique
        ingestStops(parsed.stops());
        ingestRoutes(parsed.routes());
        ingestCalendar(parsed.calendar());
        ingestCalendarDates(parsed.calendarDates());
        ingestTrips(parsed.trips());
        ingestStopTimes(parsed.stopTimes());

        // Mise à jour des métadonnées (dans la même transaction)
        store.setMeta("source_url",         sourceUrl);
        store.setMeta("ingested_at",        now);
        store.setMeta("respect_feed_dates", String.valueOf(respectFeedDates));
        store.setMeta("coverage_pct",       String.valueOf(report.coveragePct()));
        store.setMeta("total_routes",       String.valueOf(report.totalRoutes()));
        store.setMeta("routes_with_trips",  String.valueOf(report.routesWithTrips()));
    }

    // -------------------------------------------------------------------------
    // Lecture du ZIP
    // -------------------------------------------------------------------------

    /**
     * Représentation en mémoire d'un flux GTFS parsé, avant persistance.
     * Permet de valider la couverture AVANT de toucher la base.
     */
    public record ParsedGtfs(
        List<Map<String, String>> stops,
        List<Map<String, String>> routes,
        List<Map<String, String>> trips,
        List<Map<String, String>> stopTimes,
        List<Map<String, String>> calendar,
        List<Map<String, String>> calendarDates
    ) {}

    ParsedGtfs readZip(byte[] gtfsZip) throws IOException {
        Map<String, List<Map<String, String>>> entries = new HashMap<>();
        try (var zis = new ZipInputStream(new ByteArrayInputStream(gtfsZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (KNOWN_FILES.contains(name)) {
                    byte[] data = zis.readAllBytes();
                    entries.put(name, parseCsv(data));
                }
                zis.closeEntry();
            }
        }
        return new ParsedGtfs(
            entries.getOrDefault("stops.txt",          List.of()),
            entries.getOrDefault("routes.txt",         List.of()),
            entries.getOrDefault("trips.txt",          List.of()),
            entries.getOrDefault("stop_times.txt",     List.of()),
            entries.getOrDefault("calendar.txt",       List.of()),
            entries.getOrDefault("calendar_dates.txt", List.of())
        );
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
    // Ingestion par entité — connaissent le format GTFS, non exposées hors loader
    // -------------------------------------------------------------------------

    private void ingestStops(List<Map<String, String>> stops) {
        for (var row : stops) {
            String id   = row.get("stop_id");
            String name = row.getOrDefault("stop_name", "");
            Double lat  = parseDouble(row.get("stop_lat"));
            Double lon  = parseDouble(row.get("stop_lon"));
            store.insertStation(id, name, lat, lon, "TRAIN");
            String alias = TimeUtils.normalize(name);
            if (!alias.equals(name.toLowerCase(Locale.ROOT))) {
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

    private void configureProxy(HttpClient.Builder builder) {
        if (proxyUrl == null || proxyUrl.isBlank()) return;
        try {
            var uri  = URI.create(proxyUrl);
            var host = uri.getHost();
            var port = uri.getPort() == -1 ? 3128 : uri.getPort();
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            log.info("Proxy HTTP configuré : {}:{}", host, port);

            String user = proxyUser;
            String pass = proxyPassword;
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                user = parts[0];
                pass = parts.length > 1 ? parts[1] : "";
            }

            if (user != null && !user.isBlank()) {
                final String finalUser = user;
                final char[] finalPass = (pass != null ? pass : "").toCharArray();
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                System.setProperty("jdk.http.auth.proxying.disabledSchemes",  "");
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(finalUser, finalPass);
                        }
                        return null;
                    }
                });
                log.info("Authentification proxy configurée pour l'utilisateur : {}", finalUser);
            }
        } catch (Exception e) {
            log.warn("gtfs.proxy.url invalide '{}' : {}", proxyUrl, e.getMessage());
        }
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            }, null);
            return ctx;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("Impossible de créer le SSLContext trust-all", e);
        }
    }

    @SafeVarargs
    private static <T> T coalesce(T... values) {
        for (T v : values) if (v != null && !v.toString().isBlank()) return v;
        return null;
    }
}
