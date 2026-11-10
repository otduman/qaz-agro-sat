package kz.kazagrosat.experiment;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.stack.ContactSheet;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;

/**
 * The harvest-signal test at field level, for an area with step 2 and step 3 outputs: picks fields by a fixed
 * rule, lays out each field's photos for reading by eye, and scores the frozen rule ({@link Analysis#swirJump})
 * against those readings. Throwaway. Run from the repository root:
 *
 * <pre>
 * FieldStudy pick   &lt;dataFolder&gt; &lt;year&gt; &lt;picks.csv&gt;
 * FieldStudy strips &lt;dataFolder&gt; &lt;year&gt; &lt;picks.csv&gt; &lt;outFolder&gt;
 * FieldStudy score  &lt;dataFolder&gt; &lt;year&gt; &lt;readings.csv&gt; [&lt;landsatFit.csv&gt;]
 * FieldStudy date   &lt;dataFolder&gt; &lt;year&gt; &lt;out.csv&gt;
 * </pre>
 *
 * {@code date} runs the frozen rule on every field of {@code series-landsat-<year>}: Sentinel-2 rows, plus Landsat
 * rows (already on the Sentinel-2 scale) on dates Sentinel-2 has no row for.
 *
 * The rule is the experiment's, unchanged; its input differs in one way: a field's reading is the median over its
 * interior (step 3), where the experiment used the mean of a 60-pixel box.
 */
public final class FieldStudy {

    static final double MIN_CROP_HA = 30;
    static final double MAX_CROP_HA = 400;
    static final int CROPS = 16;
    static final int CONTROLS = 4;
    static final LocalDate NO_DATE = null;

    private static final int TILE = 150;
    private static final int GAP = 5;
    private static final int LABEL = 16;
    private static final int PER_ROW = 8;
    private static final int MARGIN = 12;

    record Pick(String id, String kind) {}

    /**
     * One field's cut as the frozen rule dates it: between {@code before} and {@code after}. Status {@code dated}
     * (a rise of at least {@link Analysis#SWIR_JUMP}), {@code no cut found} (ripe, but no such rise) or
     * {@code never ripe} (too few clear ripe dates to compare); the last two have no dates.
     */
    record FieldDate(String field, String status, LocalDate before, LocalDate after, double rise) {}

    /** Every field of a series CSV, in the order they first appear, dated by the frozen rule. */
    static List<FieldDate> dateAll(List<String> seriesCsv) {
        List<String> header = List.of(seriesCsv.get(0).split(","));
        int clear = header.indexOf("clear_share");
        int ndvi = header.indexOf("ndvi_median");
        int b11 = header.indexOf("b11");
        int satellite = header.indexOf("satellite");
        Map<String, TreeMap<LocalDate, Analysis.OpticalRow>> sentinel = new LinkedHashMap<>();
        Map<String, TreeMap<LocalDate, Analysis.OpticalRow>> landsat = new HashMap<>();
        for (String line : seriesCsv.subList(1, seriesCsv.size())) {
            String[] cells = line.split(",", -1);
            sentinel.computeIfAbsent(cells[0], k -> new TreeMap<>());
            if (cells[ndvi].isEmpty() || cells[b11].isEmpty()) {
                continue;
            }
            boolean isSentinel = satellite < 0 || cells[satellite].isEmpty() || cells[satellite].equals("S2");
            LocalDate date = LocalDate.parse(cells[1]);
            (isSentinel ? sentinel : landsat).computeIfAbsent(cells[0], k -> new TreeMap<>()).put(date,
                    new Analysis.OpticalRow(cells[0], date, Double.parseDouble(cells[clear]),
                            Double.parseDouble(cells[ndvi]), Double.NaN, Double.parseDouble(cells[b11])));
        }
        List<FieldDate> dates = new ArrayList<>();
        sentinel.forEach((field, rows) -> {
            TreeMap<LocalDate, Analysis.OpticalRow> merged = new TreeMap<>(landsat.getOrDefault(field, new TreeMap<>()));
            merged.putAll(rows);
            List<Analysis.OpticalRow> clearRows = merged.values().stream()
                    .filter(row -> row.clear() >= Analysis.MIN_CLEAR).toList();
            Optional<Analysis.Step> jump = Analysis.swirJump(clearRows);
            if (jump.isEmpty()) {
                dates.add(new FieldDate(field, "never ripe", null, null, Double.NaN));
            } else if (jump.get().size() < Analysis.SWIR_JUMP) {
                dates.add(new FieldDate(field, "no cut found", null, null, jump.get().size()));
            } else {
                dates.add(new FieldDate(field, "dated", jump.get().before(), jump.get().after(), jump.get().size()));
            }
        });
        return dates;
    }

