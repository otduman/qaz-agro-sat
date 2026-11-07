package kz.kazagrosat.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;

/**
 * Regression check for the season stacks: recomputes every experiment box on every stored date from
 * the files on disk, with the experiment's own measuring code, and compares each row with the
 * experiment's optical.csv. Same pixels and same arithmetic must give the same text.
 *
 * <p>Usage: {@code StackCheck <dataFolder> <experimentOutputFolder>}, reading {@code experiment/runs.csv}.
 * Exits 0 only when no compared row differs.
 */
public final class StackCheck {

    private static final double USEFUL_CLEAR = 0.6;

    private StackCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: StackCheck <dataFolder> <experimentOutputFolder>");
            System.exit(2);
        }
        Path data = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int differing = 0;
        for (Run run : Run.load(Path.of("experiment", "runs.csv"))) {
            differing += check(run, SeasonStack.open(data.resolve("season-" + run.season().year())), output);
        }
        System.out.println(differing == 0 ? "PASS: every compared row matches" : "FAIL: " + differing + " rows differ");
        System.exit(differing == 0 ? 0 : 1);
    }

    private static int check(Run run, SeasonStack stack, Path output) throws IOException, InterruptedException {
        Map<String, String> expected = new HashMap<>();
        Map<LocalDate, Double> bestClearByDate = new TreeMap<>();
        List<String> lines = Files.readAllLines(run.folder(output).resolve("optical.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",");
            expected.put(cells[0] + "," + cells[1], line);
            bestClearByDate.merge(LocalDate.parse(cells[1]), Double.parseDouble(cells[3]), Math::max);
        }

        List<Field> fields = run.fields();
        int width = stack.area().grid().width();
        int maskWidth = stack.area().maskGrid().width();
        int compared = 0;
        List<String> differences = new ArrayList<>();
        Set<LocalDate> stackDates = new TreeSet<>();
        Set<LocalDate> cloudyInStack = new TreeSet<>();
        for (StackEntry entry : stack.entries()) {
            LocalDate date = entry.date();
            stackDates.add(date);
            if (entry.status() == StackEntry.Status.CLOUDY) {
                cloudyInStack.add(date);
                continue;
            }
            int[] classes = stack.raw(date, Layer.SCL);
            boolean[] clear = SclMask.usable(classes);
            float[] red = stack.reflectance(date, Layer.RED);
            float[] green = stack.reflectance(date, Layer.GREEN);
            float[] blue = stack.reflectance(date, Layer.BLUE);
            float[] nir = stack.reflectance(date, Layer.NIR);
            float[] swir16 = stack.reflectance(date, Layer.SWIR16);
            float[] swir22 = stack.reflectance(date, Layer.SWIR22);
            for (Field field : fields) {
                String want = expected.get(field.name() + "," + date);
                if (want == null) {
                    continue;
                }
                OpticalPass.Row row = OpticalPass.measureField(field, entry.scene(), date, 2, maskWidth, width,
                        clear, classes, red, green, blue, nir, swir16, swir22);
                String got = HarvestSignalExperiment.opticalLine(row);
                compared++;
                if (!got.equals(want)) {
                    differences.add("  want " + want + "\n  got  " + got);
                }
            }
        }

        System.out.println(run.name() + " (" + run.season().year() + "): " + compared + " rows compared, "
                + (compared - differences.size()) + " match, " + differences.size() + " differ");
        differences.stream().limit(10).forEach(System.out::println);
        for (LocalDate date : bestClearByDate.keySet()) {
            if (!stackDates.contains(date)) {
                System.out.println(String.format(Locale.ROOT,
                        "  experiment date %s is not in the stack; best box clear share that day %.3f%s",
                        date, bestClearByDate.get(date),
                        bestClearByDate.get(date) >= USEFUL_CLEAR ? "  <-- a usable box-date was lost" : ""));
            }
        }
        for (LocalDate date : cloudyInStack) {
            Double best = bestClearByDate.get(date);
            if (best != null && best >= USEFUL_CLEAR) {
                System.out.println(String.format(Locale.ROOT,
                        "  %s was skipped as cloudy but a box was %.3f clear  <-- a usable box-date was lost",
                        date, best));
            }
        }
        for (LocalDate date : stackDates) {
            if (!bestClearByDate.containsKey(date)) {
                System.out.println("  stack date " + date + " is not in the experiment");
            }
        }
        return differences.size();
    }
}
