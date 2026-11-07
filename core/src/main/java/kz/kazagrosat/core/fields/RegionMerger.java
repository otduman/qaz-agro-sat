package kz.kazagrosat.core.fields;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges neighbouring regions that are really one field split by wet patches or soil differences.
 * Two neighbours merge when both hold:
 * <ul>
 *   <li>the median ridge along their shared border is at most {@code ridgeWeak}; the ridge across one
 *       pixel pair is the larger of the two pixels' season edges;</li>
 *   <li>they have at least {@code minCommonDates} dates where both have a value, and the rank-th highest
 *       gap between their NDVI curves on those dates is at most {@code curveTolerance}.</li>
 * </ul>
 * Pairs are tried from the weakest ridge up. Once two regions merge, later comparisons use the merged
 * curve, recomputed exactly from pixel sums.
 */
public final class RegionMerger {

    private RegionMerger() {}

    /**
     * @param regionPixels pixel count per label, index 1..regions
     * @return for each old label (index 0..regions) its merged id, numbered from 1 in order of each merged
     *         region's smallest old label; index 0 maps to 0
     */
    public static int[] merge(int[] labels, float[] seasonEdges, int width, int height, RegionSeries series,
                              int[] regionPixels, FieldParameters p) {
        int regions = series.regions();
        if (labels.length != width * height || seasonEdges.length != labels.length
                || regionPixels.length != regions + 1) {
            throw new IllegalArgumentException("Inputs differ in size");
        }
        List<Pair> pairs = borders(labels, seasonEdges, width, height, regions);
        pairs.sort(Comparator.comparingDouble(Pair::ridge).thenComparingInt(Pair::a).thenComparingInt(Pair::b));

        int[] parent = new int[regions + 1];
        for (int r = 0; r <= regions; r++) {
            parent[r] = r;
        }
        RegionSeries merged = series.copy();
        int[] pixels = regionPixels.clone();
        for (Pair pair : pairs) {
            if (pair.ridge() > p.ridgeWeak()) {
                break;
            }
            int ra = find(parent, pair.a());
            int rb = find(parent, pair.b());
            if (ra == rb || !curvesMatch(merged, ra, rb, pixels, p)) {
                continue;
            }
            int root = Math.min(ra, rb);
            int other = Math.max(ra, rb);
            parent[other] = root;
            merged.absorb(root, other);
            pixels[root] += pixels[other];
        }

        int[] newIdOf = new int[regions + 1];
        int[] idOfRoot = new int[regions + 1];
        int next = 1;
        for (int old = 1; old <= regions; old++) {
            int root = find(parent, old);
            if (idOfRoot[root] == 0) {
                idOfRoot[root] = next++;
            }
            newIdOf[old] = idOfRoot[root];
        }
        return newIdOf;
    }

    private static boolean curvesMatch(RegionSeries series, int a, int b, int[] pixels, FieldParameters p) {
        double[] gaps = new double[series.dates()];
        int common = 0;
        for (int d = 0; d < series.dates(); d++) {
            double ma = series.mean(a, d, pixels[a], p.minClearShare());
            double mb = series.mean(b, d, pixels[b], p.minClearShare());
            if (!Double.isNaN(ma) && !Double.isNaN(mb)) {
                gaps[common++] = Math.abs(ma - mb);
            }
        }
        if (common < p.minCommonDates() || common < p.edgeRank()) {
            return false;
        }
        Arrays.sort(gaps, 0, common);
        return gaps[common - p.edgeRank()] <= p.curveTolerance();
    }

    private static int find(int[] parent, int r) {
        while (parent[r] != r) {
            parent[r] = parent[parent[r]];
            r = parent[r];
        }
        return r;
    }

    record Pair(int a, int b, double ridge) {}

    /** Every pair of 4-neighbouring labels, with the median ridge along their shared border. */
    static List<Pair> borders(int[] labels, float[] edges, int width, int height, int regions) {
        Map<Long, FloatList> cracks = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (labels[i] == 0) {
                    continue;
                }
                if (x < width - 1) {
                    addCrack(cracks, labels, edges, i, i + 1, regions);
                }
                if (y < height - 1) {
                    addCrack(cracks, labels, edges, i, i + width, regions);
                }
            }
        }
        List<Pair> pairs = new ArrayList<>(cracks.size());
        for (Map.Entry<Long, FloatList> entry : cracks.entrySet()) {
            long key = entry.getKey();
            int a = (int) (key / (regions + 1L));
            int b = (int) (key % (regions + 1L));
            pairs.add(new Pair(a, b, entry.getValue().median()));
        }
        return pairs;
    }

    private static void addCrack(Map<Long, FloatList> cracks, int[] labels, float[] edges, int i, int j,
                                 int regions) {
        int a = labels[i];
        int b = labels[j];
        if (b == 0 || a == b || Float.isNaN(edges[i]) || Float.isNaN(edges[j])) {
            return;
        }
        long key = (long) Math.min(a, b) * (regions + 1L) + Math.max(a, b);
        cracks.computeIfAbsent(key, k -> new FloatList()).add(Math.max(edges[i], edges[j]));
    }

    private static final class FloatList {
        private float[] values = new float[8];
        private int size;

        void add(float value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        double median() {
            float[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return size % 2 == 1 ? sorted[size / 2] : (sorted[size / 2 - 1] + (double) sorted[size / 2]) / 2;
        }
    }
}
