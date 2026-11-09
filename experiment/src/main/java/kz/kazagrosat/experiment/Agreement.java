package kz.kazagrosat.experiment;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** How closely Landsat readings agree with Sentinel-2 readings of the same fields on (nearly) the same days. */
final class Agreement {

    /**
     * @param bias the median of Landsat minus Sentinel-2
     * @param medianAbs the median absolute difference
     * @param slope, intercept the least-squares line Landsat = slope x Sentinel-2 + intercept
     */
    record Stats(int pairs, double bias, double medianAbs, double slope, double intercept) {}

    private Agreement() {}

    static Stats of(double[] landsat, double[] sentinel) {
        int n = landsat.length;
        if (n == 0) {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double[] diff = new double[n];
        double[] abs = new double[n];
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            diff[i] = landsat[i] - sentinel[i];
            abs[i] = Math.abs(diff[i]);
            mx += sentinel[i];
            my += landsat[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (sentinel[i] - mx) * (landsat[i] - my);
            sxx += (sentinel[i] - mx) * (sentinel[i] - mx);
        }
        double slope = sxx > 0 ? sxy / sxx : Double.NaN;
        return new Stats(n, median(diff), median(abs), slope, sxx > 0 ? my - slope * mx : Double.NaN);
    }

    /**
     * Pairs each Landsat date with the nearest Sentinel-2 date at most {@code maxDays} away (a tie goes to the
     * earlier date). @return pairs of {landsat, sentinel}
     */
    static List<double[]> pairs(Map<LocalDate, Double> landsat, Map<LocalDate, Double> sentinel, int maxDays) {
        List<double[]> pairs = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> l : landsat.entrySet()) {
            LocalDate best = null;
            long gap = Long.MAX_VALUE;
            for (LocalDate s : sentinel.keySet()) {
                long g = Math.abs(ChronoUnit.DAYS.between(l.getKey(), s));
                if (g <= maxDays && (g < gap || g == gap && s.isBefore(best))) {
                    best = s;
                    gap = g;
                }
            }
            if (best != null) {
                pairs.add(new double[] {l.getValue(), sentinel.get(best)});
            }
        }
        return pairs;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
