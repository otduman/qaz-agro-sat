package kz.kazagrosat.pipeline.series;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.pipeline.fields.OutputSwap;

/**
 * Writes a season's series into a folder:
 * <ul>
 *   <li>{@code series-<year>.csv}, one line per field and date, values to 4 decimals and blank where
 *       there is none;</li>
 *   <li>{@code fields-<year>.csv}, every region of the field map, including those with no rows;</li>
 *   <li>{@code summary-<year>.txt}, the counts of what has no values and why.</li>
 * </ul>
 * They replace the old files all together through {@link OutputSwap}.
 */
public final class SeriesOutputs {

    static final String SERIES_HEADER = "field,date,clear_share,ndvi_p25,ndvi_median,ndvi_p75,b11,b12,ndti,nir,"
            + "brightness,blue,snow_share,roughness,line_strength,line_direction,satellite";
    static final String FIELDS_HEADER = "id,land,area_ha,interior_ha,centroid_lat,centroid_lon,in_series";

    private SeriesOutputs() {}

    /** @return the files written, in the order listed above */
    public static List<Path> write(Series series, Path folder) throws IOException {
        int year = series.year();
        try (OutputSwap swap = new OutputSwap(folder)) {
            try (BufferedWriter out = Files.newBufferedWriter(swap.part("series-" + year + ".csv"),
                    StandardCharsets.UTF_8)) {
                out.write(SERIES_HEADER);
                out.write('\n');
                for (SeriesRow r : series.rows()) {
                    out.write(r.field() + "," + r.date());
                    for (double v : new double[] {r.clearShare(), r.ndviP25(), r.ndviMedian(), r.ndviP75(), r.b11(),
                            r.b12(), r.ndti(), r.nir(), r.brightness(), r.blue(), r.snowShare(), r.roughness(),
                            r.lineStrength(), r.lineDirection()}) {
                        out.write(',');
                        out.write(Double.isNaN(v) ? "" : decimals(v));
                    }
                    out.write(',');
                    out.write(r.satellite());
                    out.write('\n');
                }
            }
            try (BufferedWriter out = Files.newBufferedWriter(swap.part("fields-" + year + ".csv"),
                    StandardCharsets.UTF_8)) {
                out.write(FIELDS_HEADER);
                out.write('\n');
                for (SeriesField f : series.fields()) {
                    out.write(String.format(Locale.ROOT, "%s,%s,%.2f,%.2f,%s,%s,%s%n", f.id(),
                            f.land().name().toLowerCase(Locale.ROOT), f.areaHectares(), f.interiorHectares(),
                            degrees(f.centroidLat()), degrees(f.centroidLon()), f.inSeries() ? "yes" : "no")
                            .replace(System.lineSeparator(), "\n"));
                }
            }
            Files.writeString(swap.part("summary-" + year + ".txt"), summary(series), StandardCharsets.UTF_8);
            return swap.commit();
        }
    }

    /** Four decimals, with a value that rounds to zero written as 0.0000, never -0.0000. */
    static String decimals(double value) {
        String text = String.format(Locale.ROOT, "%.4f", value);
        return text.equals("-0.0000") ? "0.0000" : text;
    }

    private static String degrees(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    static String summary(Series series) {
        List<SeriesField> inSeries = series.fields().stream().filter(SeriesField::inSeries).toList();
        long fields = inSeries.stream().filter(f -> f.land() == LandLabel.FIELD).count();
        List<String> noInterior = inSeries.stream().filter(f -> f.interiorPixels() == 0).map(SeriesField::id).toList();
        long blank = series.rows().stream().filter(r -> !r.hasValues()).count();
        long cut = series.rows().stream().filter(r -> !r.hasValues() && !Double.isNaN(r.clearShare())).count();
        long snowy = series.rows().stream().filter(r -> r.snowShare() > 0).count();

        StringBuilder text = new StringBuilder();
        line(text, "Season: %d, tile %s", series.year(), series.tile());
        line(text, "Dates: %d (%s)", series.dates().size(),
                series.dates().stream().map(LocalDate::toString).collect(Collectors.joining(" ")));
        line(text, "Fields in the series: %d (field %d, fallow %d); other regions listed only: %d", inSeries.size(),
                fields, inSeries.size() - fields, series.fields().size() - inSeries.size());
        line(text, "Fields with nothing left after the %.0f m shrink: %d%s", SeriesBuilder.INTERIOR_METRES,
                noInterior.size(), noInterior.isEmpty() ? "" : " (" + String.join(" ", noInterior) + ")");
        line(text, "Rows: %d; with blank values: %d (under %.0f%% clear %d, no interior %d)", series.rows().size(),
                blank, SeriesBuilder.MIN_CLEAR_SHARE * 100, cut, blank - cut);
        line(text, "Rows with any snow in the interior: %d", snowy);
        if (series.landsatDays().isEmpty()) {
            line(text, "Landsat: none");
        } else {
            line(text, "Landsat: %d satellite-day%s (%s)", series.landsatDays().size(),
                    series.landsatDays().size() == 1 ? "" : "s", String.join(", ", series.landsatDays()));
            line(text, "Landsat put on the Sentinel-2 scale by: %s", series.landsatSource());
        }
        LocalDate from = LocalDate.of(series.year(), 8, 1);
        LocalDate to = LocalDate.of(series.year(), 9, 30);
        String gap = "Longest wait for a clear reading, 1 August to 30 September, typical field: Sentinel-2 %s";
        if (series.landsatDays().isEmpty()) {
            line(text, gap, days(typicalLongestGap(series, false, from, to)));
        } else {
            line(text, gap + ", with Landsat %s", days(typicalLongestGap(series, false, from, to)),
                    days(typicalLongestGap(series, true, from, to)));
        }
        return text.toString();
    }

    private static String days(long days) {
        return days < 0 ? "-" : days + " days";
    }

    /**
     * Per field in the series with an interior, the most days between neighbouring dates that have values, counting
     * the window's ends as edges; the median over those fields, or -1 when there are none.
     */
    static long typicalLongestGap(Series series, boolean withLandsat, LocalDate from, LocalDate to) {
        Map<String, TreeSet<LocalDate>> seen = new LinkedHashMap<>();
        for (SeriesField field : series.fields()) {
            if (field.inSeries() && field.interiorPixels() > 0) {
                seen.put(field.id(), new TreeSet<>());
            }
        }
        for (SeriesRow row : series.rows()) {
            boolean counted = withLandsat || row.satellite().equals(SeriesRow.SENTINEL_2);
            if (counted && row.hasValues() && seen.containsKey(row.field())) {
                seen.get(row.field()).add(row.date());
            }
        }
        if (seen.isEmpty()) {
            return -1;
        }
        long[] gaps = new long[seen.size()];
        int i = 0;
        for (TreeSet<LocalDate> dates : seen.values()) {
            LocalDate previous = from;
            long longest = 0;
            for (LocalDate date : dates.subSet(from, true, to, true)) {
                longest = Math.max(longest, ChronoUnit.DAYS.between(previous, date));
                previous = date;
            }
            gaps[i++] = Math.max(longest, ChronoUnit.DAYS.between(previous, to));
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    private static void line(StringBuilder text, String format, Object... values) {
        text.append(String.format(Locale.ROOT, format, values)).append('\n');
    }
}
