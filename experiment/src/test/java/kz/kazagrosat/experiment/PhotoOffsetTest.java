package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PhotoOffsetTest {

    @Test
    void findsThePhotosOffsetOnFieldsThatDidNotChange() {
        int n = 30;
        double[] before = new double[n];
        double[] after = new double[n];
        double[] position = new double[n];
        double[] landsat = new double[n];
        for (int i = 0; i < n; i++) {
            before[i] = 0.20 + 0.002 * i;
            after[i] = before[i] + 0.008;
            position[i] = 0.25;
            landsat[i] = before[i] + 0.25 * 0.008 - 0.03;
        }

        assertEquals(-0.03, PhotoOffset.of(landsat, before, after, position, 0.02, 20), 1e-9);
    }

    @Test
    void fieldsThatChangedBetweenTheTwoPhotosAreLeftOut() {
        int n = 40;
        double[] before = new double[n];
        double[] after = new double[n];
        double[] position = new double[n];
        double[] landsat = new double[n];
        for (int i = 0; i < n; i++) {
            boolean cut = i >= 22;
            before[i] = 0.20;
            after[i] = cut ? 0.28 : 0.20;
            position[i] = 0.5;
            // A cut field's Landsat reading may sit anywhere between the two; here already at the cut level.
            landsat[i] = (cut ? 0.28 : 0.20) + 0.01;
        }

        assertEquals(0.01, PhotoOffset.of(landsat, before, after, position, 0.02, 20), 1e-9);
    }

    @Test
    void tooFewUnchangedFieldsGiveNoOffset() {
        double[] same = {0.2, 0.2, 0.2};

        assertTrue(Double.isNaN(PhotoOffset.of(same, same, same, new double[] {0.5, 0.5, 0.5}, 0.02, 20)));
    }

    @Test
    void bracketsAreTheNearestDatesOutsideTheHiddenDays() {
        TreeMap<LocalDate, Double> series = new TreeMap<>();
        series.put(LocalDate.parse("2025-08-01"), 0.20);
        series.put(LocalDate.parse("2025-08-09"), 0.21);
        series.put(LocalDate.parse("2025-08-10"), 0.22);
        series.put(LocalDate.parse("2025-08-16"), 0.26);

        PhotoOffset.Bracket bracket = PhotoOffset.bracket(series, LocalDate.parse("2025-08-10"), 1, 20).orElseThrow();

        assertEquals(0.20, bracket.before(), 1e-12);
        assertEquals(0.26, bracket.after(), 1e-12);
        assertEquals(9 / 15.0, bracket.position(), 1e-12);
    }

    @Test
    void noBracketWhenASideIsMissingOrTooFar() {
        TreeMap<LocalDate, Double> series = new TreeMap<>();
        series.put(LocalDate.parse("2025-07-01"), 0.20);
        series.put(LocalDate.parse("2025-08-16"), 0.26);

        assertTrue(PhotoOffset.bracket(series, LocalDate.parse("2025-08-10"), 1, 20).isEmpty(), "before is 40 days away");
        assertTrue(PhotoOffset.bracket(series, LocalDate.parse("2025-08-20"), 1, 60).isEmpty(), "nothing after");
    }
}
