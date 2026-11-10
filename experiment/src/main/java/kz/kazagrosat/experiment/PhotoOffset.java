package kz.kazagrosat.experiment;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * How far one Landsat photo sits from our Sentinel-2 scale, measured on the fields that did not change between
 * the Sentinel-2 photos either side of it. Haze and the two archives' atmospheric corrections give each photo an
 * offset of its own, which a fixed line ({@link BandFit}) cannot remove.
 */
final class PhotoOffset {

    /**
     * A field's Sentinel-2 values either side of a Landsat date.
     *
     * @param position where the Landsat date lies between the two, 0 at the earlier and 1 at the later
     */
    record Bracket(double before, double after, double position) {

        /** The value a straight line between the two dates gives on the Landsat date. */
        double expected() {
            return before + position * (after - before);
        }
    }

    private PhotoOffset() {}

    /**
     * The median of Landsat minus the expected Sentinel-2 value, over fields whose two Sentinel-2 values differ by
     * at most {@code tolerance}; NaN when fewer than {@code minFields} such fields.
     */
    static double of(double[] landsat, double[] before, double[] after, double[] position, double tolerance,
            int minFields) {
        double[] differences = new double[landsat.length];
        int count = 0;
        for (int i = 0; i < landsat.length; i++) {
            if (Math.abs(after[i] - before[i]) <= tolerance) {
                differences[count++] = landsat[i] - new Bracket(before[i], after[i], position[i]).expected();
            }
        }
        if (count < minFields) {
            return Double.NaN;
        }
        double[] kept = Arrays.copyOf(differences, count);
        Arrays.sort(kept);
        return count % 2 == 1 ? kept[count / 2] : (kept[count / 2 - 1] + kept[count / 2]) / 2;
    }

    /**
     * The nearest Sentinel-2 values before and after {@code date}, leaving out dates within {@code hide} days of it
     * and looking no further than {@code maxSpan} days each way. {@code hide} of 0 leaves out the date itself only.
     */
    static Optional<Bracket> bracket(NavigableMap<LocalDate, Double> series, LocalDate date, int hide, int maxSpan) {
        Map.Entry<LocalDate, Double> before = series.lowerEntry(date.minusDays(hide));
        Map.Entry<LocalDate, Double> after = series.higherEntry(date.plusDays(hide));
        if (before == null || after == null) {
            return Optional.empty();
        }
        long daysBefore = ChronoUnit.DAYS.between(before.getKey(), date);
        long daysAfter = ChronoUnit.DAYS.between(date, after.getKey());
        if (daysBefore > maxSpan || daysAfter > maxSpan) {
            return Optional.empty();
        }
        return Optional.of(new Bracket(before.getValue(), after.getValue(),
                (double) daysBefore / (daysBefore + daysAfter)));
    }
}
