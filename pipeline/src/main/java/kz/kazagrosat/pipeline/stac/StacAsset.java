package kz.kazagrosat.pipeline.stac;

/**
 * One file of a STAC item.
 *
 * @param scale  multiplier from digital number to physical value
 * @param offset added after scaling
 * @param nodata digital number meaning "no data", or null if the metadata names none
 */
public record StacAsset(String href, double scale, double offset, Integer nodata) {}
