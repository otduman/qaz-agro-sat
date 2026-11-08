package kz.kazagrosat.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import kz.kazagrosat.core.export.GeoTiffWriter;
import kz.kazagrosat.core.export.NdviPng;
import kz.kazagrosat.core.fields.FieldFinder;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.FieldParameters;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.pipeline.NdviPipeline;
import kz.kazagrosat.pipeline.NdviResult;
import kz.kazagrosat.pipeline.fields.FieldOutputs;
import kz.kazagrosat.pipeline.fields.StackImages;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.stac.SceneSelector;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;
import kz.kazagrosat.pipeline.stack.ContactSheet;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.SeasonStackBuilder;
import kz.kazagrosat.pipeline.stack.StackEntry;
import kz.kazagrosat.pipeline.stack.StackReport;

/** Command line entry point. Exit codes: 0 success, 1 the work failed, 2 the command was wrong. */
public final class Main {

    private static final String USAGE = """
            KazAgroSat: Sentinel-2 maps, season stacks and field boundaries for a small area

            Usage:
              scenes --bbox W,S,E,N --from YYYY-MM-DD --to YYYY-MM-DD [--max-cloud PERCENT]
                  Lists the scenes over an area, to help pick a date.

              ndvi --bbox W,S,E,N --date YYYY-MM-DD --out FOLDER
                  Writes a cloud-masked NDVI map as a PNG and a GeoTIFF.

              season --bbox W,S,E,N --year YYYY --out FOLDER
                  Downloads every usable date from May to October into FOLDER, with an
                  index and a contact sheet. Run it again to finish an interrupted download.

              fields --stack FOLDER --out FOLDER
                  Finds the season's fields from a stack made by the season command and
                  writes them as GeoJSON, a label GeoTIFF, outline pictures and a summary.

            The scenes, ndvi and season commands accept --stac-url URL to use a
            different STAC API.
            """;

    private static final String STAC_URL = "--stac-url";
    /** Sentinel-2 started imaging in mid-2015. */
    private static final int FIRST_YEAR = 2015;
    private static final int DOWNLOAD_THREADS = 4;

