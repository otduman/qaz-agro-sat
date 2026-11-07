package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RegionMergerTest {

    private static final FieldParameters P = FieldParameters.DEFAULTS;
    private static final int HEIGHT = 10;
    private static final int DATES = 10;
    private static final int STRIP = 10;

    /** Side-by-side strips 10 pixels wide; strip k holds label k + 1. */
    private static final class Strips {
        final int width;
        final int[] labels;
        final float[] edges;
        final RegionSeries series;
        final int[] pixels;

        Strips(int count, float[] ridges, float[][] curves, boolean[][] clearOn) {
            width = count * STRIP;
            labels = new int[width * HEIGHT];
            edges = new float[width * HEIGHT];
            pixels = new int[count + 1];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < width; x++) {
                    int strip = x / STRIP;
                    labels[y * width + x] = strip + 1;
                    pixels[strip + 1]++;
                    if (strip < count - 1 && x % STRIP == STRIP - 1) {
                        edges[y * width + x] = ridges[strip];
                        edges[y * width + x + 1] = ridges[strip];
                    }
                }
            }
            series = new RegionSeries(count, DATES);
            for (int d = 0; d < DATES; d++) {
                float[] ndvi = new float[width * HEIGHT];
                boolean[] clear = new boolean[width * HEIGHT];
                for (int i = 0; i < ndvi.length; i++) {
                    int strip = (i % width) / STRIP;
                    ndvi[i] = curves[strip][d];
                    clear[i] = clearOn == null || clearOn[strip][d];
                }
                series.add(d, labels, ndvi, clear);
            }
        }

        int[] merge() {
            return RegionMerger.merge(labels, edges, width, HEIGHT, series, pixels, P);
        }
    }

    private static float[] curve(float value) {
        float[] curve = new float[DATES];
        Arrays.fill(curve, value);
        return curve;
    }

    private static float[] lowerOn(float value, float drop, int... dates) {
        float[] curve = curve(value);
        for (int d : dates) {
            curve[d] = value - drop;
        }
        return curve;
    }

    @Test
    void mergesAWeakRidgeWithTheSameCurve() {
        Strips strips = new Strips(2, new float[] {0.02f}, new float[][] {curve(0.6f), curve(0.6f)}, null);

        assertArrayEquals(new int[] {0, 1, 1}, strips.merge());
    }

    @Test
    void keepsAStrongRidgeApart() {
        Strips strips = new Strips(2, new float[] {0.3f}, new float[][] {curve(0.6f), curve(0.6f)}, null);

        assertArrayEquals(new int[] {0, 1, 2}, strips.merge());
    }

    @Test
    void keepsCurvesThatDifferOnThreeDatesApart() {
        Strips strips = new Strips(2, new float[] {0.02f},
                new float[][] {curve(0.6f), lowerOn(0.6f, 0.3f, 2, 5, 8)}, null);

        assertArrayEquals(new int[] {0, 1, 2}, strips.merge());
    }

    @Test
    void mergesCurvesThatDifferOnTwoDates() {
        Strips strips = new Strips(2, new float[] {0.02f},
                new float[][] {curve(0.6f), lowerOn(0.6f, 0.3f, 2, 5)}, null);

        assertArrayEquals(new int[] {0, 1, 1}, strips.merge());
    }

    @Test
    void needsEnoughCommonDates() {
        boolean[] always = new boolean[DATES];
        Arrays.fill(always, true);
        boolean[] fourDates = new boolean[DATES];
        for (int d = 0; d < 4; d++) {
            fourDates[d] = true;
        }
        Strips strips = new Strips(2, new float[] {0.02f}, new float[][] {curve(0.6f), curve(0.6f)},
                new boolean[][] {always, fourDates});

        assertArrayEquals(new int[] {0, 1, 2}, strips.merge());
    }

    @Test
    void chainsMergesThroughTheMergedCurve() {
        Strips strips = new Strips(3, new float[] {0.02f, 0.03f},
                new float[][] {curve(0.6f), curve(0.6f), curve(0.6f)}, null);

        assertArrayEquals(new int[] {0, 1, 1, 1}, strips.merge());
    }

    @Test
    void comparesWithTheMergedCurve() {
        // A and B (0.04 apart) merge first, on the weaker ridge. B and C are also 0.04 apart, but the
        // merged A+B curve sits at 0.52, 0.06 from C, so C stays apart.
        Strips strips = new Strips(3, new float[] {0.01f, 0.02f},
                new float[][] {curve(0.50f), curve(0.54f), curve(0.58f)}, null);

        assertArrayEquals(new int[] {0, 1, 1, 2}, strips.merge());
    }

    @Test
    void numbersMergedRegionsInOrderOfTheirSmallestOldLabel() {
        // Strips 2 and 3 merge; strip 1 stays apart.
        Strips strips = new Strips(3, new float[] {0.3f, 0.02f},
                new float[][] {curve(0.2f), curve(0.6f), curve(0.6f)}, null);

        assertArrayEquals(new int[] {0, 1, 2, 2}, strips.merge());
    }
}
