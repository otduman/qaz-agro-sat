package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BoundaryStabilityTest {

    private static final int W = 30;
    private static final int H = 10;

    /** Region 1 for x < split, region 2 for the rest: boundary pixels sit in column split - 1. */
    private static int[] splitAt(int split) {
        int[] labels = new int[W * H];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i % W < split ? 1 : 2;
        }
        return labels;
    }

    @Test
    void identicalMapsShareEveryBoundary() {
        assertEquals(1.0, BoundaryStability.share(splitAt(15), splitAt(15), W, H, 2), 1e-12);
    }

    @Test
    void aBoundaryTwoPixelsAwayStillCounts() {
        assertEquals(1.0, BoundaryStability.share(splitAt(15), splitAt(17), W, H, 2), 1e-12);
    }

    @Test
    void threePixelsAwayDoesNot() {
        assertEquals(0.0, BoundaryStability.share(splitAt(15), splitAt(18), W, H, 2), 1e-12);
    }

    @Test
    void chanceIsTheShareOfPixelsNearABoundary() {
        // Boundary pixels in column 14; within 2 pixels are columns 12 to 16: 5 of 30 columns.
        assertEquals(5.0 / 30, BoundaryStability.chance(splitAt(15), W, H, 2), 1e-12);
    }

    @Test
    void aMapWithNoBoundaryHasNothingToShare() {
        int[] one = new int[W * H];
        java.util.Arrays.fill(one, 1);

        assertEquals(Double.NaN, BoundaryStability.share(one, splitAt(15), W, H, 2));
    }
}
