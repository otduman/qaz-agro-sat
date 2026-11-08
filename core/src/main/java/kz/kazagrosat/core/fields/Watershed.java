package kz.kazagrosat.core.fields;

/**
 * Splits a season edge image into regions by marker-based watershed: flooding starts from the calm
 * middles of fields and stops where floods from two markers meet on an edge ridge.
 *
 * <p>Markers are the calm pixels (edge below {@code markerEdge}) shrunk by {@code markerShrink} pixels
 * with a square window, kept as 4-connected components of at least {@code minMarkerPixels}. The shrink
 * stops a short gap in a boundary line from joining two fields into one marker. Outside the image counts
 * as calm, so fields on the border keep their markers.
 *
 * <p>Every marker starts flooding at once, whatever the edge values inside it. The flood is 4-connected
 * and takes the lowest edge first; ties go first-in first-out, so the result
 * depends only on the input. A pixel takes the label of the neighbour that reached it first. Pixels with
 * no edge value (NaN) get 0 and are never crossed, so pixels cut off by them also stay 0.
 */
public final class Watershed {

    /** Edge values are sorted into buckets of this width; values from 1.0 up share the top bucket. */
    private static final int LEVELS = 1000;

    private Watershed() {}

    /** @return per pixel its region, numbered from 1 in row-major order of each marker's first pixel */
    public static int[] flood(float[] edges, int width, int height, FieldParameters p) {
        int pixels = width * height;
        if (edges.length != pixels) {
            throw new IllegalArgumentException("Expected " + pixels + " edge values but got " + edges.length);
        }
        boolean[] seeds = shrink(calm(edges, p.markerEdge()), width, height, p.markerShrink());
        int[] labels = markers(seeds, width, height, p.minMarkerPixels());

        BucketQueue queue = new BucketQueue(LEVELS + 1, pixels);
        for (int i = 0; i < pixels; i++) {
            if (labels[i] != 0) {
                queue.push(0, i);
            }
        }
        int[] neighbours = new int[4];
        while (!queue.isEmpty()) {
            int current = queue.level();
            int i = queue.pop();
            int count = neighbours(i, width, height, neighbours);
            for (int k = 0; k < count; k++) {
                int n = neighbours[k];
                if (labels[n] == 0 && !Float.isNaN(edges[n])) {
                    labels[n] = labels[i];
                    queue.push(Math.max(current, level(edges[n])), n);
                }
            }
        }
        return labels;
    }

    private static int level(float edge) {
        return (int) Math.min(LEVELS, Math.floor(edge * LEVELS));
    }

    private static boolean[] calm(float[] edges, double markerEdge) {
        boolean[] calm = new boolean[edges.length];
        for (int i = 0; i < edges.length; i++) {
            calm[i] = edges[i] < markerEdge;
        }
        return calm;
    }

    /** Keeps only pixels with no uncalm pixel inside the (2r+1) x (2r+1) window around them. */
    private static boolean[] shrink(boolean[] calm, int width, int height, int radius) {
        if (radius == 0) {
            return calm;
        }
        boolean[] rows = new boolean[calm.length];
        for (int y = 0; y < height; y++) {
            int[] uncalm = new int[width + 1];
            for (int x = 0; x < width; x++) {
                uncalm[x + 1] = uncalm[x] + (calm[y * width + x] ? 0 : 1);
            }
            for (int x = 0; x < width; x++) {
                int from = Math.max(0, x - radius);
                int to = Math.min(width - 1, x + radius);
                rows[y * width + x] = uncalm[to + 1] - uncalm[from] == 0;
            }
        }
        boolean[] shrunk = new boolean[calm.length];
        int[] bad = new int[height + 1];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bad[y + 1] = bad[y] + (rows[y * width + x] ? 0 : 1);
            }
            for (int y = 0; y < height; y++) {
                int from = Math.max(0, y - radius);
                int to = Math.min(height - 1, y + radius);
                shrunk[y * width + x] = bad[to + 1] - bad[from] == 0;
            }
        }
        return shrunk;
    }

    /** Numbers the 4-connected components of the seed mask that are large enough, in scan order. */
    private static int[] markers(boolean[] seeds, int width, int height, int minPixels) {
        int[] labels = new int[seeds.length];
        boolean[] seen = new boolean[seeds.length];
        int[] component = new int[seeds.length];
        int[] neighbours = new int[4];
        int next = 1;
        for (int start = 0; start < seeds.length; start++) {
            if (!seeds[start] || seen[start]) {
                continue;
            }
            int size = 0;
            component[size++] = start;
            seen[start] = true;
            for (int head = 0; head < size; head++) {
                int count = neighbours(component[head], width, height, neighbours);
                for (int k = 0; k < count; k++) {
                    int n = neighbours[k];
                    if (seeds[n] && !seen[n]) {
                        seen[n] = true;
                        component[size++] = n;
                    }
                }
            }
            if (size >= minPixels) {
                for (int k = 0; k < size; k++) {
                    labels[component[k]] = next;
                }
                next++;
            }
        }
        return labels;
    }

    static int neighbours(int i, int width, int height, int[] out) {
        int x = i % width;
        int y = i / width;
        int count = 0;
        if (y > 0) {
            out[count++] = i - width;
        }
        if (x > 0) {
            out[count++] = i - 1;
        }
        if (x < width - 1) {
            out[count++] = i + 1;
        }
        if (y < height - 1) {
            out[count++] = i + width;
        }
        return count;
    }

    /** Pixel queues per edge level, each first-in first-out; levels are taken from the lowest up. */
    private static final class BucketQueue {

        private final int[][] items;
        private final int[] heads;
        private final int[] tails;
        private int lowest;
        private int size;

        BucketQueue(int levels, int expected) {
            items = new int[levels][];
            heads = new int[levels];
            tails = new int[levels];
            int initial = Math.max(16, expected / levels);
            for (int l = 0; l < levels; l++) {
                items[l] = new int[initial];
            }
        }

        void push(int level, int pixel) {
            if (tails[level] == items[level].length) {
                int live = tails[level] - heads[level];
                int[] grown = live * 2 > items[level].length ? new int[items[level].length * 2] : items[level];
                System.arraycopy(items[level], heads[level], grown, 0, live);
                items[level] = grown;
                heads[level] = 0;
                tails[level] = live;
            }
            items[level][tails[level]++] = pixel;
            if (level < lowest) {
                lowest = level;
            }
            size++;
        }

        boolean isEmpty() {
            return size == 0;
        }

        /** The lowest level holding a pixel; only valid when not empty. */
        int level() {
            while (heads[lowest] == tails[lowest]) {
                lowest++;
            }
            return lowest;
        }

        int pop() {
            int level = level();
            size--;
            return items[level][heads[level]++];
        }
    }
}
