package kz.kazagrosat.core.raster;

/** Turns stored digital numbers into surface reflectance. */
public final class Reflectance {

    private Reflectance() {}

    /**
     * @param scale  from the scene's metadata; never assume it
     * @param offset from the scene's metadata; it differs between processing baselines
     * @param nodata the digital number meaning "no data", or null if there is none
     * @return reflectance per pixel, with NaN for no data
     */
    public static float[] fromDigitalNumbers(int[] digitalNumbers, double scale, double offset, Integer nodata) {
        float[] reflectance = new float[digitalNumbers.length];
        for (int i = 0; i < reflectance.length; i++) {
            boolean missing = nodata != null && digitalNumbers[i] == nodata;
            reflectance[i] = missing ? Float.NaN : (float) (digitalNumbers[i] * scale + offset);
        }
        return reflectance;
    }
}
