package ma.mobility.abrid.core.time;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Utilitaires de manipulation du temps pour les horaires de transport.
 *
 * <p>Convention : le temps est en <strong>secondes depuis minuit du jour de service</strong>.
 * Permet de gérer les services passant minuit (ex. GTFS "25:20:00" = 91200 s = 01:20 J+1).
 */
public final class TimeUtils {

    private static final Pattern GTFS_TIME = Pattern.compile("^(\\d{1,3}):([0-5]\\d):([0-5]\\d)$");
    private static final Pattern DIACRITICS  = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TimeUtils() {}

    /**
     * Convertit une chaîne GTFS "HH:MM:SS" en secondes depuis minuit.
     * Accepte les valeurs > 24:00:00 (service passant minuit).
     */
    public static int gtfsTimeToSeconds(String gtfsTime) {
        var m = GTFS_TIME.matcher(gtfsTime.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Format de temps GTFS invalide : " + gtfsTime);
        }
        int h  = Integer.parseInt(m.group(1));
        int mn = Integer.parseInt(m.group(2));
        int s  = Integer.parseInt(m.group(3));
        return h * 3600 + mn * 60 + s;
    }

    /**
     * Formate des secondes en "HH:MM" pour l'affichage.
     * Supporte les valeurs > 86400 (service passant minuit).
     */
    public static String secondsToHhmm(int seconds) {
        int h  = seconds / 3600;
        int mn = (seconds % 3600) / 60;
        return "%02d:%02d".formatted(h, mn);
    }

    /**
     * Formate en "HH:MM" avec indication "J+1" si le service passe minuit.
     */
    public static String secondsToDisplay(int seconds) {
        if (seconds >= 86400) {
            int real = seconds - 86400;
            int h  = real / 3600;
            int mn = (real % 3600) / 60;
            return "%02d:%02d (J+1)".formatted(h, mn);
        }
        return secondsToHhmm(seconds);
    }

    /** Convertit une date GTFS "YYYYMMDD" en LocalDate. */
    public static LocalDate parseGtfsDate(String gtfsDate) {
        return LocalDate.parse(gtfsDate, GTFS_DATE);
    }

    /** Convertit un LocalDate en chaîne GTFS "YYYYMMDD". */
    public static String toGtfsDate(LocalDate date) {
        return date.format(GTFS_DATE);
    }

    /**
     * Détermine si un service est actif à la date donnée.
     *
     * @param weekdays   7 booléens [lun, mar, mer, jeu, ven, sam, dim].
     * @param respectFeedDates Si false, ignore les bornes de validité (mode dev).
     */
    public static boolean isServiceActive(
            LocalDate date,
            LocalDate startDate,
            LocalDate endDate,
            boolean[] weekdays,
            boolean respectFeedDates
    ) {
        if (respectFeedDates && (date.isBefore(startDate) || date.isAfter(endDate))) {
            return false;
        }
        // DayOfWeek : MONDAY=1 … SUNDAY=7
        int idx = date.getDayOfWeek().getValue() - 1;
        return weekdays[idx];
    }

    /**
     * Normalise un texte : minuscules, sans accents, sans tirets.
     * Utilisé pour la résolution insensible aux accents des noms de gares.
     */
    public static String normalize(String text) {
        String nfkd = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        return DIACRITICS.matcher(nfkd)
                .replaceAll("")
                .replace("-", " ")
                .replace("_", " ")
                .trim();
    }
}
