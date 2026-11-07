package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxCrossingTest {

    private static final int W = 20;
    private static final int H = 10;

    /** Region 1 for x < 10, region 2 for the rest. */
    private static int[] halves() {
        int[] labels = new int[W * H];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i % W < 10 ? 1 : 2;
        }
        return labels;
    }

    @Test
    void aBoxInsideOneRegionIsNotCrossed() {
        Field box = new Field("B1", "crop", 1, 1, 9, 9);

        assertEquals(1.0, BoxCrossing.largestShare(halves(), W, box), 1e-12);
        assertFalse(BoxCrossing.crossed(halves(), W, box));
    }

    @Test
    void aBoxSplitInHalfIsCrossed() {
        Field box = new Field("B2", "crop", 6, 0, 14, 10);

        assertEquals(0.5, BoxCrossing.largestShare(halves(), W, box), 1e-12);
        assertTrue(BoxCrossing.crossed(halves(), W, box));
    }

    @Test
    void aSliverOfANeighbourDoesNotCountAsCrossed() {
        // 9 of 10 columns in region 1: a share of 0.9 is not under 0.9.
        Field box = new Field("B3", "crop", 1, 0, 11, 10);

        assertEquals(0.9, BoxCrossing.largestShare(halves(), W, box), 1e-12);
        assertFalse(BoxCrossing.crossed(halves(), W, box));
    }

    @Test
    void leftOutPixelsCountAsTheirOwnRegion() {
        int[] labels = halves();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < 5; x++) {
                labels[y * W + x] = 0;
            }
        }
        Field box = new Field("B4", "crop", 0, 0, 10, 10);

        assertEquals(0.5, BoxCrossing.largestShare(labels, W, box), 1e-12);
    }
}
