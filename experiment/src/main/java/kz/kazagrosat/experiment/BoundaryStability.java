package kz.kazagrosat.experiment;

/**
 * How much two years' field boundaries agree. A boundary pixel is one whose label differs from its
 * right or lower neighbour; "within" a tolerance uses the larger of the column and row distances.
 */
final class BoundaryStability {

    private BoundaryStability() {}

    /** The share of {@code from}'s boundary pixels lying within {@code tolerance} of a {@code to} boundary. */
    static double share(int[] from, int[] to, int width, int height, int tolerance) {
        boolean[] fromBoundary = boundary(from, width, height);
        boolean[] near = near(boundary(to, width, height), width, height, tolerance);
        long boundaryPixels = 0;
        long matched = 0;
        for (int i = 0; i < fromBoundary.length; i++) {
            if (fromBoundary[i]) {
                boundaryPixels++;
                if (near[i]) {
                    matched++;
                }
            }
        }
        return boundaryPixels == 0 ? Double.NaN : (double) matched / boundaryPixels;
    }

    /** The share of all pixels within {@code tolerance} of a {@code to} boundary: what a random line would score. */
    static double chance(int[] to, int width, int height, int tolerance) {
        boolean[] near = near(boundary(to, width, height), width, height, tolerance);
        long count = 0;
        for (boolean b : near) {
            if (b) {
                count++;
            }
        }
        return (double) count / near.length;
    }

    static boolean[] boundary(int[] labels, int width, int height) {
        boolean[] boundary = new boolean[labels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                boundary[i] = x < width - 1 && labels[i + 1] != labels[i]
                        || y < height - 1 && labels[i + width] != labels[i];
            }
        }
        return boundary;
    }

    private static boolean[] near(boolean[] boundary, int width, int height, int tolerance) {
        boolean[] rows = new boolean[boundary.length];
        for (int y = 0; y < height; y++) {
            int last = Integer.MIN_VALUE / 2;
            for (int x = 0; x < width; x++) {
                if (boundary[y * width + x]) {
                    last = x;
                }
                rows[y * width + x] = x - last <= tolerance;
            }
            last = Integer.MAX_VALUE / 2;
            for (int x = width - 1; x >= 0; x--) {
                if (boundary[y * width + x]) {
                    last = x;
                }
                rows[y * width + x] |= last - x <= tolerance;
            }
        }
        boolean[] near = new boolean[boundary.length];
        for (int x = 0; x < width; x++) {
            int last = Integer.MIN_VALUE / 2;
            for (int y = 0; y < height; y++) {
                if (rows[y * width + x]) {
                    last = y;
                }
                near[y * width + x] = y - last <= tolerance;
            }
            last = Integer.MAX_VALUE / 2;
            for (int y = height - 1; y >= 0; y--) {
                if (rows[y * width + x]) {
                    last = y;
                }
                near[y * width + x] |= last - y <= tolerance;
            }
        }
        return near;
    }
}
