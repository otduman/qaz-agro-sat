package kz.kazagrosat.pipeline;

import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stac.StacItem;

/**
 * NDVI for one area on one date.
 *
 * @param grid where the pixels sit on the ground
 * @param ndvi one value per pixel, row by row; NaN where masked or missing
 */
public record NdviResult(StacItem scene, PixelGrid grid, float[] ndvi) {

    /** The share of pixels holding a real value, from 0 to 1. Unlike tile cloud cover, this is for this area. */
    public double validFraction() {
        long valid = 0;
        for (float value : ndvi) {
            if (!Float.isNaN(value)) {
                valid++;
            }
        }
        return (double) valid / ndvi.length;
    }
}
