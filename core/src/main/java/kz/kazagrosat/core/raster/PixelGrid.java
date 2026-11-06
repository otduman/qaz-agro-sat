package kz.kazagrosat.core.raster;

import kz.kazagrosat.core.geo.Extent;

/**
 * Places a north-up raster on the ground.
 *
 * @param originX easting of the left edge of the first pixel
 * @param originY northing of the top edge of the first pixel
 */
public record PixelGrid(int epsg, double originX, double originY, double pixelSize, int width, int height) {

    /** The pixels touching the extent, which must lie inside this grid. */
    public PixelWindow windowCovering(Extent extent) {
        int left = (int) Math.floor((extent.minX() - originX) / pixelSize);
        int right = (int) Math.ceil((extent.maxX() - originX) / pixelSize);
        int top = (int) Math.floor((originY - extent.maxY()) / pixelSize);
        int bottom = (int) Math.ceil((originY - extent.minY()) / pixelSize);
        if (left < 0 || top < 0 || right > width || bottom > height) {
            throw new IllegalArgumentException("Extent " + extent + " is not inside grid " + this);
        }
        return new PixelWindow(left, top, right - left, bottom - top);
    }

    public PixelGrid subGrid(PixelWindow window) {
        return new PixelGrid(
                epsg,
                originX + window.col() * pixelSize,
                originY - window.row() * pixelSize,
                pixelSize,
                window.width(),
                window.height());
    }
}
