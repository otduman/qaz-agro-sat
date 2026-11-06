package kz.kazagrosat.core.index;

/** Normalised Difference Vegetation Index: (NIR - Red) / (NIR + Red). */
public final class Ndvi {

    private Ndvi() {}

    /**
     * Takes reflectance per pixel, with NaN for no data, and returns NDVI in [-1, 1], with NaN where
     * it is undefined. Negative reflectance, which the scene offset produces on very dark pixels,
     * counts as zero; left alone it would push NDVI out of range.
     */
    public static float[] compute(float[] red, float[] nir) {
        if (red.length != nir.length) {
            throw new IllegalArgumentException("Bands differ in size: " + red.length + " and " + nir.length);
        }
        float[] ndvi = new float[red.length];
        for (int i = 0; i < ndvi.length; i++) {
            float redLight = Math.max(red[i], 0f);
            float nirLight = Math.max(nir[i], 0f);
            float total = redLight + nirLight;
            ndvi[i] = total > 0f ? (nirLight - redLight) / total : Float.NaN;
        }
        return ndvi;
    }
}
