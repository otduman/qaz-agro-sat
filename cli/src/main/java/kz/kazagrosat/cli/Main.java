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
import kz.kazagrosat.pipeline.series.Series;
import kz.kazagrosat.pipeline.series.SeriesBuilder;
import kz.kazagrosat.pipeline.series.SeriesField;
import kz.kazagrosat.pipeline.series.SeriesOutputs;
import kz.kazagrosat.pipeline.stac.SceneSelector;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;
import kz.kazagrosat.core.geo.DistrictPlan;
import kz.kazagrosat.pipeline.district.District;
import kz.kazagrosat.pipeline.district.DistrictJoin;
import kz.kazagrosat.pipeline.district.DistrictRunner;
import kz.kazagrosat.pipeline.harvest.Harvest;
import kz.kazagrosat.pipeline.harvest.HarvestReport;
import kz.kazagrosat.pipeline.landsat.LandsatAdjustment;
import kz.kazagrosat.pipeline.landsat.LandsatDownloader;
import kz.kazagrosat.pipeline.landsat.LandsatFolder;
import kz.kazagrosat.pipeline.landsat.LandsatReport;
import kz.kazagrosat.pipeline.stack.ContactSheet;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.SeasonStackBuilder;
import kz.kazagrosat.pipeline.stack.StackEntry;
import kz.kazagrosat.pipeline.stack.StackReport;

/** Command line entry point. Exit codes: 0 success, 1 the work failed, 2 the command was wrong. */
public final class Main {

    private static final String USAGE = """
            KazAgroSat: Sentinel-2 maps, season stacks, field boundaries and field series for a small area

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

              landsat --stack FOLDER --out FOLDER
                  Downloads the Landsat 8 and 9 scenes of a stack's area and season from
                  Planetary Computer (no account needed) into FOLDER, for the series
                  command. Run it again to finish an interrupted download.

              series --stack FOLDER --fields FOLDER --out FOLDER
                     [--landsat FOLDER [--landsat-fit FILE]]
                  Reads every field the fields command found on every date of the same
                  stack, and writes one row per field and date as CSV, with a field list
                  and a summary. With --landsat, every Landsat day of the landsat
                  command's FOLDER adds rows too, put on the Sentinel-2 scale by the
                  built-in fit, or by FILE.

              harvest --series FOLDER --fields FOLDER --out FOLDER
                  Dates each field's cut from a series made with --landsat (or without),
                  with a cut window and a status, leaving out photos with snow, frost or
                  haze over the whole area. Writes a CSV, a day-of-year GeoTIFF, a map
                  and a summary with checks.

              district plan --boundary FILE --out FOLDER
                  Splits the district outlined in a GeoJSON file into squares of about
                  20 x 20 km, each inside one Sentinel-2 tile, overlapping by 1 km.

              district run --district FOLDER --year YYYY [--squares ID,ID]
                  Runs season, fields, landsat, series and harvest for every square (or
                  the ones listed), one at a time, skipping squares already done. Logs
                  time and disk per step in FOLDER/run-YYYY.csv.

              district join --district FOLDER --year YYYY
                  Joins the squares done so far into one field table and a summary with
                  each square's checks, counting every field once.

            The scenes, ndvi, season and landsat commands accept --stac-url URL to use
            a different STAC API; landsat also takes --token-url URL.
            """;

