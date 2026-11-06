package kz.kazagrosat.pipeline.stack;

import java.time.LocalDate;

/**
 * One date of a season stack, as recorded in its index.
 *
 * @param tileCloud  cloud cover of the whole tile in percent, from the catalogue
 * @param clearShare share of the area's pixels the cloud mask marks as clear ground or water
 * @param scale      shared by all six bands: reflectance = digital number * scale + offset
 * @param nodata     the digital number meaning "no data", or null if there is none
 */
public record StackEntry(
        LocalDate date,
        String scene,
        double tileCloud,
        double clearShare,
        Status status,
        double scale,
        double offset,
        Integer nodata) {

    public enum Status {
        /** Every layer is on disk. */
        STORED,
        /** Too little of the area was clear: only the cloud mask is on disk. */
        CLOUDY
    }
}
