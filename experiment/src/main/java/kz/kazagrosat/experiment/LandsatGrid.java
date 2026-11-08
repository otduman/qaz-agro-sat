package kz.kazagrosat.experiment;

import java.util.Arrays;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;

/**
 * Puts a Landsat scene, on its own 30 m grid in the same UTM zone, onto the 10 m area grid: each area pixel takes
 * the Landsat pixel its centre falls in.
 */
final class LandsatGrid {

    private LandsatGrid() {}

    /** The part of the scene that the area's pixel centres fall in, or null when none does. */
    static PixelWindow windowFor(PixelGrid area, PixelGrid scene) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = -1;
        int bottom = -1;
        for (int x = 0; x < area.width(); x++) {
            int col = column(area, scene, x);
            if (col >= 0 && col < scene.width()) {
                left = Math.min(left, col);
                right = Math.max(right, col);
            }
        }
        for (int y = 0; y < area.height(); y++) {
            int row = row(area, scene, y);
            if (row >= 0 && row < scene.height()) {
                top = Math.min(top, row);
                bottom = Math.max(bottom, row);
            }
        }
        if (right < 0 || bottom < 0) {
            return null;
        }
        return new PixelWindow(left, top, right - left + 1, bottom - top + 1);
    }

    /** For each area pixel, its index in an array read from {@code window} of the scene, or -1 outside it. */
    static int[] lookup(PixelGrid area, PixelGrid scene, PixelWindow window) {
        int[] cols = new int[area.width()];
        for (int x = 0; x < area.width(); x++) {
            int col = column(area, scene, x) - window.col();
            cols[x] = col >= 0 && col < window.width() ? col : -1;
        }
        int[] lookup = new int[area.width() * area.height()];
        for (int y = 0; y < area.height(); y++) {
            int row = row(area, scene, y) - window.row();
            for (int x = 0; x < area.width(); x++) {
                boolean inside = row >= 0 && row < window.height() && cols[x] >= 0;
                lookup[y * area.width() + x] = inside ? row * window.width() + cols[x] : -1;
            }
        }
        return lookup;
    }

    private static int column(PixelGrid area, PixelGrid scene, int x) {
        double centre = area.originX() + (x + 0.5) * area.pixelSize();
        return (int) Math.floor((centre - scene.originX()) / scene.pixelSize());
    }

    private static int row(PixelGrid area, PixelGrid scene, int y) {
        double centre = area.originY() - (y + 0.5) * area.pixelSize();
        return (int) Math.floor((scene.originY() - centre) / scene.pixelSize());
    }

    /** One satellite's photos of one day, joined: a pixel takes the first clear value any scene gives it. */
    static final class Day {

        private final boolean[] clear;
        private final float[][] bands;
        private int scenes;

        Day(int pixels, int bandCount) {
            clear = new boolean[pixels];
            bands = new float[bandCount][pixels];
            for (float[] band : bands) {
                Arrays.fill(band, Float.NaN);
            }
        }

        /** @param qa the scene window's {@code qa_pixel}; {@code values} its bands, in this day's band order */
        void add(int[] lookup, int[] qa, float[][] values) {
            scenes++;
            for (int i = 0; i < clear.length; i++) {
                int k = lookup[i];
                if (clear[i] || k < 0 || !QaPixel.clear(qa[k])) {
                    continue;
                }
                boolean complete = true;
                for (float[] value : values) {
                    complete &= !Float.isNaN(value[k]);
                }
                if (!complete) {
                    continue;
                }
                clear[i] = true;
                for (int b = 0; b < bands.length; b++) {
                    bands[b][i] = values[b][k];
                }
            }
        }

        boolean[] clear() {
            return clear;
        }

        float[] band(int b) {
            return bands[b];
        }

        int scenes() {
            return scenes;
        }
    }
}
