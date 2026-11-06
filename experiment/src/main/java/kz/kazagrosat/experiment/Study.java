package kz.kazagrosat.experiment;

import java.time.LocalDate;
import java.util.Arrays;
import kz.kazagrosat.core.geo.BoundingBox;

/** What this experiment looks at: one test area over one season. */
final class Study {

    static final BoundingBox AREA = BoundingBox.parse("62.40,53.45,62.70,53.63");
    static final LocalDate FROM = LocalDate.of(2025, 5, 1);
    static final LocalDate TO = LocalDate.of(2025, 10, 31);

    /** Every field lies in this Sentinel-2 tile, so all its scenes share one pixel grid. */
    static final String TILE = "T41UMV";

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
