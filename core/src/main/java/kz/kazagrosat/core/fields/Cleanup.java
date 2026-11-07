package kz.kazagrosat.core.fields;

/** Tidies a label map: drops regions that are too small and fills small holes. Inputs are not changed. */
public final class Cleanup {

    private Cleanup() {}

    /**
     * @param holes  the label map after filling
     * @param filled holes filled into the region around them
     * @param filledPixels pixels in those holes
     * @param kept   holes inside a single region kept as holes, being {@code maxPixels} or larger
     */
    public record Holes(int[] labels, int filled, long filledPixels, int kept) {}

    /** Sets every region with fewer than {@code minPixels} pixels to 0. */
    public static int[] dropSmall(int[] labels, int regionCount, int minPixels) {
        int[] counts = new int[regionCount + 1];
        for (int label : labels) {
            counts[label]++;
        }
        int[] result = labels.clone();
        for (int i = 0; i < result.length; i++) {
            if (counts[result[i]] < minPixels) {
                result[i] = 0;
            }
        }
        return result;
    }

    /**
     * Gives each 4-connected area of 0s to the region around it, when the area is under {@code maxPixels},
     * doesn't touch the image border and borders exactly one region.
     */
    public static Holes fillHoles(int[] labels, int width, int height, int maxPixels) {
        int[] result = labels.clone();
        boolean[] seen = new boolean[labels.length];
        int[] component = new int[labels.length];
        int[] neighbours = new int[4];
        int filled = 0;
        long filledPixels = 0;
        int kept = 0;
        for (int start = 0; start < labels.length; start++) {
            if (labels[start] != 0 || seen[start]) {
                continue;
            }
            int size = 0;
            component[size++] = start;
            seen[start] = true;
            boolean touchesBorder = false;
            int around = 0;
            boolean several = false;
            for (int head = 0; head < size; head++) {
                int i = component[head];
                int x = i % width;
                int y = i / width;
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    touchesBorder = true;
                }
                int count = Watershed.neighbours(i, width, height, neighbours);
                for (int k = 0; k < count; k++) {
                    int n = neighbours[k];
                    int label = labels[n];
                    if (label == 0) {
                        if (!seen[n]) {
                            seen[n] = true;
                            component[size++] = n;
                        }
                    } else if (around == 0) {
                        around = label;
                    } else if (label != around) {
                        several = true;
                    }
                }
            }
            if (touchesBorder || several || around == 0) {
                continue;
            }
            if (size < maxPixels) {
                for (int k = 0; k < size; k++) {
                    result[component[k]] = around;
                }
                filled++;
                filledPixels += size;
            } else {
                kept++;
            }
        }
        return new Holes(result, filled, filledPixels, kept);
    }
}
