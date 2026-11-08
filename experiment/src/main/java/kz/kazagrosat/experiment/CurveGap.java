package kz.kazagrosat.experiment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** How far apart two curves are: the absolute differences on the dates both have a value for. */
final class CurveGap {

    /** @param median the median absolute difference, NaN with no common date; {@code largest} likewise */
    record Gap(double median, double largest, int dates) {}

    private CurveGap() {}

    static Gap of(Map<LocalDate, Double> a, Map<LocalDate, Double> b) {
        List<Double> gaps = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> entry : a.entrySet()) {
            Double other = b.get(entry.getKey());
            if (other != null && !entry.getValue().isNaN() && !other.isNaN()) {
                gaps.add(Math.abs(entry.getValue() - other));
            }
        }
        if (gaps.isEmpty()) {
            return new Gap(Double.NaN, Double.NaN, 0);
        }
        gaps.sort(null);
        int n = gaps.size();
        double median = n % 2 == 1 ? gaps.get(n / 2) : (gaps.get(n / 2 - 1) + gaps.get(n / 2)) / 2;
        return new Gap(median, gaps.get(n - 1), n);
    }
}
