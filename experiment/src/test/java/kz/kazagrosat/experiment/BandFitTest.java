package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BandFitTest {

    @Test
    void recoversAnExactLine() {
        double[] landsat = {0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40};
        double[] sentinel = new double[landsat.length];
        for (int i = 0; i < landsat.length; i++) {
            sentinel[i] = 0.98 * landsat[i] + 0.02;
        }

        BandFit fit = BandFit.of(landsat, sentinel);

        assertEquals(0.98, fit.slope(), 1e-9);
        assertEquals(0.02, fit.intercept(), 1e-9);
        assertEquals(landsat.length, fit.pairs());
    }

    @Test
    void aFewWildPairsDoNotMoveTheLine() {
        int n = 50;
        double[] landsat = new double[n];
        double[] sentinel = new double[n];
        for (int i = 0; i < n; i++) {
            landsat[i] = 0.1 + 0.3 * i / n;
            sentinel[i] = landsat[i] + 0.02;
        }
        // A field cut between the two photos, or a cloud edge: 8 of 50 pairs far off.
        for (int i = 0; i < n; i += 6) {
            sentinel[i] += 0.15;
        }

        BandFit fit = BandFit.of(landsat, sentinel);

        assertEquals(1.0, fit.slope(), 0.02);
        assertEquals(0.02, fit.intercept(), 0.005);
    }

    @Test
    void applyPutsALandsatReadingOnTheSentinelScale() {
        BandFit fit = new BandFit(0.9, 0.03, 10);

        assertEquals(0.9 * 0.2 + 0.03, fit.apply(0.2), 1e-12);
        assertEquals(Double.NaN, fit.apply(Double.NaN));
    }

    @Test
    void refusesTooFewOrFlatPairs() {
        assertThrows(IllegalArgumentException.class, () -> BandFit.of(new double[] {0.1}, new double[] {0.1}));
        assertThrows(IllegalArgumentException.class,
                () -> BandFit.of(new double[] {0.2, 0.2, 0.2}, new double[] {0.1, 0.2, 0.3}));
        assertThrows(IllegalArgumentException.class,
                () -> BandFit.of(new double[] {0.1, 0.2}, new double[] {0.1}));
    }
}
