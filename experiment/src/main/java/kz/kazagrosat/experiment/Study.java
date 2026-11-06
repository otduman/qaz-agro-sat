package kz.kazagrosat.experiment;

import java.time.LocalDate;
import java.util.Arrays;
import kz.kazagrosat.core.geo.BoundingBox;

/** What this experiment looks at: one test area, over one or more seasons. */
final class Study {

    static final BoundingBox AREA = BoundingBox.parse("62.40,53.45,62.70,53.63");

    /** Every box lies in this Sentinel-2 tile, so all its scenes share one pixel grid. */
    static final String TILE = "T41UMV";

    /** The dates that matter in one growing season. */
    record Season(int year) {

        /** Everything downloaded: sowing to after the last harvest. */
        LocalDate from() { return LocalDate.of(year, 5, 1); }
        LocalDate to() { return LocalDate.of(year, 10, 31); }

        /** The stretch shown on the page and saved as photos. */
        LocalDate chartFrom() { return LocalDate.of(year, 7, 1); }
        LocalDate chartTo() { return LocalDate.of(year, 10, 25); }
        LocalDate photosTo() { return LocalDate.of(year, 10, 20); }

        /** Radar changes before this are growth, not harvest. */
        LocalDate radarFrom() { return LocalDate.of(year, 8, 1); }
    }

    private Study() {}

    /** The median of the first {@code count} values; the array is reordered. NaN when there are none. */
    static double median(float[] values, int count) {
        if (count == 0) {
            return Double.NaN;
        }
        Arrays.sort(values, 0, count);
        return count % 2 == 1 ? values[count / 2] : (values[count / 2 - 1] + values[count / 2]) / 2.0;
    }
}
