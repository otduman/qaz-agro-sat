package kz.kazagrosat.pipeline.series;

import kz.kazagrosat.core.fields.LandLabel;

/**
 * A region of the season's field map, as the series lists it.
 *
 * @param id such as {@code 2025-F0001}
 * @param region the region's number in the label file
 * @param areaHectares the whole region
 * @param interiorPixels pixels left after the 20 m shrink
 * @param centroidLat the centre of the whole region, WGS84 degrees; {@code centroidLon} likewise
 * @param inSeries whether the series has rows for it: fields and fallow do, other regions don't
 */
public record SeriesField(String id, int region, LandLabel land, double areaHectares, int interiorPixels,
                          double interiorHectares, double centroidLat, double centroidLon, boolean inSeries) {}
