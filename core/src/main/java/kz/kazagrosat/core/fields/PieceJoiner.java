package kz.kazagrosat.core.fields;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Joins pieces of a field back to it after {@link RegionMerger}. Wet hollows, tree clumps and soil patches inside
 * a field draw strong but crooked season edges; they cut the field into pieces whose ridge is too strong for the
 * first pass and whose curves differ a little, since the hollow's own pixels sit in them. A border between two
 * fields is straight; a hollow is not. So a region joins a neighbour, whatever the ridge, when all of these hold:
 * <ul>
 *   <li>the rank-th highest gap between their NDVI curves is at most {@code pieceCurveTolerance};</li>
 *   <li>the median line strength ({@link LineStrength}) along their shared border is under {@code pieceLineEdge};</li>
 *   <li>the smaller of the two is at most {@code pieceMaxHectares} and at most {@code pieceMaxShare} of the
 *       larger, so two whole fields are never joined this way.</li>
 * </ul>
 * Joins go in rounds: in each, candidate pairs are taken closest curves first, and a region joins at most once.
 * Rounds repeat, with the joined curves, until none joins.
 */
public final class PieceJoiner {

    private PieceJoiner() {}

    private record Candidate(int a, int b, double gap) {}

    /**
     * @param labels       regions 1..n after the first pass, 0 for none
     * @param lines        per pixel its line strength, NaN where unknown
     * @param series       NDVI sums of the regions 1..n
     * @param regionPixels pixel count per region, index 1..n
     * @return for each region (index 0..n) its joined id, numbered from 1 in order of each joined region's smallest
     *         old label; index 0 maps to 0
     */
    public static int[] join(int[] labels, float[] lines, int width, int height, RegionSeries series,
                             int[] regionPixels, double pixelSize, FieldParameters p) {
        int regions = series.regions();
        if (labels.length != width * height || lines.length != labels.length
                || regionPixels.length != regions + 1) {
            throw new IllegalArgumentException("Inputs differ in size");
        }
        int maxPiece = FieldParameters.pixels(p.pieceMaxHectares(), pixelSize);
        int[] parent = new int[regions + 1];
        for (int r = 0; r <= regions; r++) {
            parent[r] = r;
        }
        RegionSeries joined = series.copy();
        int[] pixels = regionPixels.clone();
        int[] current = labels.clone();
        while (true) {
            List<Candidate> candidates = new ArrayList<>();
            for (RegionMerger.Pair pair : RegionMerger.borders(current, lines, width, height, regions)) {
                if (pair.ridge() >= p.pieceLineEdge()) {
                    continue;
                }
                int small = Math.min(pixels[pair.a()], pixels[pair.b()]);
                int large = Math.max(pixels[pair.a()], pixels[pair.b()]);
                if (small > maxPiece || small > p.pieceMaxShare() * large) {
                    continue;
                }
                double gap = RegionMerger.curveGap(joined, pair.a(), pair.b(), pixels, p);
                if (gap <= p.pieceCurveTolerance()) {
                    candidates.add(new Candidate(pair.a(), pair.b(), gap));
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::gap).thenComparingInt(Candidate::a)
                    .thenComparingInt(Candidate::b));
            boolean[] used = new boolean[regions + 1];
            int joins = 0;
            for (Candidate c : candidates) {
                if (used[c.a()] || used[c.b()]) {
                    continue;
                }
                used[c.a()] = true;
                used[c.b()] = true;
                int root = Math.min(c.a(), c.b());
                int other = Math.max(c.a(), c.b());
                parent[other] = root;
                joined.absorb(root, other);
                pixels[root] += pixels[other];
                pixels[other] = 0;
                joins++;
            }
            if (joins == 0) {
                break;
            }
            for (int i = 0; i < current.length; i++) {
                current[i] = RegionMerger.find(parent, current[i]);
            }
        }

        int[] newIdOf = new int[regions + 1];
        int[] idOfRoot = new int[regions + 1];
        int next = 1;
        for (int old = 1; old <= regions; old++) {
            int root = RegionMerger.find(parent, old);
            if (idOfRoot[root] == 0) {
                idOfRoot[root] = next++;
            }
            newIdOf[old] = idOfRoot[root];
        }
        return newIdOf;
    }
}
