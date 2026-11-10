package kz.kazagrosat.pipeline.landsat;

import java.util.Arrays;
import java.util.function.IntPredicate;

/** One satellite's photos of one day, joined: a pixel takes the first clear value any scene gives it. */
public final class LandsatDay {

    private final boolean[] clear;
    private final float[][] bands;
    private int scenes;

    public LandsatDay(int pixels, int bandCount) {
        clear = new boolean[pixels];
        bands = new float[bandCount][pixels];
        for (float[] band : bands) {
            Arrays.fill(band, Float.NaN);
        }
    }

    /** @param qa the scene window's {@code qa_pixel}; {@code values} its bands, in this day's band order */
    public void add(int[] lookup, int[] qa, float[][] values) {
        add(lookup, qa, values, LandsatQa::clear);
    }

    /** As {@link #add(int[], int[], float[][])}, with {@code isClear} reading the quality flags. */
    public void add(int[] lookup, int[] qa, float[][] values, IntPredicate isClear) {
        scenes++;
        for (int i = 0; i < clear.length; i++) {
            int k = lookup[i];
            if (clear[i] || k < 0 || !isClear.test(qa[k])) {
                continue;
            }
            boolean complete = true;
            for (float[] value : values) {
                complete &= !Float.isNaN(value[k]);
            }
            if (!complete) {
                continue;
            }
            clear[i] = true;
            for (int b = 0; b < bands.length; b++) {
                bands[b][i] = values[b][k];
            }
        }
    }

    public boolean[] clear() {
        return clear;
    }

    public float[] band(int b) {
        return bands[b];
    }

    public int scenes() {
        return scenes;
    }
}
