package kz.kazagrosat.core.fields;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What happened on the way to a field map, so that nothing is left out silently.
 *
 * @param dates            the dates used
 * @param watershedRegions regions the watershed found
 * @param merges           merges of neighbouring regions
 * @param droppedRegions   regions dropped as too small
 * @param droppedPixels    pixels of dropped regions still left out after holes were filled
 * @param filledHoles      holes filled into the region around them
 * @param filledPixels     pixels in filled holes
 * @param keptHoles        holes kept as holes, being large enough
 * @param noDataPixels     pixels left out for having too few clear dates, or being cut off by such pixels
 * @param otherReasons     regions labelled other for lack of data, per reason
 */
public record FieldSummary(
        List<LocalDate> dates,
        int watershedRegions,
        int merges,
        int droppedRegions,
        long droppedPixels,
        int filledHoles,
        long filledPixels,
        int keptHoles,
        long noDataPixels,
        Map<String, Integer> otherReasons) {}
