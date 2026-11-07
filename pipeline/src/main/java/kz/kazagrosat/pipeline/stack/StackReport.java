package kz.kazagrosat.pipeline.stack;

import java.util.List;

/**
 * What one run of {@link SeasonStackBuilder} did.
 *
 * @param datesFound    dates in the season with a scene covering the area
 * @param alreadyStored of those, dates a previous run had finished
 * @param stored        dates this run stored with all their layers
 * @param cloudy        dates this run recorded as cloudy, keeping only their cloud mask
 * @param failures      one line per date that failed, as "YYYY-MM-DD: reason", in date order;
 *                      a rerun retries them
 */
public record StackReport(String tile, int datesFound, int alreadyStored, int stored, int cloudy,
                          List<String> failures) {}
