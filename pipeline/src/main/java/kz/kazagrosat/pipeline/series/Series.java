package kz.kazagrosat.pipeline.series;

import java.time.LocalDate;
import java.util.List;

/**
 * A season's per-field series.
 *
 * @param dates the stored dates, in order
 * @param fields every region of the field map, in region order
 * @param rows one per field in the series and date (and Landsat satellite-day): each field's rows by date, Sentinel-2
 *     first on a shared date; fields in region order
 * @param landsatDays the Landsat satellite-days with rows, such as {@code 2025-07-20 LC09}; empty without Landsat
 * @param landsatSource where the Landsat adjustment came from; null without Landsat
 */
public record Series(int year, String tile, List<LocalDate> dates, List<SeriesField> fields, List<SeriesRow> rows,
                     List<String> landsatDays, String landsatSource) {

    /** A series of Sentinel-2 rows only. */
    public Series(int year, String tile, List<LocalDate> dates, List<SeriesField> fields, List<SeriesRow> rows) {
        this(year, tile, dates, fields, rows, List.of(), null);
    }
}
