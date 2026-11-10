package kz.kazagrosat.core.harvest;

import java.time.LocalDate;

/**
 * One field's reading on one date, as the cut rule uses it: from a series row with values.
 *
 * @param satellite {@code S2}, or a Landsat satellite whose values are already on the Sentinel-2 scale
 * @param clear     share of the field's interior that was clear
 * @param b11       shortwave infrared (Sentinel-2 B11) reflectance, 0 to 1
 * @param blue      blue reflectance, 0 to 1
 * @param snow      share of the field's interior flagged as snow
 */
public record Reading(LocalDate date, String satellite, double clear, double ndvi, double b11, double blue,
                      double snow) {}
