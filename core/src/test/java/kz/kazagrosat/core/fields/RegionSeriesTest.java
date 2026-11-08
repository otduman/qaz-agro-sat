package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RegionSeriesTest {

    private static final FieldParameters P = FieldParameters.DEFAULTS;

    private static boolean[] clear(int n) {
        boolean[] clear = new boolean[n];
        Arrays.fill(clear, true);
        return clear;
    }

    @Test
    void meanAndSpreadPerRegionAndDate() {
        RegionSeries series = new RegionSeries(2, 1);
        series.add(0, new int[] {1, 1, 1, 2}, new float[] {0.2f, 0.4f, 0.6f, 0.9f}, clear(4));

        assertEquals(0.4, series.mean(1, 0, 3, 0.6), 1e-6);
        assertEquals(0.1633, series.spread(1, 0, 3, 0.6), 1e-4);
        assertEquals(0.9, series.mean(2, 0, 1, 0.6), 1e-6);
        assertEquals(0.0, series.spread(2, 0, 1, 0.6), 1e-6);
    }

    @Test
    void unclearAndUnlabelledPixelsDoNotCount() {
        RegionSeries series = new RegionSeries(1, 1);
        series.add(0, new int[] {1, 1, 1, 0}, new float[] {0.2f, 0.4f, 0.9f, 0.7f},
                new boolean[] {true, true, false, true});

        assertEquals(0.3, series.mean(1, 0, 3, 0.6), 1e-6);
        assertEquals(2, series.clearCount(1, 0));
    }

    @Test
    void missingValuesDoNotCount() {
        RegionSeries series = new RegionSeries(1, 1);
        series.add(0, new int[] {1, 1}, new float[] {0.2f, Float.NaN}, clear(2));

        assertEquals(1, series.clearCount(1, 0));
    }

    @Test
    void noValueUnderTheClearShare() {
        RegionSeries series = new RegionSeries(1, 1);
        series.add(0, new int[] {1, 1, 1, 1}, new float[] {0.2f, 0.4f, 0.6f, 0.8f},
                new boolean[] {true, true, false, false});

        assertTrue(Double.isNaN(series.mean(1, 0, 4, 0.6)));
        assertTrue(Double.isNaN(series.spread(1, 0, 4, 0.6)));
        assertEquals(0.3, series.mean(1, 0, 4, 0.5), 1e-6);
    }

    @Test
    void combiningAddsThePixelSums() {
        RegionSeries series = new RegionSeries(3, 1);
        series.add(0, new int[] {1, 1, 2, 2, 3}, new float[] {0.2f, 0.4f, 0.6f, 0.8f, 0.1f}, clear(5));

        RegionSeries combined = series.combine(new int[] {0, 1, 1, 0}, 1);

        assertEquals(0.5, combined.mean(1, 0, 4, 0.6), 1e-6);
        assertEquals(0.2236, combined.spread(1, 0, 4, 0.6), 1e-4);
        assertEquals(4, combined.clearCount(1, 0));
    }

    @Test
    void uniformityIsOneForAFlatRegion() {
        RegionSeries series = new RegionSeries(1, 4);
        for (int d = 0; d < 4; d++) {
            series.add(d, new int[] {1, 1, 1, 1}, new float[] {0.5f, 0.5f, 0.5f, 0.5f}, clear(4));
        }

        assertEquals(1.0, series.uniformity(1, 4, P), 1e-6);
    }

    @Test
    void uniformityIsZeroForHalvesHalfAnNdviApart() {
        RegionSeries series = new RegionSeries(1, 4);
        for (int d = 0; d < 4; d++) {
            series.add(d, new int[] {1, 1, 1, 1}, new float[] {0.3f, 0.3f, 0.8f, 0.8f}, clear(4));
        }

        assertEquals(0.0, series.uniformity(1, 4, P), 1e-6);
    }

    @Test
    void uniformityUsesTheThirdHighestSpread() {
        RegionSeries series = new RegionSeries(1, 5);
        float[][] dates = {
                {0.3f, 0.3f, 0.8f, 0.8f}, // spread 0.25
                {0.4f, 0.4f, 0.6f, 0.6f}, // spread 0.1
                {0.45f, 0.45f, 0.55f, 0.55f}, // spread 0.05
                {0.5f, 0.5f, 0.5f, 0.5f},
                {0.5f, 0.5f, 0.5f, 0.5f},
        };
        for (int d = 0; d < dates.length; d++) {
            series.add(d, new int[] {1, 1, 1, 1}, dates[d], clear(4));
        }

        assertEquals(1 - 0.05 / 0.25, series.uniformity(1, 4, P), 1e-5);
    }

    @Test
    void uniformityIsMissingWithTooFewDates() {
        RegionSeries series = new RegionSeries(1, 2);
        for (int d = 0; d < 2; d++) {
            series.add(d, new int[] {1, 1}, new float[] {0.5f, 0.5f}, clear(2));
        }

        assertTrue(Double.isNaN(series.uniformity(1, 2, P)));
    }

    @Test
    void rejectsArraysOfDifferentSizes() {
        RegionSeries series = new RegionSeries(1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> series.add(0, new int[] {1, 1}, new float[] {0.5f}, clear(2)));
    }
}
