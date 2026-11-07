package kz.kazagrosat.core.fields;

import java.util.Arrays;

/**
 * Gathers each pixel's edge strength over a season, one date at a time, and keeps its rank-th highest
 * value. A plain maximum would let one bad date (a haze edge, a missed cloud shadow) draw a line; a
 * median would lose boundaries that show on only a few dates. Only {@code rank} values per pixel are
 * kept, never the whole season.
 */
public final class SeasonEdges {

    private final int pixels;
    private final int rank;
    /** Per pixel, its {@code rank} highest values so far, highest first; -infinity where not yet filled. */
    private final float[] top;

    public SeasonEdges(int pixels, int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("The rank must be at least 1, not " + rank);
        }
        this.pixels = pixels;
        this.rank = rank;
        this.top = new float[pixels * rank];
        Arrays.fill(top, Float.NEGATIVE_INFINITY);
    }

    /** Adds one date's edge strengths. NaN values are skipped. */
    public void add(float[] edges) {
        if (edges.length != pixels) {
            throw new IllegalArgumentException("Expected " + pixels + " edge values but got " + edges.length);
        }
        for (int p = 0; p < pixels; p++) {
            float value = edges[p];
            if (Float.isNaN(value)) {
                continue;
            }
            int base = p * rank;
            if (value <= top[base + rank - 1]) {
                continue;
            }
            int slot = rank - 1;
            while (slot > 0 && top[base + slot - 1] < value) {
                top[base + slot] = top[base + slot - 1];
                slot--;
            }
            top[base + slot] = value;
        }
    }

    /** @return per pixel its rank-th highest value, or NaN where fewer than {@code rank} dates had one */
    public float[] result() {
        float[] result = new float[pixels];
        for (int p = 0; p < pixels; p++) {
            float value = top[p * rank + rank - 1];
            result[p] = value == Float.NEGATIVE_INFINITY ? Float.NaN : value;
        }
        return result;
    }
}
