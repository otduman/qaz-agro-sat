package kz.kazagrosat.pipeline.landsat;

import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;

/**
 * Puts a Landsat scene, on its own 30 m grid in the same UTM zone, onto the 10 m area grid: each area pixel takes
 * the Landsat pixel its centre falls in.
 */
public final class LandsatGrid {

    private LandsatGrid() {}

    /** The part of the scene that the area's pixel centres fall in, or null when none does. */
    public static PixelWindow windowFor(PixelGrid area, PixelGrid scene) {
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
    public static int[] lookup(PixelGrid area, PixelGrid scene, PixelWindow window) {
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
}
