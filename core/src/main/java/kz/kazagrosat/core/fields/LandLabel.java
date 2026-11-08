package kz.kazagrosat.core.fields;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Arrays;
import java.util.List;

/**
 * What a region's land is, from its NDVI curve over the season, by simple stated rules:
 * <ul>
 *   <li><b>other</b> when its median NDVI is under {@code waterMedian}: water or salt;</li>
 *   <li><b>field</b> when it is bare in spring, then green, then drops: its lowest NDVI up to
 *       {@code springEnd} is under {@code springBare}; its peak is at least {@code peakGreen}, on or after
 *       {@code peakFrom}; and some later date up to {@code dropUntil} is {@code dropBy} or more below it;</li>
 *   <li><b>fallow</b> when at least {@code fallowShare} of its dates are under {@code fallowBare};</li>
 *   <li><b>other</b> otherwise: trees, grass, buildings.</li>
 * </ul>
 * A region needs a value on {@code minLabelDates} dates; with fewer, or with no spring date and not
 * fallow, it is labelled other and the reason is given.
 */
public enum LandLabel {
    FIELD,
    FALLOW,
    OTHER;

    public static final String TOO_FEW_DATES = "too few clear dates";
    public static final String NO_SPRING_DATE = "no clear spring date";

    /** @param reason null, unless the region was labelled other for lack of data */
    public record Labelled(LandLabel label, String reason) {}

    /**
     * @param values the region's NDVI per date, NaN where it has no value
     */
    public static Labelled of(List<LocalDate> dates, double[] values, FieldParameters p) {
        if (dates.size() != values.length) {
            throw new IllegalArgumentException(dates.size() + " dates but " + values.length + " values");
        }
        double[] present = Arrays.stream(values).filter(v -> !Double.isNaN(v)).toArray();
        if (present.length < p.minLabelDates()) {
            return new Labelled(OTHER, TOO_FEW_DATES);
        }
        if (median(present) < p.waterMedian()) {
            return new Labelled(OTHER, null);
        }
        boolean hasSpring = false;
        double springLow = Double.POSITIVE_INFINITY;
        int peak = -1;
        for (int d = 0; d < values.length; d++) {
            if (Double.isNaN(values[d])) {
                continue;
            }
            if (!MonthDay.from(dates.get(d)).isAfter(p.springEnd())) {
                hasSpring = true;
                springLow = Math.min(springLow, values[d]);
            }
            if (peak < 0 || values[d] > values[peak]) {
                peak = d;
            }
        }
        if (hasSpring && springLow < p.springBare() && isGreenThenDrops(dates, values, peak, p)) {
            return new Labelled(FIELD, null);
        }
        long bare = Arrays.stream(present).filter(v -> v < p.fallowBare()).count();
        if (bare >= p.fallowShare() * present.length) {
            return new Labelled(FALLOW, null);
        }
        return new Labelled(OTHER, hasSpring ? null : NO_SPRING_DATE);
    }

    private static boolean isGreenThenDrops(List<LocalDate> dates, double[] values, int peak, FieldParameters p) {
        double top = values[peak];
        if (top < p.peakGreen() || MonthDay.from(dates.get(peak)).isBefore(p.peakFrom())) {
            return false;
        }
        for (int d = peak + 1; d < values.length; d++) {
            if (MonthDay.from(dates.get(d)).isAfter(p.dropUntil())) {
                break;
            }
            if (!Double.isNaN(values[d]) && values[d] <= top - p.dropBy()) {
                return true;
            }
        }
        return false;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
