package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CleanupTest {

    private static final int W = 10;
    private static final int H = 10;

    private static int[] filled(int label) {
        int[] labels = new int[W * H];
        Arrays.fill(labels, label);
        return labels;
    }

    private static void set(int[] labels, int x0, int y0, int x1, int y1, int label) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                labels[y * W + x] = label;
            }
        }
    }

    @Test
    void dropsRegionsUnderTheMinimum() {
        int[] labels = filled(1);
        set(labels, 0, 0, 4, 1, 2);

        int[] result = Cleanup.dropSmall(labels, 2, 20);

        int[] expected = filled(1);
        set(expected, 0, 0, 4, 1, 0);
        assertArrayEquals(expected, result);
    }

    @Test
    void fillsAHoleUnderTheMaximum() {
        int[] labels = filled(1);
        set(labels, 4, 4, 5, 5, 0);

        Cleanup.Holes holes = Cleanup.fillHoles(labels, W, H, 5);

        assertArrayEquals(filled(1), holes.labels());
        assertEquals(1, holes.filled());
        assertEquals(4, holes.filledPixels());
        assertEquals(0, holes.kept());
    }

    @Test
    void keepsALargerHole() {
        int[] labels = filled(1);
        set(labels, 3, 3, 5, 5, 0);

        Cleanup.Holes holes = Cleanup.fillHoles(labels, W, H, 5);

        assertArrayEquals(labels, holes.labels());
        assertEquals(0, holes.filled());
        assertEquals(1, holes.kept());
    }

    @Test
    void doesNotFillAGapTouchingTheBorder() {
        int[] labels = filled(1);
        set(labels, 0, 0, 1, 1, 0);

        Cleanup.Holes holes = Cleanup.fillHoles(labels, W, H, 5);

        assertArrayEquals(labels, holes.labels());
        assertEquals(0, holes.filled());
        assertEquals(0, holes.kept());
    }

    @Test
    void doesNotFillAGapBetweenTwoRegions() {
        int[] labels = filled(1);
        set(labels, 5, 0, 9, 9, 2);
        set(labels, 4, 4, 5, 5, 0);

        Cleanup.Holes holes = Cleanup.fillHoles(labels, W, H, 5);

        assertArrayEquals(labels, holes.labels());
        assertEquals(0, holes.filled());
        assertEquals(0, holes.kept());
    }

    @Test
    void doesNotChangeItsInput() {
        int[] labels = filled(1);
        set(labels, 4, 4, 5, 5, 0);
        int[] copy = labels.clone();

        Cleanup.fillHoles(labels, W, H, 5);
        Cleanup.dropSmall(labels, 1, 1000);

        assertArrayEquals(copy, labels);
    }
}
