package kz.kazagrosat.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed rules this experiment judges every field by. The same rule runs on crops and controls,
 * so a signal that also fires on the controls cannot pass unnoticed.
 */
final class Analysis {

    /** One point of a signal over time. */
    record Point(LocalDate date, double value) {}

    /** The biggest change in a signal, between two neighbouring usable dates. */
    record Step(LocalDate before, LocalDate after, double size) {

        boolean overlaps(LocalDate from, LocalDate to) {
            return before.isBefore(to) && after.isAfter(from);
        }
    }

    /** What the photos show for one field, as read by eye and kept in photo-readings.csv. */
    record Reading(String field, String label, LocalDate from, LocalDate to, String note) {

        boolean hasWindow() {
            return from != null;
        }
    }

    /** Dates this clear or better are trusted. Thin cloud still gets through, hence the medians below. */
    static final double MIN_CLEAR = 0.85;

    /** A field counts as ripe once NDVI has fallen below this. Before that, SWIR just tracks drying. */
    static final double RIPE_NDVI = 0.4;

    /** How many dates on each side of a split are compared. Medians stop one hazy date faking a jump. */
    private static final int SIDE = 3;

    /** A split needs this many dates after it, so a single bad last date cannot win. */
    private static final int MIN_AFTER = 2;

    /** SWIR rise that counts as a jump, in reflectance percent. Set after seeing the data. */
    static final double SWIR_JUMP = 3.5;

    private static final LocalDate RADAR_FROM = LocalDate.of(2025, 8, 1);

    private Analysis() {}

    static List<OpticalRow> loadOptical(Path file) throws IOException {
        List<OpticalRow> rows = new ArrayList<>();
        for (String line : data(file)) {
            String[] p = line.split(",");
            rows.add(new OpticalRow(p[0], LocalDate.parse(p[1]), Double.parseDouble(p[3]), Double.parseDouble(p[5]),
                    Double.parseDouble(p[6]), Double.parseDouble(p[11])));
        }
        return rows;
    }

    static List<RadarRow> loadRadar(Path file) throws IOException {
        List<RadarRow> rows = new ArrayList<>();
        for (String line : data(file)) {
            String[] p = line.split(",");
            rows.add(new RadarRow(p[0], LocalDate.parse(p[1]), Integer.parseInt(p[3]),
                    Double.parseDouble(p[4]), Double.parseDouble(p[5])));
        }
        return rows;
    }

    static Map<String, Reading> loadReadings(Path file) throws IOException {
        Map<String, Reading> readings = new LinkedHashMap<>();
        for (String line : data(file)) {
            String[] p = line.split(",", -1);
            readings.put(p[0], new Reading(p[0], p[1], p[2].isEmpty() ? null : LocalDate.parse(p[2]),
                    p[3].isEmpty() ? null : LocalDate.parse(p[3]), p[4]));
        }
        return readings;
    }

    private static List<String> data(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        return lines.subList(1, lines.size());
    }

    record OpticalRow(String field, LocalDate date, double clear, double ndvi, double ndti, double swir16) {}

    record RadarRow(String field, LocalDate date, int track, double vv, double vh) {

        double gap() {
            return vh - vv;
        }
    }

    /** The clear dates of one field, oldest first. */
    static List<OpticalRow> clearRows(List<OpticalRow> rows, String field) {
        return rows.stream()
                .filter(row -> row.field().equals(field) && row.clear() >= MIN_CLEAR && !Double.isNaN(row.ndvi()))
                .toList();
    }

    /** The clear dates from the first one where the crop looks ripe. Spring bare soil does not count. */
    static List<OpticalRow> ripeRows(List<OpticalRow> clear) {
        List<OpticalRow> ripe = new ArrayList<>();
        boolean ripened = false;
        for (OpticalRow row : clear) {
            ripened |= row.date().getMonthValue() >= 8 && row.ndvi() < RIPE_NDVI;
            if (ripened) {
                ripe.add(row);
            }
        }
        return ripe;
    }

    /** The biggest rise in SWIR brightness after the crop has ripened, in reflectance percent. */
    static Optional<Step> swirJump(List<OpticalRow> clear) {
        return biggestStep(ripeRows(clear).stream().map(row -> new Point(row.date(), row.swir16() * 100)).toList(), 1);
    }

    /** The biggest fall in the VH minus VV gap, in dB. Reported as a positive size. */
    static Optional<Step> radarDrop(List<RadarRow> rows) {
        List<Point> falling = rows.stream()
                .filter(row -> !row.date().isBefore(RADAR_FROM))
                .map(row -> new Point(row.date(), -row.gap()))
                .toList();
        return biggestStep(falling, 1);
    }

    /** How much a signal changed from the dates before a window to the dates after it. */
    static double changeAcross(List<Point> series, LocalDate from, LocalDate to) {
        double[] before = series.stream().filter(p -> !p.date().isAfter(from))
                .mapToDouble(Point::value).toArray();
        double[] after = series.stream().filter(p -> !p.date().isBefore(to))
                .mapToDouble(Point::value).toArray();
        if (before.length == 0 || after.length == 0) {
            return Double.NaN;
        }
        return median(Arrays.copyOfRange(after, 0, Math.min(SIDE, after.length)))
                - median(Arrays.copyOfRange(before, Math.max(0, before.length - SIDE), before.length));
    }

    private static Optional<Step> biggestStep(List<Point> series, int minBefore) {
        Step best = null;
        for (int split = minBefore; split <= series.size() - MIN_AFTER; split++) {
            double before = median(values(series, Math.max(0, split - SIDE), split));
            double after = median(values(series, split, Math.min(series.size(), split + SIDE)));
            double size = after - before;
            if (best == null || size > best.size()) {
                best = new Step(series.get(split - 1).date(), series.get(split).date(), size);
            }
        }
        return Optional.ofNullable(best);
    }

    private static double[] values(List<Point> series, int from, int to) {
        return series.subList(from, to).stream().mapToDouble(Point::value).toArray();
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
