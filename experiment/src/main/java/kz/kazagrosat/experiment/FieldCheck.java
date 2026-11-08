package kz.kazagrosat.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;

/**
 * Checks each season's field boundaries against the experiment's boxes and against the other seasons.
 * Throwaway, like the rest of this module; run from the repository root:
 * {@code java -cp experiment/target/harvest-signal.jar kz.kazagrosat.experiment.FieldCheck data}.
 *
 * <ul>
 *   <li><b>Boxes.</b> A box read as "two fields in the box" should be crossed by a boundary; any other
 *       reading should not, except "part natural part field", which is listed but not scored. These
 *       readings are the experimenter's own, so this is a consistency check, not ground truth.</li>
 *   <li><b>Stability.</b> The share of one season's boundary pixels within 20 m of another season's,
 *       beside the share a random line would get.</li>
 * </ul>
 */
public final class FieldCheck {

    static final String TWO_FIELDS = "two fields in the box";
    static final String MIXED = "part natural part field";
    static final String NO_READING = "no reading";
    static final int TUNED_ON = 2025;
    static final int[] YEARS = {2025, 2024, 2023};
    static final int TOLERANCE = 2;

    private record Season(int year, PixelGrid grid, int[] labels, List<Field> boxes, Map<String, String> readings) {}

    private FieldCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: FieldCheck <dataFolder>");
            System.exit(2);
        }
        Path data = Path.of(args[0]);
        List<Run> runs = Run.load(Path.of("experiment", "runs.csv"));
        Map<Integer, Season> seasons = new LinkedHashMap<>();
        for (int year : YEARS) {
            Path file = data.resolve("fields-" + year).resolve("fields-" + year + ".tif");
            if (!Files.exists(file)) {
                System.out.println(year + ": no " + file + ", skipped");
                continue;
            }
            seasons.put(year, load(year, file, runs));
        }
        for (Season season : seasons.values()) {
            printBoxes(season);
        }
        Season tuned = seasons.get(TUNED_ON);
        if (tuned != null) {
            System.out.println();
            System.out.println("Stability: share of boundary pixels within " + TOLERANCE * 10
                    + " m of the other season's boundaries (chance level in brackets)");
            for (Season other : seasons.values()) {
                if (other.year() != TUNED_ON) {
                    printStability(tuned, other);
                    printStability(other, tuned);
                }
            }
        }
    }

    private static Season load(int year, Path file, List<Run> runs) throws IOException, InterruptedException {
        CogReader reader = CogReader.open(new FileByteSource(file));
        PixelGrid grid = reader.grid();
        int[] labels = reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
        List<Field> boxes = new ArrayList<>();
        Map<String, String> readings = new LinkedHashMap<>();
        for (Run run : runs) {
            if (run.season().year() != year) {
                continue;
            }
            for (Field box : run.fields()) {
                if (boxes.stream().noneMatch(b -> b.name().equals(box.name()))) {
                    boxes.add(box);
                }
            }
            if (run.photosRead()) {
                List<String> lines = Files.readAllLines(run.readings());
                for (String line : lines.subList(1, lines.size())) {
                    String[] parts = line.split(",", -1);
                    readings.put(parts[0], parts[1]);
                }
            }
        }
        return new Season(year, grid, labels, boxes, readings);
    }

    private static void printBoxes(Season season) {
        System.out.println();
        System.out.println(season.year() + (season.year() == TUNED_ON ? " (seen while tuning)" : " (not seen while tuning)"));
        System.out.println(String.format(Locale.ROOT, "%-5s %-34s %8s %8s %8s %7s",
                "Box", "Reading", "Largest", "Crossed", "Expected", "Agrees"));
        List<String> readings = new ArrayList<>();
        List<Boolean> crossings = new ArrayList<>();
        for (Field box : season.boxes()) {
            String reading = season.readings().getOrDefault(box.name(), NO_READING);
            double share = BoxCrossing.largestShare(season.labels(), season.grid().width(), box);
            boolean crossed = share < BoxCrossing.CROSSED_BELOW;
            readings.add(reading);
            crossings.add(crossed);
            String expected = "-";
            String agrees = "-";
            if (BoxScore.isScored(reading)) {
                boolean shouldCross = reading.equals(TWO_FIELDS);
                expected = shouldCross ? "yes" : "no";
                agrees = crossed == shouldCross ? "yes" : "NO";
            }
            System.out.println(String.format(Locale.ROOT, "%-5s %-34s %8.2f %8s %8s %7s",
                    box.name(), reading, share, crossed ? "yes" : "no", expected, agrees));
        }
        BoxScore score = BoxScore.of(readings, crossings);
        System.out.println(String.format(Locale.ROOT,
                "Agreement: %d of %d scored boxes (two-field boxes crossed: %d of %d; one-field boxes not crossed:"
                        + " %d of %d); %d listed but not scored",
                score.agree(), score.scored(), score.twoFieldCrossed(), score.twoFieldBoxes(),
                score.oneFieldNotCrossed(), score.oneFieldBoxes(), score.unscored()));
        System.out.println(String.format(Locale.ROOT,
                "Baseline: a map with no boundaries at all would agree on %d of %d", score.noBoundaryAgree(),
                score.scored()));
    }

    private static void printStability(Season from, Season to) {
        int width = from.grid().width();
        int height = from.grid().height();
        if (!from.grid().equals(to.grid())) {
            System.out.println(from.year() + " -> " + to.year() + ": the grids differ, skipped");
            return;
        }
        double share = BoundaryStability.share(from.labels(), to.labels(), width, height, TOLERANCE);
        double chance = BoundaryStability.chance(to.labels(), width, height, TOLERANCE);
        System.out.println(String.format(Locale.ROOT, "  %d -> %d: %.1f%% (chance %.1f%%)",
                from.year(), to.year(), share * 100, chance * 100));
    }
}
