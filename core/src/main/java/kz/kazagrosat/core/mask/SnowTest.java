package kz.kazagrosat.core.mask;

/**
 * Finds snow that the satellite's own cloud mask may count as clear. A pixel is snow when its snow
 * index (green - SWIR16) / (green + SWIR16) is at least 0.4 and its near-infrared reflectance is at least
 * 0.11, the long-standing MODIS and Sentinel-2 snow-mapping rule. Water also has a high snow index; the
 * near-infrared condition keeps it out. A missing value anywhere means not snow.
 */
public final class SnowTest {

    /** The lowest snow index that counts as snow. */
    public static final double MIN_INDEX = 0.4;
    /** The lowest near-infrared reflectance that counts as snow. */
    public static final float MIN_NIR = 0.11f;

    private SnowTest() {}

    /** @return per pixel whether it is snow; all three arrays are reflectance on the same grid */
    public static boolean[] snow(float[] green, float[] nir, float[] swir16) {
        if (green.length != nir.length || green.length != swir16.length) {
            throw new IllegalArgumentException("Expected arrays of one length but got "
                    + green.length + ", " + nir.length + " and " + swir16.length);
        }
        boolean[] snow = new boolean[green.length];
        for (int i = 0; i < green.length; i++) {
            double sum = (double) green[i] + swir16[i];
            // NaN fails every comparison, so a missing value is never snow
            snow[i] = sum > 0 && (green[i] - (double) swir16[i]) / sum >= MIN_INDEX && nir[i] >= MIN_NIR;
        }
        return snow;
    }
}
