package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class WatershedTest {

    private static final FieldParameters P = FieldParameters.DEFAULTS;

    private static float[] filled(int width, int height, float value) {
        float[] edges = new float[width * height];
        Arrays.fill(edges, value);
        return edges;
    }

    private static void fill(float[] edges, int width, int x0, int y0, int x1, int y1, float value) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                edges[y * width + x] = value;
            }
        }
    }

    private static Set<Integer> labelsIn(int[] labels) {
        Set<Integer> set = new TreeSet<>();
        for (int label : labels) {
            set.add(label);
        }
        return set;
    }

    @Test
    void twoCalmAreasSplitAlongTheRidge() {
        int width = 40;
        int height = 20;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 20, 0, 20, height - 1, 1.0f);

        int[] labels = Watershed.flood(edges, width, height, P);

        int left = labels[0];
        int right = labels[width - 1];
        assertNotEquals(left, right);
        assertEquals(Set.of(left, right), labelsIn(labels));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labels[y * width + x];
                if (x < 20) {
                    assertEquals(left, label, "pixel " + x + "," + y);
                } else if (x > 20) {
                    assertEquals(right, label, "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void aGapInTheRidgeStillGivesTwoRegions() {
        int width = 40;
        int height = 20;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 20, 0, 20, height - 1, 1.0f);
        fill(edges, width, 20, 8, 20, 11, 0f);

        int[] labels = Watershed.flood(edges, width, height, P);

        int left = labels[0];
        int right = labels[width - 1];
        assertNotEquals(left, right);
        assertEquals(Set.of(left, right), labelsIn(labels));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x <= 15) {
                    assertEquals(left, labels[y * width + x], "pixel " + x + "," + y);
                } else if (x >= 25) {
                    assertEquals(right, labels[y * width + x], "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void aSmallCalmSpeckSeedsNothing() {
        int width = 40;
        int height = 20;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 20, 0, width - 1, height - 1, 0.2f);
        fill(edges, width, 30, 9, 32, 11, 0f);

        int[] labels = Watershed.flood(edges, width, height, P);

        assertEquals(Set.of(1), labelsIn(labels));
    }

    @Test
    void aCalmAreaTooSmallAfterShrinkingSeedsNothing() {
        int width = 60;
        int height = 30;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 30, 0, width - 1, height - 1, 0.2f);
        // 11 x 11 shrinks to 5 x 5 = 25 pixels, under the 50-pixel minimum.
        fill(edges, width, 40, 10, 50, 20, 0f);

        assertEquals(Set.of(1), labelsIn(Watershed.flood(edges, width, height, P)));
    }

    @Test
    void aCalmAreaLargeEnoughAfterShrinkingSeedsARegion() {
        int width = 60;
        int height = 30;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 30, 0, width - 1, height - 1, 0.2f);
        // 15 x 15 shrinks to 9 x 9 = 81 pixels.
        fill(edges, width, 38, 8, 52, 22, 0f);

        int[] labels = Watershed.flood(edges, width, height, P);

        assertEquals(Set.of(1, 2), labelsIn(labels));
        assertEquals(2, labels[15 * width + 45]);
    }

    @Test
    void missingPixelsGetNoRegion() {
        int width = 40;
        int height = 20;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 5, 5, 8, 8, Float.NaN);

        int[] labels = Watershed.flood(edges, width, height, P);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean missing = x >= 5 && x <= 8 && y >= 5 && y <= 8;
                assertEquals(missing, labels[y * width + x] == 0, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void theFloodDoesNotCrossMissingPixels() {
        int width = 40;
        int height = 20;
        float[] edges = filled(width, height, 0f);
        fill(edges, width, 20, 0, 20, height - 1, Float.NaN);
        fill(edges, width, 21, 0, width - 1, height - 1, 0.2f);

        int[] labels = Watershed.flood(edges, width, height, P);

        for (int y = 0; y < height; y++) {
            for (int x = 20; x < width; x++) {
                assertEquals(0, labels[y * width + x], "pixel " + x + "," + y);
            }
        }
        assertEquals(1, labels[0]);
    }

    @Test
    void labelsFollowScanOrder() {
        int width = 40;
        int height = 40;
        float[] edges = filled(width, height, 0.2f);
        fill(edges, width, 0, 25, 14, 39, 0f);
        fill(edges, width, 25, 0, 39, 14, 0f);

        int[] labels = Watershed.flood(edges, width, height, P);

        assertEquals(1, labels[5 * width + 32], "the top-right area starts first in row order");
        assertEquals(2, labels[32 * width + 5]);
        assertTrue(Arrays.stream(labels).allMatch(label -> label == 1 || label == 2));
    }

    /**
     * The right field's middle is calm but busier than the left's, and a calm band runs along its side
     * of the ridge, too close to the ridge to be part of any marker. The left flood can reach that band
     * only through a gap in the ridge. Every marker floods from the start, so the band, which touches the
     * right marker, is the right field's before the left flood gets through the gap.
     */
    @Test
    void aHigherMarkerKeepsTheBandBesideIt() {
        int width = 60;
        int height = 20;
        int ridge = 30;
        float busyButCalm = (float) (P.markerEdge() - 0.01);
        float[] edges = filled(width, height, 0f);
        fill(edges, width, ridge + 1, 0, width - 1, height - 1, busyButCalm);
        fill(edges, width, ridge + 1, 0, ridge + P.markerShrink(), height - 1, 0.02f);
        fill(edges, width, ridge, 0, ridge, height - 1, 1.0f);
        fill(edges, width, ridge, 8, ridge, 11, 0.10f);

        int[] labels = Watershed.flood(edges, width, height, P);

        int right = labels[width - 1];
        assertNotEquals(labels[0], right);
        for (int y = 0; y < height; y++) {
            for (int x = ridge + 1; x <= ridge + P.markerShrink(); x++) {
                assertEquals(right, labels[y * width + x], "band pixel " + x + "," + y);
            }
        }
    }
}
