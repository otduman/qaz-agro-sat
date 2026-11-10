package kz.kazagrosat.experiment;

import java.util.Arrays;

/**
 * A straight line that puts a Landsat reading on our Sentinel-2 scale: sentinel = slope x landsat + intercept.
 * Fitted by Theil-Sen (the median of the slopes between every two pairs, then the median intercept), so a few pairs
 * that are off, such as a field cut between the two photos or a cloud edge, don't move it.
 */
record BandFit(double slope, double intercept, int pairs) {

    /** Beyond this many pairs, every k-th is used, to keep the pairwise slopes under about 8 million. */
    static final int MAX_POINTS = 4000;

    static BandFit of(double[] landsat, double[] sentinel) {
        if (landsat.length != sentinel.length) {
            throw new IllegalArgumentException("Landsat and Sentinel-2 values must come in pairs");
        }
        if (landsat.length < 2) {
            throw new IllegalArgumentException("A line needs at least two pairs, not " + landsat.length);
        }
        int step = (landsat.length + MAX_POINTS - 1) / MAX_POINTS;
        int n = (landsat.length + step - 1) / step;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = landsat[i * step];
            y[i] = sentinel[i * step];
        }
        double[] slopes = new double[n * (n - 1) / 2];
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (x[j] != x[i]) {
                    slopes[count++] = (y[j] - y[i]) / (x[j] - x[i]);
                }
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("All Landsat values are the same, so no slope can be fitted");
        }
        double slope = median(Arrays.copyOf(slopes, count));
        double[] intercepts = new double[landsat.length];
        for (int i = 0; i < landsat.length; i++) {
            intercepts[i] = sentinel[i] - slope * landsat[i];
        }
        return new BandFit(slope, median(intercepts), landsat.length);
    }

    double apply(double landsat) {
        return slope * landsat + intercept;
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2;
    }
}
