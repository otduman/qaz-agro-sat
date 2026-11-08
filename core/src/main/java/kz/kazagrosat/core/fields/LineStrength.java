package kz.kazagrosat.core.fields;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * How strongly each pixel lies on a long straight line of the season edge. Field borders here are long
 * and straight; noise and wet hollows are not. A border can be too faint to stop a calm area and still
 * run straight for hundreds of metres.
 *
 * <p>For each pixel and each of {@value #DIRECTIONS} directions: the median season edge along a segment of
 * 2r+1 pixels centred on the pixel, minus the mean season edge in the (2r+1) x (2r+1) square around it.
 * The line strength is the largest of these, or 0 if none is positive. A median ignores a blob covering a
 * few pixels of the segment, so only a line running most of the segment scores.
 */
public final class LineStrength {

    static final int DIRECTIONS = 12;

    private LineStrength() {}

    /**
     * @param radius r, half the segment length in pixels
     * @return per pixel its line strength, NaN where its season edge is NaN. NaN samples are skipped; a
     *         direction counts only with more than {@code radius} samples.
     */
    public static float[] of(float[] seasonEdges, int width, int height, int radius) {
        if (seasonEdges.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " edge values but got "
                    + seasonEdges.length);
        }
        if (radius < 1) {
            throw new IllegalArgumentException("The radius must be at least 1, not " + radius);
        }
        int stride = width + 1;
        double[] sums = new double[stride * (height + 1)];
        int[] counts = new int[stride * (height + 1)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = seasonEdges[y * width + x];
                int k = (y + 1) * stride + x + 1;
                boolean has = !Float.isNaN(v);
                sums[k] = sums[k - 1] + sums[k - stride] - sums[k - stride - 1] + (has ? v : 0);
                counts[k] = counts[k - 1] + counts[k - stride] - counts[k - stride - 1] + (has ? 1 : 0);
            }
        }
        int[][] dx = new int[DIRECTIONS][2 * radius + 1];
        int[][] dy = new int[DIRECTIONS][2 * radius + 1];
        for (int d = 0; d < DIRECTIONS; d++) {
            double angle = Math.PI * d / DIRECTIONS;
            for (int k = -radius; k <= radius; k++) {
                dx[d][k + radius] = (int) Math.round(k * Math.cos(angle));
                dy[d][k + radius] = (int) Math.round(k * Math.sin(angle));
            }
        }

        float[] lines = new float[seasonEdges.length];
        IntStream.range(0, height).parallel().forEach(y -> {
            float[] samples = new float[2 * radius + 1];
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (Float.isNaN(seasonEdges[i])) {
                    lines[i] = Float.NaN;
                    continue;
                }
                int left = Math.max(0, x - radius);
                int right = Math.min(width - 1, x + radius) + 1;
                int top = Math.max(0, y - radius);
                int bottom = Math.min(height - 1, y + radius) + 1;
                double sum = sums[bottom * stride + right] - sums[top * stride + right]
                        - sums[bottom * stride + left] + sums[top * stride + left];
                int count = counts[bottom * stride + right] - counts[top * stride + right]
                        - counts[bottom * stride + left] + counts[top * stride + left];
                double background = sum / count;
                double best = 0;
                for (int d = 0; d < DIRECTIONS; d++) {
                    int n = 0;
                    for (int k = 0; k <= 2 * radius; k++) {
                        int sx = x + dx[d][k];
                        int sy = y + dy[d][k];
                        if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                            continue;
                        }
                        float v = seasonEdges[sy * width + sx];
                        if (!Float.isNaN(v)) {
                            samples[n++] = v;
                        }
                    }
                    if (n > radius) {
                        Arrays.sort(samples, 0, n);
                        best = Math.max(best, samples[n / 2] - background);
                    }
                }
                lines[i] = (float) best;
            }
        });
        return lines;
    }
}
