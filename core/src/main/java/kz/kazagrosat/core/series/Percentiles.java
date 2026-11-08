package kz.kazagrosat.core.series;

import java.util.Arrays;

/** Percentiles of the values at a set of pixels. */
public final class Percentiles {

    private Percentiles() {}

    /**
     * @param pixels the pixels to read
     * @param use per pixel whether to use it, or null to use every listed pixel
     * @param share the percentile as a share from 0 to 1, so 0.5 is the median
     * @return the percentile by linear interpolation between the sorted used values that aren't NaN, or
     *     NaN when there are none
     */
    public static double of(float[] values, int[] pixels, boolean[] use, double share) {
        if (!(share >= 0 && share <= 1)) {
            throw new IllegalArgumentException("The share must be from 0 to 1 but was " + share);
        }
        float[] kept = new float[pixels.length];
        int n = 0;
        for (int i : pixels) {
            if ((use == null || use[i]) && !Float.isNaN(values[i])) {
                kept[n++] = values[i];
            }
        }
        if (n == 0) {
            return Double.NaN;
        }
        Arrays.sort(kept, 0, n);
        double position = share * (n - 1);
        int below = (int) Math.floor(position);
        int above = Math.min(n - 1, below + 1);
        double fraction = position - below;
        return kept[below] + fraction * ((double) kept[above] - kept[below]);
    }
}
