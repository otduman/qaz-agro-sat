package kz.kazagrosat.core.fields;

/**
 * One region of a season's field map.
 *
 * @param uniformity how alike its pixels are, 0 to 1, or NaN when it can't be scored
 */
public record Region(int id, int pixels, LandLabel land, double uniformity) {}
