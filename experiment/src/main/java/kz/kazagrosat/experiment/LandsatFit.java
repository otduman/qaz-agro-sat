package kz.kazagrosat.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fits, per measure, a line that puts Landsat field readings on our Sentinel-2 scale, on one season of one area, and
 * checks it on others. Pairs are the same field at most a day apart, as in the Landsat test. Run from the repository
 * root after {@code LandsatCheck} has written each season's Landsat series:
 * {@code LandsatFit data/kokshetau:2025 data/kokshetau:2024 data:2025 ...}; the first is the season fitted on.
 */
public final class LandsatFit {

    static final List<String> MEASURES = List.of("ndvi_median", "b11", "b12", "nir", "blue");
    static final int MAX_DAYS_APART = 1;

    private LandsatFit() {}

    /** Per measure, pairs of {landsat, sentinel} for the same field at most a day apart. */
    static Map<String, double[][]> pairs(List<String> landsatCsv, List<String> sentinelCsv) {
        Map<String, Map<String, Map<LocalDate, Double>>> landsat = byFieldAndMeasure(landsatCsv);
        Map<String, Map<String, Map<LocalDate, Double>>> sentinel = byFieldAndMeasure(sentinelCsv);
        Map<String, double[][]> pairs = new LinkedHashMap<>();
        for (String measure : MEASURES) {
            List<double[]> found = new ArrayList<>();
            for (Map.Entry<String, Map<String, Map<LocalDate, Double>>> field : landsat.entrySet()) {
                Map<LocalDate, Double> l = field.getValue().getOrDefault(measure, Map.of());
                Map<LocalDate, Double> s = sentinel.getOrDefault(field.getKey(), Map.of()).getOrDefault(measure, Map.of());
                found.addAll(Agreement.pairs(l, s, MAX_DAYS_APART));
            }
            pairs.put(measure, found.toArray(double[][]::new));
        }
        return pairs;
    }

    /** field -> measure -> date -> value, leaving out blank cells. */
    private static Map<String, Map<String, Map<LocalDate, Double>>> byFieldAndMeasure(List<String> csv) {
        List<String> header = List.of(csv.get(0).split(","));
        Map<String, Map<String, Map<LocalDate, Double>>> values = new HashMap<>();
        for (String line : csv.subList(1, csv.size())) {
            String[] cells = line.split(",", -1);
            for (String measure : MEASURES) {
                int column = header.indexOf(measure);
                if (column >= 0 && column < cells.length && !cells[column].isEmpty()) {
                    values.computeIfAbsent(cells[0], k -> new HashMap<>())
                            .computeIfAbsent(measure, k -> new TreeMap<>())
                            .put(LocalDate.parse(cells[1]), Double.parseDouble(cells[column]));
                }
            }
        }
        return values;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LandsatFit <folder>:<year> [<folder>:<year> ...] (the first is fitted on)");
            System.exit(2);
        }
        Map<String, Map<String, double[][]>> seasons = new LinkedHashMap<>();
        for (String arg : args) {
            String folder = arg.substring(0, arg.lastIndexOf(':'));
            String year = arg.substring(arg.lastIndexOf(':') + 1);
            Path data = Path.of(folder);
            List<String> landsat = Files.readAllLines(data.resolve("landsat-" + year).resolve("landsat-series-" + year + ".csv"));
            List<String> sentinel = Files.readAllLines(data.resolve("series-" + year).resolve("series-" + year + ".csv"));
            seasons.put(arg, pairs(landsat, sentinel));
        }
        String fittedOn = args[0];
        Map<String, BandFit> fits = new LinkedHashMap<>();
        for (String measure : MEASURES) {
            double[][] p = seasons.get(fittedOn).get(measure);
            fits.put(measure, BandFit.of(column(p, 0), column(p, 1)));
        }

        List<String> lines = new ArrayList<>();
        lines.add("measure,slope,intercept,pairs,fitted_on");
        System.out.println("Fitted on " + fittedOn + ": sentinel = slope x landsat + intercept");
        System.out.println("| measure | slope | intercept | pairs |");
        System.out.println("|---|---|---|---|");
        for (Map.Entry<String, BandFit> fit : fits.entrySet()) {
            BandFit f = fit.getValue();
            System.out.printf(Locale.ROOT, "| %s | %.4f | %+.4f | %d |%n", fit.getKey(), f.slope(), f.intercept(), f.pairs());
            lines.add(String.format(Locale.ROOT, "%s,%.6f,%.6f,%d,%s", fit.getKey(), f.slope(), f.intercept(), f.pairs(),
                    fittedOn));
        }
        Files.write(Path.of(fittedOn.substring(0, fittedOn.lastIndexOf(':'))).resolve("landsat-fit.csv"), lines);

        System.out.println();
        System.out.println("Landsat minus Sentinel-2, median (and median absolute) difference, before -> after the fit;"
                + " and each season's own line");
        System.out.println("| season | measure | pairs | median before | median after | abs before | abs after | own slope | own intercept |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");
        for (Map.Entry<String, Map<String, double[][]>> season : seasons.entrySet()) {
            for (String measure : MEASURES) {
                double[][] p = season.getValue().get(measure);
                double[] landsat = column(p, 0);
                double[] sentinel = column(p, 1);
                double[] adjusted = new double[landsat.length];
                for (int i = 0; i < landsat.length; i++) {
                    adjusted[i] = fits.get(measure).apply(landsat[i]);
                }
                Agreement.Stats before = Agreement.of(landsat, sentinel);
                Agreement.Stats after = Agreement.of(adjusted, sentinel);
                String own = "-";
                if (landsat.length >= 2) {
                    BandFit f = BandFit.of(landsat, sentinel);
                    own = String.format(Locale.ROOT, "%.3f | %+.4f", f.slope(), f.intercept());
                } else {
                    own = "- | -";
                }
                System.out.printf(Locale.ROOT, "| %s%s | %s | %d | %+.4f | %+.4f | %.4f | %.4f | %s |%n", season.getKey(),
                        season.getKey().equals(fittedOn) ? " (fitted)" : "", measure, landsat.length, before.bias(),
                        after.bias(), before.medianAbs(), after.medianAbs(), own);
            }
        }
    }

    private static double[] column(double[][] pairs, int index) {
        double[] values = new double[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            values[i] = pairs[i][index];
        }
        return values;
    }
}
