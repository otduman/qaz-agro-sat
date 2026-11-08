package kz.kazagrosat.core.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PercentilesTest {

    private static final float[] VALUES = {5, 3, 1, 4, 2};
    private static final int[] ALL = {0, 1, 2, 3, 4};

    @Test
    void percentilesInterpolate() {
        assertEquals(2.0, Percentiles.of(VALUES, ALL, null, 0.25), 1e-12);
        assertEquals(3.0, Percentiles.of(VALUES, ALL, null, 0.5), 1e-12);
        assertEquals(4.0, Percentiles.of(VALUES, ALL, null, 0.75), 1e-12);
        assertEquals(1.0, Percentiles.of(VALUES, ALL, null, 0.0), 1e-12);
        assertEquals(5.0, Percentiles.of(VALUES, ALL, null, 1.0), 1e-12);
        assertEquals(1.4, Percentiles.of(VALUES, ALL, null, 0.1), 1e-6, "between 1 and 2");
    }

    @Test
    void anEvenCountTakesTheMiddleTwo() {
        assertEquals(2.5, Percentiles.of(new float[] {1, 2, 3, 4}, new int[] {0, 1, 2, 3}, null, 0.5), 1e-12);
    }

    @Test
    void unusedAndMissingValuesAreSkipped() {
        float[] values = {5, Float.NaN, 1, 100, 2, 3};
        boolean[] use = {true, true, true, false, true, true};

        assertEquals(2.5, Percentiles.of(values, new int[] {0, 1, 2, 3, 4, 5}, use, 0.5), 1e-12,
                "the median of 5, 1, 2, 3");
        assertEquals(1.0, Percentiles.of(values, new int[] {2}, use, 0.5), 1e-12, "only the listed pixels");
    }

    @Test
    void noValuesGiveNaN() {
        assertTrue(Double.isNaN(Percentiles.of(VALUES, new int[0], null, 0.5)));
        assertTrue(Double.isNaN(Percentiles.of(new float[] {Float.NaN}, new int[] {0}, null, 0.5)));
        assertTrue(Double.isNaN(Percentiles.of(VALUES, ALL, new boolean[5], 0.5)));
    }

    @Test
    void refusesAShareOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> Percentiles.of(VALUES, ALL, null, 1.5));
        assertThrows(IllegalArgumentException.class, () -> Percentiles.of(VALUES, ALL, null, -0.1));
    }

    @Test
    void severalSharesAtOnceMatchOneAtATime() {
        double[] all = Percentiles.of(VALUES, ALL, null, new double[] {0.25, 0.5, 0.75});

        assertEquals(3, all.length);
        assertEquals(2.0, all[0], 1e-12);
        assertEquals(3.0, all[1], 1e-12);
        assertEquals(4.0, all[2], 1e-12);
        assertTrue(Double.isNaN(Percentiles.of(VALUES, new int[0], null, new double[] {0.5})[0]));
    }
}
