package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LineStrengthTest {

    private static final int W = 120;
    private static final int H = 100;
    private static final int RADIUS = 15;

    private static float[] flat(float value) {
        float[] edges = new float[W * H];
        Arrays.fill(edges, value);
        return edges;
    }

    @Test
    void flatEdgesGiveNoLines() {
        float[] lines = LineStrength.of(flat(0.05f), W, H, RADIUS);

        for (float value : lines) {
            assertEquals(0.0, value, 1e-6);
        }
    }

    @Test
    void aFaintStraightLineStandsOut() {
        float[] horizontal = flat(0.05f);
        float[] vertical = flat(0.05f);
        float[] diagonal = flat(0.05f);
        for (int x = 0; x < W; x++) {
            horizontal[50 * W + x] = 0.10f;
        }
        for (int y = 0; y < H; y++) {
            vertical[y * W + 60] = 0.10f;
            diagonal[y * W + y + 10] = 0.10f;
        }

        float[] h = LineStrength.of(horizontal, W, H, RADIUS);
        float[] v = LineStrength.of(vertical, W, H, RADIUS);
        float[] d = LineStrength.of(diagonal, W, H, RADIUS);

        assertTrue(h[50 * W + 60] >= 0.045, "on the horizontal line: " + h[50 * W + 60]);
        assertTrue(v[50 * W + 60] >= 0.045, "on the vertical line: " + v[50 * W + 60]);
        assertTrue(d[50 * W + 60] >= 0.045, "on the diagonal line: " + d[50 * W + 60]);
        assertTrue(h[40 * W + 60] < 0.005, "10 pixels off the horizontal line: " + h[40 * W + 60]);
        assertTrue(v[50 * W + 70] < 0.005, "10 pixels off the vertical line: " + v[50 * W + 70]);
        assertTrue(d[50 * W + 70] < 0.005, "10 pixels off the diagonal line: " + d[50 * W + 70]);
    }

    @Test
    void aBlobIsNotALine() {
        float[] edges = flat(0.05f);
        for (int y = 47; y < 54; y++) {
            for (int x = 57; x < 64; x++) {
                edges[y * W + x] = 0.4f;
            }
        }

        float[] lines = LineStrength.of(edges, W, H, RADIUS);

        assertTrue(lines[50 * W + 60] < 0.01, "at the blob's centre: " + lines[50 * W + 60]);
    }

    @Test
    void noValueStaysNoValue() {
        float[] edges = flat(0.05f);
        edges[50 * W + 60] = Float.NaN;

        float[] lines = LineStrength.of(edges, W, H, RADIUS);

        assertTrue(Float.isNaN(lines[50 * W + 60]));
        assertFalse(Float.isNaN(lines[50 * W + 61]));
        assertEquals(0.0, lines[50 * W + 61], 1e-6);
    }

    @Test
    void refusesWrongSizes() {
        assertThrows(IllegalArgumentException.class, () -> LineStrength.of(new float[10], W, H, RADIUS));
        assertThrows(IllegalArgumentException.class, () -> LineStrength.of(flat(0.05f), W, H, 0));
    }
}