    private FieldStudy() {}

    /** Crop fields of 30 to 400 ha and fallow controls, all in the series, shuffled by the seed. */
    static List<Pick> pick(List<String> fieldsCsv, long seed, int crops, int controls) {
        List<String> header = List.of(fieldsCsv.get(0).split(","));
        int land = header.indexOf("land");
        int area = header.indexOf("area_ha");
        int inSeries = header.indexOf("in_series");
        List<String> cropIds = new ArrayList<>();
        List<String> controlIds = new ArrayList<>();
        for (String line : fieldsCsv.subList(1, fieldsCsv.size())) {
            String[] cells = line.split(",", -1);
            if (!cells[inSeries].equals("yes")) {
                continue;
            }
            double ha = Double.parseDouble(cells[area]);
            if (cells[land].equals("field") && ha >= MIN_CROP_HA && ha <= MAX_CROP_HA) {
                cropIds.add(cells[0]);
            } else if (cells[land].equals("fallow")) {
                controlIds.add(cells[0]);
            }
        }
        Random random = new Random(seed);
        Collections.sort(cropIds);
        Collections.sort(controlIds);
        Collections.shuffle(cropIds, random);
        Collections.shuffle(controlIds, random);
        List<Pick> picks = new ArrayList<>();
        cropIds.stream().limit(crops).sorted().forEach(id -> picks.add(new Pick(id, "crop")));
        controlIds.stream().limit(controls).sorted().forEach(id -> picks.add(new Pick(id, "control")));
        return picks;
    }

    /** One field's series rows that have values, oldest first, as the experiment's rule reads them. */
    static List<Analysis.OpticalRow> rows(List<String> seriesCsv, String field) {
        List<String> header = List.of(seriesCsv.get(0).split(","));
        int clear = header.indexOf("clear_share");
        int ndvi = header.indexOf("ndvi_median");
        int ndti = header.indexOf("ndti");
        int b11 = header.indexOf("b11");
        List<Analysis.OpticalRow> rows = new ArrayList<>();
        for (String line : seriesCsv.subList(1, seriesCsv.size())) {
            String[] cells = line.split(",", -1);
            if (!cells[0].equals(field) || cells[ndvi].isEmpty() || cells[b11].isEmpty()) {
                continue;
            }
            rows.add(new Analysis.OpticalRow(field, LocalDate.parse(cells[1]), Double.parseDouble(cells[clear]),
                    Double.parseDouble(cells[ndvi]), cells[ndti].isEmpty() ? Double.NaN : Double.parseDouble(cells[ndti]),
                    Double.parseDouble(cells[b11])));
        }
        rows.sort(Comparator.comparing(Analysis.OpticalRow::date));
        return rows;
    }

