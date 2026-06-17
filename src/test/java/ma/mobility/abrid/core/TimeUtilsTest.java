package ma.mobility.abrid.core;

import ma.mobility.abrid.core.time.TimeUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/** Tests unitaires purs — aucune dépendance Spring. */
class TimeUtilsTest {

    @Test
    void gtfsTimeStandard() {
        assertThat(TimeUtils.gtfsTimeToSeconds("08:30:00")).isEqualTo(30600);
    }

    @Test
    void gtfsTimeMidnightPlus() {
        // 25:20:00 = 01:20 le lendemain
        assertThat(TimeUtils.gtfsTimeToSeconds("25:20:00")).isEqualTo(91200);
    }

    @Test
    void gtfsTimeInvalidThrows() {
        assertThatThrownBy(() -> TimeUtils.gtfsTimeToSeconds("invalid"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secondsToHhmm() {
        assertThat(TimeUtils.secondsToHhmm(30600)).isEqualTo("08:30");
        assertThat(TimeUtils.secondsToHhmm(91200)).isEqualTo("25:20");
    }

    @Test
    void secondsToDisplayNormal() {
        assertThat(TimeUtils.secondsToDisplay(30600)).isEqualTo("08:30");
    }

    @Test
    void secondsToDisplayMidnightPlus() {
        String display = TimeUtils.secondsToDisplay(91200);
        assertThat(display).contains("01:20").contains("J+1");
    }

    @Test
    void parseGtfsDateRoundtrip() {
        LocalDate d = LocalDate.of(2024, 9, 2);
        assertThat(TimeUtils.parseGtfsDate(TimeUtils.toGtfsDate(d))).isEqualTo(d);
    }

    @Test
    void isServiceActiveWeekday() {
        // REFERENCE_DATE = lundi 2 septembre 2024
        LocalDate date  = LocalDate.of(2024, 9, 2);
        boolean[] wd    = {true, true, true, true, true, false, false};
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end   = LocalDate.of(2025, 12, 31);
        assertThat(TimeUtils.isServiceActive(date, start, end, wd, true)).isTrue();
    }

    @Test
    void isServiceActiveOutsideRange() {
        LocalDate date  = LocalDate.of(2024, 9, 2);
        boolean[] wd    = {true, true, true, true, true, true, true};
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end   = LocalDate.of(2025, 12, 31);
        assertThat(TimeUtils.isServiceActive(date, start, end, wd, true)).isFalse();
    }

    @Test
    void isServiceActiveIgnoreDates() {
        // Mode dev : ignore les bornes
        LocalDate date  = LocalDate.of(2024, 9, 2);
        boolean[] wd    = {true, true, true, true, true, true, true};
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end   = LocalDate.of(2025, 12, 31);
        assertThat(TimeUtils.isServiceActive(date, start, end, wd, false)).isTrue();
    }

    @Test
    void normalize() {
        assertThat(TimeUtils.normalize("Fès")).isEqualTo("fes");
        assertThat(TimeUtils.normalize("Casablanca-Voyageurs")).isEqualTo("casablanca voyageurs");
        assertThat(TimeUtils.normalize("TANGER")).isEqualTo("tanger");
    }
}
