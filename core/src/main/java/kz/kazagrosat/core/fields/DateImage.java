package kz.kazagrosat.core.fields;

import java.time.LocalDate;

/**
 * One date of a season on the area's 10 m grid: NDVI, visible brightness (the mean of blue, green and
 * red reflectance) and whether each pixel is clear.
 */
public record DateImage(LocalDate date, float[] ndvi, float[] brightness, boolean[] clear) {}
