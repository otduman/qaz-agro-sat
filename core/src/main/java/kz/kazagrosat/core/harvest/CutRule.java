package kz.kazagrosat.core.harvest;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The frozen cut rule (rule v1 of the harvest-signal experiment, unchanged): from the first reading in August or
 * later with NDVI below 0.4, the split with the biggest rise in B11, comparing the median of up to three readings on
 * each side, with at least two after. A rise of {@link #JUMP} points or more counts as the cut.
 */
public final class CutRule {

    /** Readings at least this clear are used. */
    public static final double MIN_CLEAR = 0.85;
    /** A field counts as ripe once NDVI has fallen below this, in August or later. */
    public static final double RIPE_NDVI = 0.4;
    /** Rise in B11, in reflectance points (percent), that counts as a cut. */
    public static final double JUMP = 3.5;
    private static final int SIDE = 3;
    private static final int MIN_AFTER = 2;

    private CutRule() {}

    /** The cut lies between {@code before} and {@code after}; {@code rise} in reflectance points. */
    public record Step(LocalDate before, LocalDate after, double rise) {}

    /** The ripe readings: from the first one in August or later with NDVI below {@link #RIPE_NDVI}. */
    public static List<Reading> ripe(List<Reading> readings) {
        for (int i = 0; i < readings.size(); i++) {
            Reading r = readings.get(i);
            if (r.date().getMonthValue() >= 8 && r.ndvi() < RIPE_NDVI) {
                return readings.subList(i, readings.size());
            }
        }
        return List.of();
    }

    /**
     * The biggest B11 rise after ripening, whatever its size; empty when there are too few ripe readings to compare.
     * The readings must be usable (clear, screened) and in date order.
     */
    public static Optional<Step> biggestRise(List<Reading> readings) {
        List<Reading> ripe = ripe(readings);
        double[] values = ripe.stream().mapToDouble(r -> r.b11() * 100).toArray();
        Step best = null;
        for (int split = 1; split <= values.length - MIN_AFTER; split++) {
            double before = median(Arrays.copyOfRange(values, Math.max(0, split - SIDE), split));
            double after = median(Arrays.copyOfRange(values, split, Math.min(values.length, split + SIDE)));
            double rise = after - before;
            if (best == null || rise > best.rise()) {
                best = new Step(ripe.get(split - 1).date(), ripe.get(split).date(), rise);
            }
        }
        return Optional.ofNullable(best);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