    private static final String STAC_URL = "--stac-url";
    private static final String TOKEN_URL = "--token-url";
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
        if (args[0].equals("district")) {
            return district(args);
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
            case "landsat" -> {
                allowOnly(options, Set.of("--stack", "--out", STAC_URL, TOKEN_URL));
                Path stack = Path.of(require(options, "--stack"));
                Path folder = Path.of(require(options, "--out"));
                if (stack.toAbsolutePath().normalize().equals(folder.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("--out " + folder + " is the stack itself. Give the Landsat"
                            + " scenes a folder of their own.");
                }
                URI landsatStac = options.containsKey(STAC_URL)
                        ? URI.create(options.get(STAC_URL)) : LandsatDownloader.PLANETARY_STAC;
                URI tokenUri = options.containsKey(TOKEN_URL)
                        ? URI.create(options.get(TOKEN_URL)) : LandsatDownloader.PLANETARY_TOKEN;
                yield (out, err) -> landsat(stack, folder, landsatStac, tokenUri, out, err);
            }
            case "series" -> {
                allowOnly(options, Set.of("--stack", "--fields", "--out", "--landsat", "--landsat-fit"));
                Path stack = Path.of(require(options, "--stack"));
                Path fields = Path.of(require(options, "--fields"));
                Path folder = Path.of(require(options, "--out"));
                if (options.containsKey("--landsat-fit") && !options.containsKey("--landsat")) {
                    throw new IllegalArgumentException("--landsat-fit needs --landsat");
                }
                Path landsat = options.containsKey("--landsat") ? Path.of(options.get("--landsat")) : null;
                Path fit = options.containsKey("--landsat-fit") ? Path.of(options.get("--landsat-fit")) : null;
                yield (out, err) -> series(stack, fields, landsat, fit, folder, out, err);
            }
            case "harvest" -> {
                allowOnly(options, Set.of("--series", "--fields", "--out"));
                Path seriesFolder = Path.of(require(options, "--series"));
                Path fields = Path.of(require(options, "--fields"));
                Path folder = Path.of(require(options, "--out"));
                yield (out, err) -> harvest(seriesFolder, fields, folder, out, err);
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
    }

    private static Command district(String[] args) {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new IllegalArgumentException("district needs plan, run or join, such as: district plan --boundary FILE"
                    + " --out FOLDER");
        }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        Map<String, String> options = parseOptions(rest);
        return switch (args[1]) {
            case "plan" -> {
                allowOnly(options, Set.of("--boundary", "--out"));
                Path boundary = Path.of(require(options, "--boundary"));
                Path folder = Path.of(require(options, "--out"));
                yield (out, err) -> {
                    List<DistrictPlan.Square> squares = District.plan(boundary, folder);
                    double inside = squares.stream().mapToDouble(DistrictPlan.Square::shareInside).sum();
                    out.println(text("Squares: %d of about 20 x 20 km (%.1f squares' worth inside the district)",
                            squares.size(), inside));
                    out.println("Wrote:   " + folder.resolve(District.PLAN));
                    return 0;
                };
            }
            case "run" -> {
                allowOnly(options, Set.of("--district", "--year", "--squares"));
                Path folder = Path.of(require(options, "--district"));
                int year = parseYear(require(options, "--year"));
                List<String> only = options.containsKey("--squares")
                        ? List.of(options.get("--squares").split(",")) : List.of();
                yield (out, err) -> {
                    DistrictRunner.Report report = DistrictRunner.run(folder, year, only, (stepArgs, log) -> {
                        try (PrintStream stepOut = new PrintStream(java.nio.file.Files.newOutputStream(log), true,
                                java.nio.charset.StandardCharsets.UTF_8)) {
                            return run(stepArgs, stepOut, stepOut);
                        }
                    }, err::println);
                    out.println(text("Squares: %d done, %d already done, %d failed", report.done().size(),
                            report.skipped().size(), report.failed().size()));
                    report.failed().forEach((id, why) -> out.println("  " + id + ": " + why));
                    out.println("Log:     " + folder.resolve("run-" + year + ".csv"));
                    return report.failed().isEmpty() ? 0 : 1;
                };
            }
            case "join" -> {
                allowOnly(options, Set.of("--district", "--year"));
                Path folder = Path.of(require(options, "--district"));
                int year = parseYear(require(options, "--year"));
                yield (out, err) -> {
                    DistrictJoin.Result result = DistrictJoin.join(folder, year);
                    out.println(text("Joined:  %d squares, %d fields", result.squares(), result.fields()));
                    out.println(text("Overlap: %d of %d fields seen by two squares got the same cut window",
                            result.overlapAgree(), result.overlapPairs()));
                    out.println("Wrote:   " + folder.resolve("district-harvest-" + year + ".csv"));
                    out.println("         " + folder.resolve("district-summary-" + year + ".txt"));
                    return 0;
                };
            }
            default -> throw new IllegalArgumentException("Unknown district command: " + args[1]
                    + ". Use plan, run or join.");
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
        boolean sheetFailed = false;
        if (!stack.entries().isEmpty()) {
            err.println("Drawing the contact sheet...");
            try {
                ContactSheet.write(stack, sheet);
            } catch (IOException | RuntimeException e) {
                // The dates are stored either way; say what went wrong and still print the summary.
                err.println("Could not draw the contact sheet " + sheet + ": " + e.getMessage());
                sheetFailed = true;
            }
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
        return sheetFailed ? 1 : 0;
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

    private static int landsat(Path stackFolder, Path folder, URI stacUri, URI tokenUri, PrintStream out,
                               PrintStream err) throws IOException, InterruptedException {
        SeasonStack stack = SeasonStack.open(stackFolder);
        LandsatReport report = new LandsatDownloader(stacUri, tokenUri, RetryPolicy.DEFAULT, err::println)
                .download(stack.area(), folder);
        out.println(text("Scenes:  %d found, %d Landsat 7 left out, %d in another UTM zone left out%s",
                report.found(), report.landsat7(), report.otherZone().size(),
                report.outside() == 0 ? "" : text(", %d not over the area", report.outside())));
        out.println(text("Kept:    %d stored, %d cloudy (bands skipped), %d already stored",
                report.stored(), report.cloudy(), report.alreadyStored()));
        out.println(text("Size:    %.2f GB in %s", sizeOf(folder) / 1e9, folder));
        if (!report.failures().isEmpty()) {
            err.println("Failed scenes:");
            report.failures().forEach(line -> err.println("  " + line));
            err.println("Run the same command again to retry them.");
            return 1;
        }
        return 0;
    }

    private static int series(Path stackFolder, Path fieldsFolder, Path landsatFolder, Path fitFile, Path folder,
                              PrintStream out, PrintStream err) throws IOException, InterruptedException {
        for (Path input : List.of(stackFolder, fieldsFolder)) {
            if (sameFolder(input, folder)) {
                throw new IllegalArgumentException("--out " + folder + " would overwrite files in " + input
                        + ", such as its summary. Give the series a folder of its own.");
            }
        }
        SeriesBuilder.LandsatInput landsat = null;
        if (landsatFolder != null) {
            if (sameFolder(landsatFolder, folder)) {
                throw new IllegalArgumentException("--out " + folder + " would overwrite files in " + landsatFolder
                        + ". Give the series a folder of its own.");
            }
            LandsatAdjustment fit = fitFile == null ? LandsatAdjustment.DEFAULT : LandsatAdjustment.read(fitFile);
            landsat = new SeriesBuilder.LandsatInput(LandsatFolder.open(landsatFolder), fit);
        }
        Series series = SeriesBuilder.build(SeasonStack.open(stackFolder), fieldsFolder, landsat, err::println);
        List<Path> written = SeriesOutputs.write(series, folder);

        List<SeriesField> inSeries = series.fields().stream().filter(SeriesField::inSeries).toList();
        long fields = inSeries.stream().filter(f -> f.land() == LandLabel.FIELD).count();
        long noInterior = inSeries.stream().filter(f -> f.interiorPixels() == 0).count();
        long blank = series.rows().stream().filter(r -> !r.hasValues()).count();
        out.println(text("Season:  %d, %d dates", series.year(), series.dates().size()));
        out.println(text("Fields:  %d in the series (%d field, %d fallow), %d other listed only, "
                + "%d with nothing left after the %.0f m shrink", inSeries.size(), fields, inSeries.size() - fields,
                series.fields().size() - inSeries.size(), noInterior, SeriesBuilder.INTERIOR_METRES));
        out.println(text("Rows:    %d, %d with blank values", series.rows().size(), blank));
        if (landsat != null) {
            out.println(text("Landsat: %d satellite-days, adjusted by the %s", series.landsatDays().size(),
                    series.landsatSource()));
        }
        for (int i = 0; i < written.size(); i++) {
            out.println((i == 0 ? "Wrote:   " : "         ") + written.get(i));
        }
        return 0;
    }

    private static int harvest(Path seriesFolder, Path fieldsFolder, Path folder, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        for (Path input : List.of(seriesFolder, fieldsFolder)) {
            if (sameFolder(input, folder)) {
                throw new IllegalArgumentException("--out " + folder + " would overwrite files in " + input
                        + ", such as its summary. Give the harvest dates a folder of their own.");
            }
        }
        HarvestReport report = Harvest.run(seriesFolder, fieldsFolder, folder, err::println);
        out.println(text("Season:  %d, %d fields: %d dated, %d cut in a cloud gap, %d no cut found, %d unclear, "
                + "%d no crop seen", report.year(), report.fields(), report.dated(), report.cloudGap(), report.noCut(),
                report.unclear(), report.noCrop()));
        out.println(text("Screened: %d dates (snow, frost or haze over the whole area)", report.screenedDates()));
        for (int i = 0; i < report.written().size(); i++) {
            out.println((i == 0 ? "Wrote:   " : "         ") + report.written().get(i));
        }
        return 0;
    }

    /** Whether two paths name the same folder, following links where the folders exist. */
    private static boolean sameFolder(Path a, Path b) throws IOException {
        Path left = Files.exists(a) ? a.toRealPath() : a.toAbsolutePath().normalize();
        Path right = Files.exists(b) ? b.toRealPath() : b.toAbsolutePath().normalize();
        return left.equals(right);
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
