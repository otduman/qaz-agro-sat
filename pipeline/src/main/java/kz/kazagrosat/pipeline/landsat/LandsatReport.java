package kz.kazagrosat.pipeline.landsat;

import java.util.List;

/**
 * What a {@code landsat} run did.
 *
 * @param found     scenes the catalogue returned for the area and season
 * @param landsat7  of those, Landsat 7 scenes, which are left out (its scan-line gaps since 2003)
 * @param otherZone scenes on a grid in another UTM zone than the stack, left out
 * @param outside   scenes whose grid holds none of the area's pixel centres
 * @param failures  one line per scene that failed, for running again
 */
public record LandsatReport(int found, int landsat7, List<String> otherZone, int outside, int alreadyStored,
                            int stored, int cloudy, List<String> failures) {}
