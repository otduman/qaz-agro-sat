package kz.kazagrosat.core.series;

/**
 * Each field's interior: its pixels shrunk away from every edge, so that readings inside it don't mix
 * in a neighbour, a track or the field's own headland. A pixel is in its field's interior when every
 * pixel within {@code shrinkPixels} of it (Chebyshev distance, so a square window) is inside the image
 * and has the same label. Label 0 means no field; it never has an interior but does shrink its
 * neighbours, like any other label.
 */
public final class FieldInteriors {

    private final int[][] pixels;

    private FieldInteriors(int[][] pixels) {
        this.pixels = pixels;
    }

    /**
     * @param labels per pixel its field, numbered from 1, or 0 for none
     * @param shrinkPixels how far from any other label or the image edge an interior pixel must be
     */
    public static FieldInteriors of(int[] labels, int width, int height, int shrinkPixels) {
        if (labels.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " labels but got " + labels.length);
        }
        if (shrinkPixels < 0) {
            throw new IllegalArgumentException("The shrink can't be negative: " + shrinkPixels);
        }
        int fields = 0;
        for (int label : labels) {
            if (label < 0) {
                throw new IllegalArgumentException("Labels can't be negative: " + label);
            }
            fields = Math.max(fields, label);
        }
        boolean[] interior = new boolean[labels.length];
        int[] counts = new int[fields + 1];
        for (int y = shrinkPixels; y < height - shrinkPixels; y++) {
            for (int x = shrinkPixels; x < width - shrinkPixels; x++) {
                int i = y * width + x;
                if (labels[i] != 0 && windowIsAll(labels, width, x, y, shrinkPixels, labels[i])) {
                    interior[i] = true;
                    counts[labels[i]]++;
                }
            }
        }
        int[][] pixels = new int[fields + 1][];
        for (int f = 1; f <= fields; f++) {
            pixels[f] = new int[counts[f]];
        }
        int[] filled = new int[fields + 1];
        for (int i = 0; i < labels.length; i++) {
            if (interior[i]) {
                pixels[labels[i]][filled[labels[i]]++] = i;
            }
        }
        return new FieldInteriors(pixels);
    }

    private static boolean windowIsAll(int[] labels, int width, int x, int y, int radius, int label) {
        for (int dy = -radius; dy <= radius; dy++) {
            int row = (y + dy) * width;
            for (int dx = -radius; dx <= radius; dx++) {
                if (labels[row + x + dx] != label) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The highest field number in the labels. */
    public int fieldCount() {
        return pixels.length - 1;
    }

    /** @return the field's interior pixels in row-major order, empty when nothing is left */
    public int[] pixelsOf(int field) {
        if (field < 1 || field > fieldCount()) {
            throw new IllegalArgumentException("No field " + field + "; fields run from 1 to " + fieldCount());
        }
        return pixels[field].clone();
    }
}
