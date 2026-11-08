package kz.kazagrosat.core.mask;

/** Cloud and snow masking from the Sentinel-2 Scene Classification Layer. */
public final class SclMask {

    private static final int VEGETATION = 4;
    private static final int BARE_SOIL = 5;
    private static final int WATER = 6;
    /** The class the mask gives snow and ice. */
    public static final int SNOW = 11;

    private SclMask() {}

    /** Marks the pixels showing clear ground or water. */
    public static boolean[] usable(int[] sclClasses) {
        boolean[] usable = new boolean[sclClasses.length];
        for (int i = 0; i < usable.length; i++) {
            int sclClass = sclClasses[i];
            usable[i] = sclClass == VEGETATION || sclClass == BARE_SOIL || sclClass == WATER;
        }
        return usable;
    }

    /** Nearest-neighbour resampling: each pixel becomes a factor x factor block. Classes must not blend. */
    public static int[] upsample(int[] classes, int width, int height, int factor) {
        int outWidth = width * factor;
        int[] upsampled = new int[outWidth * height * factor];
        for (int y = 0; y < height * factor; y++) {
            for (int x = 0; x < outWidth; x++) {
                upsampled[y * outWidth + x] = classes[(y / factor) * width + x / factor];
            }
        }
        return upsampled;
    }

    /** Returns a copy with NaN wherever the mask says the pixel is not usable. */
    public static float[] apply(float[] values, boolean[] usable) {
        if (values.length != usable.length) {
            throw new IllegalArgumentException(
                    "Mask size " + usable.length + " differs from data size " + values.length);
        }
        float[] masked = new float[values.length];
        for (int i = 0; i < masked.length; i++) {
            masked[i] = usable[i] ? values[i] : Float.NaN;
        }
        return masked;
    }
}
