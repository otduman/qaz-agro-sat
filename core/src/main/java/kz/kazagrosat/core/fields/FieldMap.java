package kz.kazagrosat.core.fields;

import java.util.List;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * A season's field map.
 *
 * @param labels      per pixel its region id, 1 to n in row-major order of each region's first pixel; 0 is
 *                    no region
 * @param seasonEdges per pixel its season edge strength, NaN where there were too few clear dates
 * @param regions     the regions, in id order
 */
public record FieldMap(PixelGrid grid, int[] labels, float[] seasonEdges, List<Region> regions,
                       FieldSummary summary) {}
