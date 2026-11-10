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

    /**
     * The pixels touching the extent, clipped to this grid when the extent pokes out by at most {@code maxOutside}
     * metres on any side. A district square's longitude-latitude box can reach a few metres past its tile's edge
     * though the square itself is inside.
     */
    public PixelWindow windowClippedTo(Extent extent, double maxOutside) {
        double minX = originX;
        double maxX = originX + width * pixelSize;
        double maxY = originY;
        double minY = originY - height * pixelSize;
        if (extent.minX() < minX - maxOutside || extent.maxX() > maxX + maxOutside
                || extent.minY() < minY - maxOutside || extent.maxY() > maxY + maxOutside) {
            throw new IllegalArgumentException("Extent " + extent + " is not inside grid " + this);
        }
        return windowCovering(new Extent(Math.max(extent.minX(), minX), Math.max(extent.minY(), minY),
                Math.min(extent.maxX(), maxX), Math.min(extent.maxY(), maxY)));
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