    /** Adds the field's Landsat rows, put on the Sentinel-2 scale, on dates Sentinel-2 has no row for. */
    static List<Analysis.OpticalRow> withLandsat(List<Analysis.OpticalRow> sentinel, List<String> landsatCsv,
            Map<String, BandFit> fits, String field) {
        List<String> header = List.of(landsatCsv.get(0).split(","));
        int clear = header.indexOf("clear_share");
        int ndvi = header.indexOf("ndvi_median");
        int b11 = header.indexOf("b11");
        TreeMap<LocalDate, Analysis.OpticalRow> merged = new TreeMap<>();
        for (String line : landsatCsv.subList(1, landsatCsv.size())) {
            String[] cells = line.split(",", -1);
            if (!cells[0].equals(field) || cells[ndvi].isEmpty() || cells[b11].isEmpty()) {
                continue;
            }
            LocalDate date = LocalDate.parse(cells[1]);
            merged.put(date, new Analysis.OpticalRow(field, date, Double.parseDouble(cells[clear]),
                    fits.get("ndvi_median").apply(Double.parseDouble(cells[ndvi])), Double.NaN,
                    fits.get("b11").apply(Double.parseDouble(cells[b11]))));
        }
        for (Analysis.OpticalRow row : sentinel) {
            merged.put(row.date(), row);
        }
        return List.copyOf(merged.values());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: FieldStudy pick|strips|score|date <dataFolder> <year> ...");
            System.exit(2);
        }
        Path data = Path.of(args[1]);
        int year = Integer.parseInt(args[2]);
        switch (args[0]) {
            case "pick" -> {
                List<Pick> picks = pick(Files.readAllLines(seriesFolder(data, year).resolve("fields-" + year + ".csv")),
                        year, CROPS, CONTROLS);
                List<String> lines = new ArrayList<>(List.of("id,kind"));
                picks.forEach(p -> lines.add(p.id() + "," + p.kind()));
                Files.write(Path.of(args[3]), lines);
                System.out.println(picks.size() + " fields picked");
            }
            case "strips" -> strips(data, year, Path.of(args[3]), Path.of(args[4]));
            case "score" -> score(data, year, Path.of(args[3]), args.length > 4 ? Path.of(args[4]) : null);
            case "date" -> {
                List<FieldDate> dates = dateAll(Files.readAllLines(
                        data.resolve("series-landsat-" + year).resolve("series-" + year + ".csv")));
                List<String> lines = new ArrayList<>(List.of("field,status,before,after,rise"));
                for (FieldDate d : dates) {
                    lines.add(String.format(Locale.ROOT, "%s,%s,%s,%s,%s", d.field(), d.status(),
                            d.before() == null ? "" : d.before(), d.after() == null ? "" : d.after(),
                            Double.isNaN(d.rise()) ? "" : String.format(Locale.ROOT, "%.2f", d.rise())));
                }
                Files.write(Path.of(args[3]), lines);
                System.out.println(dates.size() + " fields, "
                        + dates.stream().filter(d -> d.status().equals("dated")).count() + " dated");
            }
            default -> throw new IllegalArgumentException("Unknown command " + args[0]);
        }
    }

    private static Path seriesFolder(Path data, int year) {
        return data.resolve("series-" + year);
    }

    private static void strips(Path data, int year, Path picksFile, Path out) throws Exception {
        List<String> picks = Files.readAllLines(picksFile);
        List<String> series = Files.readAllLines(seriesFolder(data, year).resolve("series-" + year + ".csv"));
        CogReader reader = CogReader.open(
                new FileByteSource(data.resolve("fields-" + year).resolve("fields-" + year + ".tif")));
        PixelGrid grid = reader.grid();
        int width = grid.width();
        int height = grid.height();
        int[] labels = reader.readWindow(new PixelWindow(0, 0, width, height));
        SeasonStack stack = SeasonStack.open(data.resolve("season-" + year));

        Map<String, int[]> boxOf = new LinkedHashMap<>();
        Map<String, List<LocalDate>> datesOf = new HashMap<>();
        for (String line : picks.subList(1, picks.size())) {
            String id = line.split(",")[0];
            int region = Integer.parseInt(id.substring(id.indexOf("-F") + 2));
            int left = width;
            int top = height;
            int right = -1;
            int bottom = -1;
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == region) {
                    left = Math.min(left, i % width);
                    right = Math.max(right, i % width);
                    top = Math.min(top, i / width);
                    bottom = Math.max(bottom, i / width);
                }
            }
            boxOf.put(id, new int[] {Math.max(0, left - MARGIN), Math.max(0, top - MARGIN),
                Math.min(width - 1, right + MARGIN), Math.min(height - 1, bottom + MARGIN), region});
            datesOf.put(id, rows(series, id).stream().map(Analysis.OpticalRow::date)
                    .filter(d -> !d.isBefore(LocalDate.of(year, 7, 15))).toList());
        }
        Map<String, Map<LocalDate, BufferedImage>> tiles = new HashMap<>();
        List<LocalDate> allDates = datesOf.values().stream().flatMap(List::stream).distinct().sorted().toList();
        for (LocalDate date : allDates) {
            float[] red = stack.reflectance(date, Layer.RED);
            float[] green = stack.reflectance(date, Layer.GREEN);
            float[] blue = stack.reflectance(date, Layer.BLUE);
            for (Map.Entry<String, int[]> entry : boxOf.entrySet()) {
                if (!datesOf.get(entry.getKey()).contains(date)) {
                    continue;
                }
                int[] b = entry.getValue();
                BufferedImage tile = new BufferedImage(b[2] - b[0] + 1, b[3] - b[1] + 1, BufferedImage.TYPE_INT_RGB);
                for (int y = b[1]; y <= b[3]; y++) {
                    for (int x = b[0]; x <= b[2]; x++) {
                        int i = y * width + x;
                        boolean missing = Float.isNaN(red[i]) || Float.isNaN(green[i]) || Float.isNaN(blue[i]);
                        int rgb = missing ? 0 : ContactSheet.stretch(red[i]) << 16
                                | ContactSheet.stretch(green[i]) << 8 | ContactSheet.stretch(blue[i]);
                        // The outline, one pixel wide, just outside the field so the field itself stays untouched.
                        boolean inside = labels[i] == b[4];
                        boolean edge = !inside && (x > 0 && labels[i - 1] == b[4] || x < width - 1 && labels[i + 1] == b[4]
                                || y > 0 && labels[i - width] == b[4] || y < height - 1 && labels[i + width] == b[4]);
                        tile.setRGB(x - b[0], y - b[1], edge ? 0xFFD60A : rgb);
                    }
                }
                tiles.computeIfAbsent(entry.getKey(), k -> new TreeMap<>()).put(date, tile);
            }
        }
        Files.createDirectories(out);
        for (Map.Entry<String, Map<LocalDate, BufferedImage>> field : tiles.entrySet()) {
            writeStrip(out.resolve(field.getKey() + ".jpg"), field.getKey(), field.getValue());
        }
        System.out.println(tiles.size() + " strips written to " + out);
    }

    private static void writeStrip(Path file, String id, Map<LocalDate, BufferedImage> photos) throws IOException {
        int count = photos.size();
        int rows = (count + PER_ROW - 1) / PER_ROW;
        int columns = Math.min(PER_ROW, count);
        int cell = TILE + LABEL;
        BufferedImage strip = new BufferedImage(columns * (TILE + GAP) - GAP, rows * (cell + GAP) - GAP + LABEL,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(Color.BLACK);
        g.drawString(id, 2, 12);
        int i = 0;
        for (Map.Entry<LocalDate, BufferedImage> photo : photos.entrySet()) {
            BufferedImage image = photo.getValue();
            int x = (i % PER_ROW) * (TILE + GAP);
            int y = LABEL + (i / PER_ROW) * (cell + GAP);
            double scale = Math.min((double) TILE / image.getWidth(), (double) TILE / image.getHeight());
            int w = (int) (image.getWidth() * scale);
            int h = (int) (image.getHeight() * scale);
            g.drawImage(image, x + (TILE - w) / 2, y + LABEL + (TILE - h) / 2, w, h, null);
            g.setColor(Color.BLACK);
            g.drawString(photo.getKey().toString().substring(5), x + 2, y + 12);
            i++;
        }
        g.dispose();
        ImageIO.write(strip, "jpg", file.toFile());
    }

    private static void score(Path data, int year, Path readingsFile, Path fitFile) throws IOException {
        Map<String, Analysis.Reading> readings = Analysis.loadReadings(readingsFile);
        List<String> series = Files.readAllLines(seriesFolder(data, year).resolve("series-" + year + ".csv"));
        List<String> landsat = null;
        Map<String, BandFit> fits = new HashMap<>();
        if (fitFile != null) {
            landsat = Files.readAllLines(data.resolve("landsat-" + year).resolve("landsat-series-" + year + ".csv"));
            for (String line : Files.readAllLines(fitFile).subList(1, 6)) {
                String[] cells = line.split(",");
                fits.put(cells[0], new BandFit(Double.parseDouble(cells[1]), Double.parseDouble(cells[2]),
                        Integer.parseInt(cells[3])));
            }
        }
        System.out.println("| field | reading | window | Sentinel-2: jump | result | with Landsat: jump | result |");
        System.out.println("|---|---|---|---|---|---|---|");
        Map<String, int[]> tally = new LinkedHashMap<>();
        for (Analysis.Reading reading : readings.values()) {
            List<Analysis.OpticalRow> rows = rows(series, reading.field());
            String alone = describe(reading, rows);
            String both = landsat == null ? "- | -" : describe(reading, withLandsat(rows, landsat, fits, reading.field()));
            System.out.printf(Locale.ROOT, "| %s | %s | %s | %s | %s |%n", reading.field(), reading.label(),
                    reading.hasWindow() ? reading.from() + " to " + reading.to() : "-", alone, both);
            count(tally, reading.label() + " / Sentinel-2 / " + alone.substring(alone.lastIndexOf('|') + 2));
            if (landsat != null) {
                count(tally, reading.label() + " / with Landsat / " + both.substring(both.lastIndexOf('|') + 2));
            }
        }
        System.out.println();
        tally.forEach((key, value) -> System.out.println(key + ": " + value[0]));
    }

    private static void count(Map<String, int[]> tally, String key) {
        tally.computeIfAbsent(key, k -> new int[1])[0]++;
    }

    private static String describe(Analysis.Reading reading, List<Analysis.OpticalRow> rows) {
        List<Analysis.OpticalRow> clear = rows.stream().filter(row -> row.clear() >= Analysis.MIN_CLEAR).toList();
        Optional<Analysis.Step> jump = Analysis.swirJump(clear);
        String step = jump.map(j -> String.format(Locale.ROOT, "%+.1f, %s to %s", j.size(), j.before(), j.after()))
                .orElse("none");
        return step + " | " + Report.result(reading, jump);
    }
}
