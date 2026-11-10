package kz.kazagrosat.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares candidate areas on our own step 2 and step 3 outputs: how much of the area the field maps call
 * field, how big the fields are, and the typical field's longest wait for a clear photo at harvest.
 * Throwaway; run from the repository root:
 * {@code java -cp experiment/target/harvest-signal.jar kz.kazagrosat.experiment.AreaCheck 2025
 * Ours=data/series-2025 N1=data/areas/N1/series-2025}, each folder a {@code series} command's output.
 */
public final class AreaCheck {

    record Land(double fieldShare, double fallowShare, int fields, double medianFieldHa, double largestFieldHa,
            double[] fieldHa) {

        /** The share of field area that lies in fields larger than {@code hectares}. */
        double areaInFieldsOver(double hectares) {
            double total = Arrays.stream(fieldHa).sum();
            return Arrays.stream(fieldHa).filter(ha -> ha > hectares).sum() / total;
        }
    }

    private AreaCheck() {}

    /** Reads a fields table (id, land, area_ha, ...). */
    static Land land(List<String> lines) {
        double total = 0;
        double fallow = 0;
        List<Double> fields = new ArrayList<>();
        List<String> header = List.of(lines.get(0).split(","));
        int landColumn = header.indexOf("land");
        int areaColumn = header.indexOf("area_ha");
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            double ha = Double.parseDouble(cells[areaColumn]);
            total += ha;
            switch (cells[landColumn]) {
                case "field" -> fields.add(ha);
                case "fallow" -> fallow += ha;
                default -> { }
            }
        }
        double[] sizes = fields.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double fieldTotal = Arrays.stream(sizes).sum();
        return new Land(fieldTotal / total, fallow / total, sizes.length, sizes[sizes.length / 2],
                sizes[sizes.length - 1], sizes);
    }

    /** Per field, the longest stretch without a date that has a value; the median over fields. */
    static long harvestGap(List<String> series, LocalDate from, LocalDate to) {
        List<String> header = List.of(series.get(0).split(","));
        int fieldColumn = header.indexOf("field");
        int dateColumn = header.indexOf("date");
        int ndviColumn = header.indexOf("ndvi_median");
        Map<String, List<LocalDate>> clear = new LinkedHashMap<>();
        for (String line : series.subList(1, series.size())) {
            String[] cells = line.split(",", -1);
            List<LocalDate> dates = clear.computeIfAbsent(cells[fieldColumn], f -> new ArrayList<>());
            if (ndviColumn < cells.length && !cells[ndviColumn].isEmpty()) {
                dates.add(LocalDate.parse(cells[dateColumn]));
            }
        }
        long[] gaps = clear.values().stream().mapToLong(dates -> Gaps.longest(dates, from, to)).sorted().toArray();
        return gaps[gaps.length / 2];
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: AreaCheck <year> <name>=<seriesFolder> ...");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        LocalDate from = LocalDate.of(year, 8, 1);
        LocalDate to = LocalDate.of(year, 9, 30);
        System.out.println("| area | field share | fallow | fields | median field | largest | field area in fields over"
                + " 100 ha | longest harvest gap, typical field |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        for (String arg : Arrays.copyOfRange(args, 1, args.length)) {
            String name = arg.substring(0, arg.indexOf('='));
            Path folder = Path.of(arg.substring(arg.indexOf('=') + 1));
            Land land = land(Files.readAllLines(folder.resolve("fields-" + year + ".csv")));
            long gap = harvestGap(Files.readAllLines(folder.resolve("series-" + year + ".csv")), from, to);
            System.out.printf(Locale.ROOT, "| %s | %.0f%% | %.0f%% | %d | %.0f ha | %.0f ha | %.0f%% | %d days |%n",
                    name, 100 * land.fieldShare(), 100 * land.fallowShare(), land.fields(), land.medianFieldHa(),
                    land.largestFieldHa(), 100 * land.areaInFieldsOver(100), gap);
        }
    }
}
