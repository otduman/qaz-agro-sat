package kz.kazagrosat.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Tests correcting each Landsat photo by its own offset ({@link PhotoOffset}) after the fixed line of
 * {@link LandsatFit}. Judged on Landsat photos that have a Sentinel-2 photo at most a day away: those Sentinel-2
 * dates (and any within {@code hide} days) are hidden, the offset is measured from the dates further out, and the
 * corrected Landsat reading is compared with the hidden one. Run from the repository root:
 * {@code LandsatPhotoFit <fitCsv> <tolerance> <ndviTolerance> <maxSpanDays> <hideDays> <folder>:<year> ...}.
 */
public final class LandsatPhotoFit {

    static final int MIN_FIELDS = 20;
    static final int MIN_JUDGED = 30;

    private record Photo(LocalDate date, String satellite) {}

    private LandsatPhotoFit() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Usage: LandsatPhotoFit <fitCsv> <tolerance> <ndviTolerance> <maxSpanDays> <hideDays>"
                    + " <folder>:<year> ...");
            System.exit(2);
        }
        Map<String, BandFit> fits = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0])).subList(1, 6)) {
            String[] cells = line.split(",");
            fits.put(cells[0], new BandFit(Double.parseDouble(cells[1]), Double.parseDouble(cells[2]),
                    Integer.parseInt(cells[3])));
        }
        double tolerance = Double.parseDouble(args[1]);
        double ndviTolerance = Double.parseDouble(args[2]);
        int maxSpan = Integer.parseInt(args[3]);
        int hide = Integer.parseInt(args[4]);
        System.out.printf(Locale.ROOT, "Tolerance %.3f (NDVI %.3f), brackets within %d days, Sentinel-2 hidden within"
                + " %d day(s) of each Landsat photo%n", tolerance, ndviTolerance, maxSpan, hide);
        System.out.println("| season | measure | photos judged | corrected | typical photo offset: line only | with photo"
                + " offset | worst: line only | with photo offset | reading abs: line only | with photo offset |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|");
        for (String arg : Arrays.copyOfRange(args, 5, args.length)) {
            String folder = arg.substring(0, arg.lastIndexOf(':'));
            String year = arg.substring(arg.lastIndexOf(':') + 1);
            List<String> landsat = Files.readAllLines(
                    Path.of(folder).resolve("landsat-" + year).resolve("landsat-series-" + year + ".csv"));
            List<String> sentinel = Files.readAllLines(
                    Path.of(folder).resolve("series-" + year).resolve("series-" + year + ".csv"));
            for (String measure : LandsatFit.MEASURES) {
                season(arg, measure, landsat, sentinel, fits.get(measure),
                        measure.equals("ndvi_median") ? ndviTolerance : tolerance, maxSpan, hide);
            }
        }
    }

    private static void season(String name, String measure, List<String> landsatCsv, List<String> sentinelCsv,
            BandFit fit, double tolerance, int maxSpan, int hide) {
        Map<String, TreeMap<LocalDate, Double>> sentinel = new HashMap<>();
        List<String> header = List.of(sentinelCsv.get(0).split(","));
        int column = header.indexOf(measure);
        for (String line : sentinelCsv.subList(1, sentinelCsv.size())) {
            String[] cells = line.split(",", -1);
            if (column < cells.length && !cells[column].isEmpty()) {
                sentinel.computeIfAbsent(cells[0], k -> new TreeMap<>())
                        .put(LocalDate.parse(cells[1]), Double.parseDouble(cells[column]));
            }
        }
        // photo -> field -> line-adjusted Landsat value
        Map<Photo, Map<String, Double>> photos = new LinkedHashMap<>();
        header = List.of(landsatCsv.get(0).split(","));
        column = header.indexOf(measure);
        int satellite = header.indexOf("satellite");
        for (String line : landsatCsv.subList(1, landsatCsv.size())) {
            String[] cells = line.split(",", -1);
            if (column < cells.length && !cells[column].isEmpty()) {
                photos.computeIfAbsent(new Photo(LocalDate.parse(cells[1]), cells[satellite]), k -> new LinkedHashMap<>())
                        .put(cells[0], fit.apply(Double.parseDouble(cells[column])));
            }
        }

        List<Double> lineOnly = new ArrayList<>();
        List<Double> withOffset = new ArrayList<>();
        List<Double> absLine = new ArrayList<>();
        List<Double> absOffset = new ArrayList<>();
        int judged = 0;
        for (Map.Entry<Photo, Map<String, Double>> photo : photos.entrySet()) {
            LocalDate date = photo.getKey().date();
            List<double[]> brackets = new ArrayList<>();
            List<double[]> truths = new ArrayList<>();
            for (Map.Entry<String, Double> field : photo.getValue().entrySet()) {
                TreeMap<LocalDate, Double> series = sentinel.get(field.getKey());
                if (series == null) {
                    continue;
                }
                Optional<PhotoOffset.Bracket> bracket = PhotoOffset.bracket(series, date, hide, maxSpan);
                bracket.ifPresent(b -> brackets.add(new double[] {field.getValue(), b.before(), b.after(), b.position()}));
                nearest(series, date).ifPresent(truth -> truths.add(new double[] {field.getValue(), truth}));
            }
            if (truths.size() < MIN_JUDGED) {
                continue;
            }
            judged++;
            double offset = PhotoOffset.of(column(brackets, 0), column(brackets, 1), column(brackets, 2),
                    column(brackets, 3), tolerance, MIN_FIELDS);
            if (Double.isNaN(offset)) {
                continue;
            }
            double[] line = new double[truths.size()];
            double[] corrected = new double[truths.size()];
            for (int i = 0; i < line.length; i++) {
                line[i] = truths.get(i)[0] - truths.get(i)[1];
                corrected[i] = line[i] - offset;
                absLine.add(Math.abs(line[i]));
                absOffset.add(Math.abs(corrected[i]));
            }
            lineOnly.add(Math.abs(median(line)));
            withOffset.add(Math.abs(median(corrected)));
        }
        System.out.printf(Locale.ROOT, "| %s | %s | %d | %d | %.4f | %.4f | %.4f | %.4f | %.4f | %.4f |%n", name, measure,
                judged, lineOnly.size(), median(lineOnly), median(withOffset), max(lineOnly), max(withOffset),
                median(absLine), median(absOffset));
    }

    /** The Sentinel-2 value at most a day from the date; a tie goes to the earlier date. */
    private static Optional<Double> nearest(TreeMap<LocalDate, Double> series, LocalDate date) {
        for (LocalDate candidate : List.of(date, date.minusDays(1), date.plusDays(1))) {
            if (series.containsKey(candidate)) {
                return Optional.of(series.get(candidate));
            }
        }
        return Optional.empty();
    }

    private static double[] column(List<double[]> rows, int index) {
        return rows.stream().mapToDouble(row -> row[index]).toArray();
    }

    private static double median(List<Double> values) {
        return values.isEmpty() ? Double.NaN : median(values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }
}
