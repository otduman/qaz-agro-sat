package kz.kazagrosat.core.series;

import java.util.Arrays;

/**
 * Texture of a field on one date, from 10 m brightness: how rough it looks and how strongly it shows
 * parallel lines, such as combine tracks and swath rows, and which way they run.
 *
 * <p>Only pixels whose whole 3 x 3 neighbourhood is usable count. Usable must mean clear and inside
 * the field's interior; interiors of different fields never come within 3 pixels of each other, so one
 * flag array can serve every field.
 * <ul>
 *   <li><b>Roughness</b> is the median of the 3 x 3 standard deviation of brightness, divided by the
 *       median brightness of the same counted pixels (not of the whole clear interior, which the series
 *       reports as brightness; the two differ slightly), so it doesn't change when the whole field gets
 *       brighter.</li>
 *   <li><b>Line strength</b> is the coherence of the summed gradient structure tensor, from 0 (no
 *       preferred direction) to 1 (every gradient parallel). Gradients are Sobel divided by 4, as in
 *       the field boundaries.</li>
 *   <li><b>Line direction</b> is the way the lines run, in degrees east of north from 0 up to 180, with
 *       north up the image. It is NaN when the strength is under {@link #MIN_STRENGTH}.</li>
 * </ul>
 * All three are NaN when fewer than {@link #MIN_PIXELS} pixels count.
 */
public final class Texture {

    /** The fewest pixels that give a texture. */
    public static final int MIN_PIXELS = 30;
    /** The weakest line strength that has a direction. */
    public static final double MIN_STRENGTH = 0.05;

    /** @param pixels how many pixels counted */
    public record Measures(double roughness, double lineStrength, double lineDirection, int pixels) {}

    private Texture() {}

    /**
     * @param brightness per pixel brightness on the image grid
     * @param usable per pixel whether it is clear and in the field's interior
     * @param pixels the field's interior pixels
     */
    public static Measures of(float[] brightness, boolean[] usable, int[] pixels, int width, int height) {
        int n = pixels.length;
        float[] deviations = new float[n];
        float[] values = new float[n];
        double xx = 0;
        double yy = 0;
        double xy = 0;
        int count = 0;
        for (int i : pixels) {
            int x = i % width;
            int y = i / width;
            if (!neighbourhoodUsable(usable, width, height, x, y)) {
                continue;
            }
            double sum = 0;
            double squares = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    double v = brightness[(y + dy) * width + x + dx];
                    sum += v;
                    squares += v * v;
                }
            }
            double mean = sum / 9;
            deviations[count] = (float) Math.sqrt(Math.max(0, squares / 9 - mean * mean));
            values[count] = brightness[i];
            count++;

            double gx = (b(brightness, width, x + 1, y - 1) + 2 * b(brightness, width, x + 1, y)
                    + b(brightness, width, x + 1, y + 1) - b(brightness, width, x - 1, y - 1)
                    - 2 * b(brightness, width, x - 1, y) - b(brightness, width, x - 1, y + 1)) / 4;
            // northwards, so up the image
            double gn = (b(brightness, width, x - 1, y - 1) + 2 * b(brightness, width, x, y - 1)
                    + b(brightness, width, x + 1, y - 1) - b(brightness, width, x - 1, y + 1)
                    - 2 * b(brightness, width, x, y + 1) - b(brightness, width, x + 1, y + 1)) / 4;
            xx += gx * gx;
            yy += gn * gn;
            xy += gx * gn;
        }
        if (count < MIN_PIXELS) {
            return new Measures(Double.NaN, Double.NaN, Double.NaN, count);
        }
        double medianBrightness = median(values, count);
        double roughness = medianBrightness > 0 ? median(deviations, count) / medianBrightness : Double.NaN;

        double trace = xx + yy;
        double strength = trace > 0 ? Math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy) / trace : 0;
        double direction = Double.NaN;
        if (strength >= MIN_STRENGTH) {
            // the main gradient's angle counter-clockwise from east; the lines run across it, which as
            // a bearing clockwise from north is the same angle with its sign turned
            double gradient = Math.toDegrees(0.5 * Math.atan2(2 * xy, xx - yy));
            direction = ((-gradient) % 180 + 180) % 180;
        }
        return new Measures(roughness, strength, direction, count);
    }

    private static double b(float[] brightness, int width, int x, int y) {
        return brightness[y * width + x];
    }

    private static boolean neighbourhoodUsable(boolean[] usable, int width, int height, int x, int y) {
        if (x < 1 || y < 1 || x >= width - 1 || y >= height - 1) {
            return false;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!usable[(y + dy) * width + x + dx]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double median(float[] values, int count) {
        float[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return count % 2 == 1 ? sorted[count / 2] : (sorted[count / 2 - 1] + (double) sorted[count / 2]) / 2;
    }
}
