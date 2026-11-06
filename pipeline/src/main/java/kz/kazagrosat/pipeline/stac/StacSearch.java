package kz.kazagrosat.pipeline.stac;

import java.time.LocalDate;
import kz.kazagrosat.core.geo.BoundingBox;

/**
 * A STAC item search over whole UTC days, both inclusive.
 *
 * @param maxCloudCover upper limit in percent, or null for no limit
 */
public record StacSearch(String collection, BoundingBox bbox, LocalDate from, LocalDate to, Double maxCloudCover) {}
