package kz.kazagrosat.pipeline.landsat;

import java.time.LocalDate;

/**
 * One Landsat scene of a Landsat folder, as recorded in its index.
 *
 * @param satellite  {@code LC08} or {@code LC09}
 * @param clearShare share of the area's 10 m pixels whose Landsat pixel the quality flags call clear
 * @param scale      shared by the six bands: reflectance = digital number * scale + offset
 * @param nodata     the bands' digital number for "no data", or null if there is none
 */
public record LandsatEntry(String scene, LocalDate date, String satellite, double clearShare, Status status,
                           double scale, double offset, Integer nodata) {

    public enum Status {
        /** The quality layer and all six bands are on disk. */
        STORED,
        /** None of the area was clear: only the quality layer is on disk. */
        CLOUDY
    }
}