    @FunctionalInterface
    private interface Command {
        int execute(PrintStream out, PrintStream err) throws IOException, InterruptedException;
    }

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.print(USAGE);
            return 0;
        }
        Command command;
        try {
            command = parse(args);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            err.println("Error: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }
        try {
            return command.execute(out, err);
        } catch (IOException | IllegalArgumentException e) {
            err.println("Failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        }
    }

    private static Command parse(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("No command given");
        }
        Map<String, String> options = parseOptions(args);
        URI stacUri = options.containsKey(STAC_URL) ? URI.create(options.get(STAC_URL)) : StacClient.EARTH_SEARCH;
        return switch (args[0]) {
            case "ndvi" -> {
                allowOnly(options, Set.of("--bbox", "--date", "--out", STAC_URL));
                BoundingBox bbox = BoundingBox.parse(require(options, "--bbox"));
                LocalDate date = LocalDate.parse(require(options, "--date"));
                Path folder = Path.of(require(options, "--out"));
                yield (out, err) -> ndvi(bbox, date, folder, stacUri, out, err);
            }
            case "scenes" -> {
                allowOnly(options, Set.of("--bbox", "--from", "--to", "--max-cloud", STAC_URL));
                BoundingBox bbox = BoundingBox.parse(require(options, "--bbox"));
                LocalDate from = LocalDate.parse(require(options, "--from"));
                LocalDate to = LocalDate.parse(require(options, "--to"));
                if (from.isAfter(to)) {
                    throw new IllegalArgumentException("--from is after --to");
                }
                Double maxCloud = options.containsKey("--max-cloud")
                        ? Double.valueOf(options.get("--max-cloud")) : null;
                StacSearch search = new StacSearch(NdviPipeline.COLLECTION, bbox, from, to, maxCloud);
                yield (out, err) -> scenes(search, stacUri, out);
            }
            case "season" -> {
                allowOnly(options, Set.of("--bbox", "--year", "--out", STAC_URL));
                BoundingBox bbox = BoundingBox.parse(require(options, "--bbox"));
                int year = parseYear(require(options, "--year"));
                Path folder = Path.of(require(options, "--out"));
                yield (out, err) -> season(bbox, year, folder, stacUri, out, err);
            }
            case "fields" -> {
                allowOnly(options, Set.of("--stack", "--out"));
                Path stack = Path.of(require(options, "--stack"));
                Path folder = Path.of(require(options, "--out"));
                yield (out, err) -> fields(stack, folder, out, err);
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected an option but got: " + args[i]);
            }
            if (i + 1 == args.length) {
                throw new IllegalArgumentException("Option " + args[i] + " needs a value");
            }
            options.put(args[i], args[i + 1]);
        }
        return options;
    }

    private static void allowOnly(Map<String, String> options, Set<String> allowed) {
        for (String option : options.keySet()) {
            if (!allowed.contains(option)) {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
    }

    private static int parseYear(String text) {
        int year;
        try {
            year = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--year must be a whole number such as 2025, not " + text);
        }
        if (year < FIRST_YEAR || year > 2100) {
            throw new IllegalArgumentException("--year must be between " + FIRST_YEAR + " and 2100, not " + year);
        }
        return year;
    }

    private static String require(Map<String, String> options, String option) {
        String value = options.get(option);
        if (value == null) {
            throw new IllegalArgumentException("Missing option: " + option);
        }
        return value;
    }

    private static int ndvi(
            BoundingBox bbox, LocalDate date, Path folder, URI stacUri, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        Optional<NdviResult> found = new NdviPipeline(stacUri, RetryPolicy.DEFAULT, err::println).run(bbox, date);
        if (found.isEmpty()) {
            err.println("No single scene on " + date + " covers the whole area."
                    + " Use the scenes command to see which dates do.");
            return 1;
        }
        NdviResult result = found.get();
        Files.createDirectories(folder);
        Path png = folder.resolve(result.scene().id() + "_ndvi.png");
        Path tiff = folder.resolve(result.scene().id() + "_ndvi.tif");
        NdviPng.write(png, result.ndvi(), result.grid().width(), result.grid().height());
        GeoTiffWriter.write(tiff, result.ndvi(), result.grid());

        out.println(text("Scene:  %s (tile cloud cover %.1f%%)", result.scene().id(), result.scene().cloudCover()));
        out.println(text("Grid:   %d x %d pixels at %.0f m, EPSG:%d", result.grid().width(), result.grid().height(),
                result.grid().pixelSize(), result.grid().epsg()));
        out.println(text("Usable: %.1f%% of pixels in this area", result.validFraction() * 100));
        out.println("Wrote:  " + png);
        out.println("Wrote:  " + tiff);
        return 0;
    }

    private static int season(
            BoundingBox bbox, int year, Path folder, URI stacUri, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        StackReport report = new SeasonStackBuilder(stacUri, RetryPolicy.DEFAULT, DOWNLOAD_THREADS, err::println)
                .build(bbox, year, folder);
        SeasonStack stack = SeasonStack.open(folder);
        Path sheet = folder.resolve("contact-sheet.png");
        if (!stack.entries().isEmpty()) {
            err.println("Drawing the contact sheet...");
            ContactSheet.write(stack, sheet);
        }

        out.println("Tile:    " + report.tile());
        out.println(text("Dates:   %d found, %d already stored, %d stored, %d cloudy (bands skipped)",
                report.datesFound(), report.alreadyStored(), report.stored(), report.cloudy()));
        out.println(text("Size:    %.2f GB in %s", sizeOf(folder) / 1e9, folder));
        if (Files.exists(sheet)) {
            out.println("Wrote:   " + sheet);
        }
        if (!report.failures().isEmpty()) {
            err.println("Failed dates:");
            report.failures().forEach(line -> err.println("  " + line));
            err.println("Run the same command again to retry them.");
            return 1;
        }
        return 0;
    }

    private static int fields(Path stackFolder, Path folder, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        SeasonStack stack = SeasonStack.open(stackFolder);
        StackImages images = new StackImages(stack);
        if (images.dates().isEmpty()) {
            throw new IOException(stackFolder + " has no stored dates, so there is nothing to find fields in");
        }
        FieldMap map = FieldFinder.find(images, FieldParameters.DEFAULTS, err::println);
        List<Path> written = FieldOutputs.write(stack, map, folder);

        double hectaresPerPixel = map.grid().pixelSize() * map.grid().pixelSize() / 10_000;
        long cloudy = stack.entries().stream().filter(e -> e.status() == StackEntry.Status.CLOUDY).count();
        out.println(text("Season:  %d, %d dates used, %d cloudy dates skipped",
                stack.area().year(), images.dates().size(), cloudy));
        StringBuilder byLand = new StringBuilder();
        for (LandLabel land : LandLabel.values()) {
            List<Region> regions = map.regions().stream().filter(r -> r.land() == land).toList();
            long pixels = regions.stream().mapToLong(Region::pixels).sum();
            byLand.append(byLand.isEmpty() ? "" : ", ").append(text("%d %s (%,.1f ha)",
                    regions.size(), land.name().toLowerCase(Locale.ROOT), pixels * hectaresPerPixel));
        }
        out.println(text("Fields:  %d regions: %s", map.regions().size(), byLand));
        double dropped = map.summary().droppedPixels() * hectaresPerPixel;
        double noData = map.summary().noDataPixels() * hectaresPerPixel;
        out.println(text("Left out: %,.1f ha (dropped under %.0f ha %,.1f, no clear data %,.1f)",
                dropped + noData, FieldParameters.DEFAULTS.minRegionHectares(), dropped, noData));
        for (int i = 0; i < written.size(); i++) {
            out.println((i == 0 ? "Wrote:   " : "         ") + written.get(i));
        }
        return 0;
    }

    private static long sizeOf(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            long total = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                total += Files.size(file);
            }
            return total;
        }
    }

    private static int scenes(StacSearch search, URI stacUri, PrintStream out)
            throws IOException, InterruptedException {
        List<StacItem> items = new StacClient(stacUri, RetryPolicy.DEFAULT).search(search);
        if (items.isEmpty()) {
            out.println("No scenes found.");
            return 0;
        }
        out.println("Date        Cloud   Coverage  Scene");
        items.stream().sorted(Comparator.comparing(StacItem::datetime)).forEach(item -> out.println(text(
                "%s  %5.1f%%  %-8s  %s",
                item.datetime().atZone(ZoneOffset.UTC).toLocalDate(),
                item.cloudCover(),
                SceneSelector.covers(item, search.bbox()) ? "full" : "partial",
                item.id())));
        return 0;
    }

    /** Formats with fixed conventions, so output reads the same on any machine's locale. */
    private static String text(String format, Object... values) {
        return String.format(Locale.ROOT, format, values);
    }
}
