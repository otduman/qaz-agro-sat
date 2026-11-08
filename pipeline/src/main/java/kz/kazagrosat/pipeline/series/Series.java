package kz.kazagrosat.pipeline.series;

import java.time.LocalDate;
import java.util.List;

/**
 * A season's per-field series.
 *
 * @param dates the stored dates, in order
 * @param fields every region of the field map, in region order
 * @param rows one per field in the series and date: each field's dates in order, fields in region order
 */
public record Series(int year, String tile, List<LocalDate> dates, List<SeriesField> fields, List<SeriesRow> rows) {}
