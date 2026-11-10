package kz.kazagrosat.pipeline.landsat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts Landsat readings on our Sentinel-2 scale: {@code sentinel = slope x landsat + intercept}, one line per measure,
 * fitted on fields seen by both satellites at most a day apart (the experiment's {@code LandsatFit}). The lines rise,
 * so applying them to every pixel and then taking medians gives the same as applying them to the medians.
 *
 * <p>The default lines were fitted on the Kokshetau square (68.70-69.00 E, 53.80-54.00 N) in 2025 and held, unchanged,
 * on Kokshetau 2023, 2024 and 2026 and Kostanay 2023 to 2026 (notes of 10 November 2026).
 */
public final class LandsatAdjustment {

    /** The measures a line is fitted for, by their column names in the fit file. */
    public enum Measure {
        NDVI("ndvi_median"), B11("b11"), B12("b12"), NIR("nir"), BLUE("blue");

        private final String column;

        Measure(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }
    }

    private record Line(double slope, double intercept) {}

    public static final LandsatAdjustment DEFAULT = new LandsatAdjustment(Map.of(
            Measure.NDVI, new Line(1.037294, -0.044746),
            Measure.B11, new Line(0.880184, 0.044665),
            Measure.B12, new Line(0.948718, 0.024992),
            Measure.NIR, new Line(1.024582, -0.008366),
            Measure.BLUE, new Line(0.800604, 0.016166)),
            "built-in fit, Kokshetau 2025 (2,801 pairs)");

    private final Map<Measure, Line> lines;
    private final String source;

    private LandsatAdjustment(Map<Measure, Line> lines, String source) {
        this.lines = new EnumMap<>(lines);
        this.source = source;
    }

    /** Reads a fit file with the header {@code measure,slope,intercept,...}; every measure must be there. */
    public static LandsatAdjustment read(Path file) throws IOException {
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.getFirst().startsWith("measure,slope,intercept")) {
            throw new IOException(file + " is not a Landsat fit file: it should start with measure,slope,intercept");
        }
        Map<Measure, Line> lines = new EnumMap<>(Measure.class);
        for (String row : rows.subList(1, rows.size())) {
            if (row.isBlank()) {
                continue;
            }
            String[] cells = row.split(",", -1);
            for (Measure measure : Measure.values()) {
                if (measure.column().equals(cells[0])) {
                    double slope = Double.parseDouble(cells[1]);
                    if (!(slope > 0)) {
                        throw new IOException(file + " gives " + cells[0] + " a slope of " + cells[1]
                                + "; a Landsat line must rise");
                    }
                    lines.put(measure, new Line(slope, Double.parseDouble(cells[2])));
                }
            }
        }
        for (Measure measure : Measure.values()) {
            if (!lines.containsKey(measure)) {
                throw new IOException(file + " has no line for " + measure.column());
            }
        }
        return new LandsatAdjustment(lines, file.toString());
    }

    public double apply(Measure measure, double landsat) {
        Line line = lines.get(measure);
        return line.slope() * landsat + line.intercept();
    }

    /** Where the lines come from, for the series summary. */
    public String source() {
        return source;
    }

    /** One line per measure, for the series summary. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (Measure measure : Measure.values()) {
            Line line = lines.get(measure);
            text.append(text.isEmpty() ? "" : "; ").append(String.format(Locale.ROOT, "%s %.4f x %+.4f",
                    measure.column(), line.slope(), line.intercept()));
        }
        return text.toString();
    }
}
