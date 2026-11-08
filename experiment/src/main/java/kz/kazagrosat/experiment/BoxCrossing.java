package kz.kazagrosat.experiment;

import java.util.HashMap;
import java.util.Map;

/**
 * Whether a field boundary crosses an experiment box: the box is crossed when its most common region
 * holds less than {@link #CROSSED_BELOW} of its pixels. Pixels left out of every region count as one
 * region of their own.
 */
final class BoxCrossing {

    static final double CROSSED_BELOW = 0.9;

    private BoxCrossing() {}

    /** The share of the box's pixels in its most common region; the box spans left..right-1, top..bottom-1. */
    static double largestShare(int[] labels, int width, Field box) {
        Map<Integer, Integer> counts = new HashMap<>();
        int total = 0;
        for (int y = box.top(); y < box.bottom(); y++) {
            for (int x = box.left(); x < box.right(); x++) {
                counts.merge(labels[y * width + x], 1, Integer::sum);
                total++;
            }
        }
        int largest = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return total == 0 ? Double.NaN : (double) largest / total;
    }

    static boolean crossed(int[] labels, int width, Field box) {
        return largestShare(labels, width, box) < CROSSED_BELOW;
    }
}
