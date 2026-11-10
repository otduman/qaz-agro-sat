package kz.kazagrosat.pipeline.series;

import java.time.LocalDate;

/**
 * One field on one date. Values are medians over the field's clear interior pixels, NaN when under
 * {@link SeriesBuilder#MIN_CLEAR_SHARE} of the interior is clear. {@code clearShare} and
 * {@code snowShare} are shares of the whole interior, NaN only for a field with no interior.
 *
 * @param field the field's id, such as {@code 2025-F0001}
 * @param ndviP25 NDVI's 25th percentile; {@code ndviP75} is the 75th
 * @param b11 SWIR at 1.6 micrometres (Sentinel-2 band 11); {@code b12} at 2.2 micrometres
 * @param ndti the tillage index (b11 - b12) / (b11 + b12), per pixel and then the median
 * @param brightness the mean of blue, green and red
 * @param blue blue reflectance, which haze raises
 * @param snowShare the share the cloud mask calls snow, plus the share it calls clear ground where the snow
 *     test finds snow (cloud never counts)
 * @param roughness, lineStrength, lineDirection see {@link kz.kazagrosat.core.series.Texture}; blank on Landsat rows,
 *     whose 30 m pixels are too coarse for combine tracks, as is {@code brightness}
 * @param satellite {@link #SENTINEL_2}, or {@code LC08} or {@code LC09} for a Landsat row, whose values are put on
 *     the Sentinel-2 scale
 */
public record SeriesRow(String field, LocalDate date, double clearShare, double ndviP25, double ndviMedian,
                        double ndviP75, double b11, double b12, double ndti, double nir, double brightness,
                        double blue, double snowShare, double roughness, double lineStrength,
                        double lineDirection, String satellite) {

    public static final String SENTINEL_2 = "S2";

    /** A Sentinel-2 row. */
    public SeriesRow(String field, LocalDate date, double clearShare, double ndviP25, double ndviMedian,
                     double ndviP75, double b11, double b12, double ndti, double nir, double brightness,
                     double blue, double snowShare, double roughness, double lineStrength, double lineDirection) {
        this(field, date, clearShare, ndviP25, ndviMedian, ndviP75, b11, b12, ndti, nir, brightness, blue, snowShare,
                roughness, lineStrength, lineDirection, SENTINEL_2);
    }

    /** Whether the field was clear enough on this date to have values. */
    public boolean hasValues() {
        return !Double.isNaN(ndviMedian);
    }
}
