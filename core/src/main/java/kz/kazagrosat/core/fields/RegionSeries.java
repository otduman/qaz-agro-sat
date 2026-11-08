package kz.kazagrosat.core.fields;

import java.util.Arrays;

/**
 * Per region and date, the clear pixel count, the sum of NDVI and the sum of its squares. From these
 * come each region's NDVI curve and spread. Sums add up exactly, so merged regions get exact curves.
 * Regions are numbered from 1; label 0 is never counted.
 */
public final class RegionSeries {

    private final int regions;
    private final int dates;
    private final int[] counts;
    private final double[] sums;
    private final double[] squares;

    public RegionSeries(int regions, int dates) {
        this.regions = regions;
        this.dates = dates;
        int cells = (regions + 1) * dates;
        this.counts = new int[cells];
        this.sums = new double[cells];
        this.squares = new double[cells];
    }

    public int regions() {
        return regions;
    }

    public int dates() {
        return dates;
    }

    /** Adds one date: every labelled, clear pixel with an NDVI value counts toward its region. */
    public void add(int dateIndex, int[] labels, float[] ndvi, boolean[] clear) {
        if (labels.length != ndvi.length || labels.length != clear.length) {
            throw new IllegalArgumentException("Labels, NDVI and clear flags differ in size: " + labels.length
                    + ", " + ndvi.length + " and " + clear.length);
        }
        for (int i = 0; i < labels.length; i++) {
            int region = labels[i];
            float value = ndvi[i];
            if (region == 0 || !clear[i] || Float.isNaN(value)) {
                continue;
            }
            int cell = cell(region, dateIndex);
            counts[cell]++;
            sums[cell] += value;
            squares[cell] += (double) value * value;
        }
    }

    public int clearCount(int region, int dateIndex) {
        return counts[cell(region, dateIndex)];
    }

    /** @return the region's mean NDVI that date, or NaN if under {@code minClearShare} of it was clear */
    public double mean(int region, int dateIndex, int regionPixels, double minClearShare) {
        int cell = cell(region, dateIndex);
        if (!enoughClear(counts[cell], regionPixels, minClearShare)) {
            return Double.NaN;
        }
        return sums[cell] / counts[cell];
    }

    /** @return the population standard deviation of the region's NDVI that date, or NaN as for the mean */
    public double spread(int region, int dateIndex, int regionPixels, double minClearShare) {
        int cell = cell(region, dateIndex);
        if (!enoughClear(counts[cell], regionPixels, minClearShare)) {
            return Double.NaN;
        }
        double mean = sums[cell] / counts[cell];
        return Math.sqrt(Math.max(0, squares[cell] / counts[cell] - mean * mean));
    }

    /**
     * How alike the region's pixels are over the season: 1 minus the rank-th highest daily spread over
     * {@code uniformitySpread}, clamped to 0..1. A region made of two different fields scores low.
     *
     * @return the score, or NaN when fewer than {@code edgeRank} dates have a value
     */
    public double uniformity(int region, int regionPixels, FieldParameters p) {
        double[] spreads = new double[dates];
        int n = 0;
        for (int d = 0; d < dates; d++) {
            double spread = spread(region, d, regionPixels, p.minClearShare());
            if (!Double.isNaN(spread)) {
                spreads[n++] = spread;
            }
        }
        if (n < p.edgeRank()) {
            return Double.NaN;
        }
        Arrays.sort(spreads, 0, n);
        double s = spreads[n - p.edgeRank()];
        return Math.clamp(1 - s / p.uniformitySpread(), 0.0, 1.0);
    }

    /**
     * @param newIdOf for each old region (index 1..regions), its new id from 1, or 0 to leave it out
     * @return a series over the new ids, holding the summed pixels of the old regions
     */
    public RegionSeries combine(int[] newIdOf, int newRegions) {
        if (newIdOf.length != regions + 1) {
            throw new IllegalArgumentException("Expected " + (regions + 1) + " ids but got " + newIdOf.length);
        }
        RegionSeries combined = new RegionSeries(newRegions, dates);
        for (int old = 1; old <= regions; old++) {
            int id = newIdOf[old];
            if (id == 0) {
                continue;
            }
            for (int d = 0; d < dates; d++) {
                int from = cell(old, d);
                int to = combined.cell(id, d);
                combined.counts[to] += counts[from];
                combined.sums[to] += sums[from];
                combined.squares[to] += squares[from];
            }
        }
        return combined;
    }

    /** Adds region {@code from}'s sums into region {@code into}, for merging in place. */
    void absorb(int into, int from) {
        for (int d = 0; d < dates; d++) {
            int to = cell(into, d);
            int source = cell(from, d);
            counts[to] += counts[source];
            sums[to] += sums[source];
            squares[to] += squares[source];
        }
    }

    RegionSeries copy() {
        RegionSeries copy = new RegionSeries(regions, dates);
        System.arraycopy(counts, 0, copy.counts, 0, counts.length);
        System.arraycopy(sums, 0, copy.sums, 0, sums.length);
        System.arraycopy(squares, 0, copy.squares, 0, squares.length);
        return copy;
    }

    private static boolean enoughClear(int count, int regionPixels, double minClearShare) {
        return count > 0 && count >= minClearShare * regionPixels;
    }

    private int cell(int region, int dateIndex) {
        if (region < 1 || region > regions || dateIndex < 0 || dateIndex >= dates) {
            throw new IndexOutOfBoundsException("No region " + region + " on date " + dateIndex);
        }
        return region * dates + dateIndex;
    }
}
