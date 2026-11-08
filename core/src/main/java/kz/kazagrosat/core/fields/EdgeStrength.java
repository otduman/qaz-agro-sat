package kz.kazagrosat.core.fields;

/**
 * How sharply each pixel differs from its neighbours on one date. Roads, shelterbelts and the change
 * from one crop to another show up as lines of high values.
 *
 * <p>The measure is the Sobel gradient divided by 4, so a sharp step of height h reads as h on the pixels
 * either side of it. It is taken on NDVI and on visible brightness, and the larger of the two counts:
 * {@code max(|grad NDVI|, weight * |grad brightness|)}.
 */
public final class EdgeStrength {

    private EdgeStrength() {}

    /**
     * @return per pixel the edge strength, or NaN where the pixel or any of its 8 neighbours is not clear
     *         or has no value. At the image border the edge pixel stands in for missing neighbours.
     */
    public static float[] of(float[] ndvi, float[] brightness, boolean[] clear, int width, int height,
                             double brightnessWeight) {
        int pixels = width * height;
        if (ndvi.length != pixels || brightness.length != pixels || clear.length != pixels) {
            throw new IllegalArgumentException("Expected " + pixels + " values for " + width + " x " + height
                    + " but got " + ndvi.length + ", " + brightness.length + " and " + clear.length);
        }
        boolean[] usable = new boolean[pixels];
        for (int i = 0; i < pixels; i++) {
            usable[i] = clear[i] && !Float.isNaN(ndvi[i]) && !Float.isNaN(brightness[i]);
        }
        float[] edges = new float[pixels];
        for (int y = 0; y < height; y++) {
            int up = Math.max(0, y - 1);
            int down = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - 1);
                int right = Math.min(width - 1, x + 1);
                if (!allUsable(usable, width, left, right, up, down)) {
                    edges[y * width + x] = Float.NaN;
                    continue;
                }
                double ndviEdge = sobel(ndvi, width, x, y, left, right, up, down);
                double brightEdge = sobel(brightness, width, x, y, left, right, up, down);
                edges[y * width + x] = (float) Math.max(ndviEdge, brightnessWeight * brightEdge);
            }
        }
        return edges;
    }

    private static boolean allUsable(boolean[] usable, int width, int left, int right, int up, int down) {
        for (int y = up; y <= down; y++) {
            for (int x = left; x <= right; x++) {
                if (!usable[y * width + x]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double sobel(float[] v, int width, int x, int y, int left, int right, int up, int down) {
        double gx = (v[up * width + right] + 2.0 * v[y * width + right] + v[down * width + right])
                - (v[up * width + left] + 2.0 * v[y * width + left] + v[down * width + left]);
        double gy = (v[down * width + left] + 2.0 * v[down * width + x] + v[down * width + right])
                - (v[up * width + left] + 2.0 * v[up * width + x] + v[up * width + right]);
        return Math.sqrt(gx * gx + gy * gy) / 4.0;
    }
}
