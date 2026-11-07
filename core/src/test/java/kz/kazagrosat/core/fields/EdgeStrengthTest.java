package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EdgeStrengthTest {

    private static final int WIDTH = 10;
    private static final int HEIGHT = 6;
    private static final double WEIGHT = 5.0;

    private static float[] filled(float value) {
        float[] values = new float[WIDTH * HEIGHT];
        Arrays.fill(values, value);
        return values;
    }

    private static float[] stepAtColumn5(float left, float right) {
        float[] values = new float[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = x < 5 ? left : right;
            }
        }
        return values;
    }

    private static boolean[] allClear() {
        boolean[] clear = new boolean[WIDTH * HEIGHT];
        Arrays.fill(clear, true);
        return clear;
    }

    @Test
    void aStepInNdviGivesItsHeightOnBothSides() {
        float[] edges = EdgeStrength.of(stepAtColumn5(0.2f, 0.6f), filled(0.1f), allClear(), WIDTH, HEIGHT, WEIGHT);

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double expected = x == 4 || x == 5 ? 0.4 : 0.0;
                assertEquals(expected, edges[y * WIDTH + x], 1e-6, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void brightnessCountsWithItsWeight() {
        float[] edges = EdgeStrength.of(filled(0.5f), stepAtColumn5(0.10f, 0.14f), allClear(), WIDTH, HEIGHT, WEIGHT);

        assertEquals(0.2, edges[2 * WIDTH + 4], 1e-6);
        assertEquals(0.2, edges[2 * WIDTH + 5], 1e-6);
        assertEquals(0.0, edges[2 * WIDTH + 2], 1e-6);
    }

    @Test
    void takesTheLargerOfTheTwo() {
        float[] edges = EdgeStrength.of(stepAtColumn5(0.5f, 0.6f), stepAtColumn5(0.10f, 0.14f), allClear(),
                WIDTH, HEIGHT, WEIGHT);

        assertEquals(0.2, edges[3 * WIDTH + 4], 1e-6);
    }

    @Test
    void isMissingNextToAnUnclearPixel() {
        boolean[] clear = allClear();
        clear[2 * WIDTH + 2] = false;

        float[] edges = EdgeStrength.of(filled(0.5f), filled(0.1f), clear, WIDTH, HEIGHT, WEIGHT);

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                boolean nearUnclear = Math.abs(x - 2) <= 1 && Math.abs(y - 2) <= 1;
                assertEquals(nearUnclear, Float.isNaN(edges[y * WIDTH + x]), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void isMissingWhereAValueIsMissing() {
        float[] ndvi = filled(0.5f);
        ndvi[3 * WIDTH + 7] = Float.NaN;

        float[] edges = EdgeStrength.of(ndvi, filled(0.1f), allClear(), WIDTH, HEIGHT, WEIGHT);

        assertTrue(Float.isNaN(edges[3 * WIDTH + 7]));
        assertTrue(Float.isNaN(edges[2 * WIDTH + 6]));
        assertFalse(Float.isNaN(edges[3 * WIDTH + 5]));
    }

    @Test
    void aFlatImageIsZeroUpToTheBorder() {
        float[] edges = EdgeStrength.of(filled(0.5f), filled(0.1f), allClear(), WIDTH, HEIGHT, WEIGHT);

        for (float edge : edges) {
            assertEquals(0.0, edge, 0.0);
        }
    }

    @Test
    void aStepAtTheBorderIsMeasuredWithTheEdgePixelStandingIn() {
        float[] ndvi = filled(0.5f);
        for (int y = 0; y < HEIGHT; y++) {
            ndvi[y * WIDTH] = 0.1f;
        }

        float[] edges = EdgeStrength.of(ndvi, filled(0.1f), allClear(), WIDTH, HEIGHT, WEIGHT);

        // Column 0 sees itself (0.1) on its left and 0.5 on its right: (0.5 - 0.1) * 4 / 4.
        assertEquals(0.4, edges[2 * WIDTH], 1e-6);
        assertEquals(0.4, edges[2 * WIDTH + 1], 1e-6);
        assertEquals(0.0, edges[2 * WIDTH + 2], 1e-6);
    }

    @Test
    void rejectsArraysOfTheWrongSize() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeStrength.of(new float[5], filled(0.1f), allClear(), WIDTH, HEIGHT, WEIGHT));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeStrength.of(filled(0.5f), filled(0.1f), new boolean[5], WIDTH, HEIGHT, WEIGHT));
    }
}
