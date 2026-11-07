package kz.kazagrosat.core.fields;

import java.time.MonthDay;

/**
 * Every number the field-boundary method uses. {@link #DEFAULTS} holds the values in use; they are tuned
 * on the 2025 season only and then run unchanged on other seasons.
 *
 * @param brightnessWeight  multiplies the brightness gradient before it is compared with the NDVI gradient
 * @param edgeRank          the season edge of a pixel is its rank-th highest value over the season
 * @param markerEdge        pixels whose season edge is below this are calm
 * @param markerShrink      pixels the calm mask is shrunk by before markers are taken from it
 * @param minMarkerPixels   calm areas smaller than this, after shrinking, seed no region
 * @param ridgeWeak         neighbours merge only if the median ridge on their border is at most this
 * @param curveTolerance    neighbours merge only if the rank-th highest gap between their curves is at most this
 * @param minCommonDates    dates on which both neighbours need a value before they can merge
 * @param minClearShare     share of a region's pixels that must be clear for its value on a date to count
 * @param minRegionHectares regions smaller than this are dropped
 * @param maxHoleHectares   holes smaller than this are filled
 * @param minLabelDates     dates with a value a region needs for a land label
 * @param springEnd         last day counted as spring
 * @param springBare        a field's lowest spring NDVI is under this
 * @param peakGreen         a field's peak NDVI is at least this
 * @param peakFrom          a field's peak is on or after this day
 * @param dropBy            a field drops at least this far below its peak after it
 * @param dropUntil         ... on or before this day
 * @param fallowBare        NDVI under this counts as bare for fallow
 * @param fallowShare       fallow is bare on at least this share of its dates
 * @param waterMedian       a median NDVI under this is water or salt
 * @param uniformitySpread  the NDVI spread at which the uniformity score reaches 0
 * @param simplifyMetres    tolerance for smoothing outlines. JTS squares it into the largest corner
 *                          triangle it removes, so smoothing a one-pixel staircase step (a triangle of
 *                          half a pixel squared) needs more than 0.71 pixel
 */
public record FieldParameters(
        double brightnessWeight,
        int edgeRank,
        double markerEdge,
        int markerShrink,
        int minMarkerPixels,
        double ridgeWeak,
        double curveTolerance,
        int minCommonDates,
        double minClearShare,
        double minRegionHectares,
        double maxHoleHectares,
        int minLabelDates,
        MonthDay springEnd,
        double springBare,
        double peakGreen,
        MonthDay peakFrom,
        double dropBy,
        MonthDay dropUntil,
        double fallowBare,
        double fallowShare,
        double waterMedian,
        double uniformitySpread,
        double simplifyMetres) {

    public static final FieldParameters DEFAULTS = new FieldParameters(
            5.0, 3, 0.15, 2, 50, 0.2, 0.05, 5, 0.6, 5.0, 1.0, 5,
            MonthDay.of(6, 15), 0.3, 0.5, MonthDay.of(6, 20), 0.25, MonthDay.of(9, 30),
            0.3, 0.7, 0.05, 0.25, 10.0);

    public FieldParameters {
        if (edgeRank < 1) {
            throw new IllegalArgumentException("edgeRank must be at least 1, not " + edgeRank);
        }
        if (markerShrink < 0 || minMarkerPixels < 1 || minCommonDates < 1 || minLabelDates < 1) {
            throw new IllegalArgumentException("Counts must be positive: " + this);
        }
    }

    /** Converts an area in hectares to a pixel count on a grid of square pixels of this size in metres. */
    public static int pixels(double hectares, double pixelSize) {
        return (int) Math.round(hectares * 10_000 / (pixelSize * pixelSize));
    }
}
