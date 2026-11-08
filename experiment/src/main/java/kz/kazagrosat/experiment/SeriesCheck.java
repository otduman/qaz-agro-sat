package kz.kazagrosat.experiment;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.core.series.FieldInteriors;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.stack.ContactSheet;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;

/**
 * Checks the per-field series against the experiment's boxes, and writes the data the results page
 * draws. Run from the repository root after the {@code series} command:
 * {@code SeriesCheck <dataFolder> [<pageFolder>]}.
 *
 * <p><b>Box check.</b> A box lying wholly inside one field's interior should give that field's NDVI:
 * same pixels, only a different crop. For each such box it prints the median absolute gap between the
 * box's NDVI (the experiment's {@code optical.csv}, dates at least 60% clear) and the field's median
 * NDVI, on the dates both have.
 *
 * <p><b>First look at lines.</b> For each such box read as "cut seen", the field's median line strength
 * on dates before the cut window and after it. This only describes; nothing is decided from it.
 */
public final class SeriesCheck {

    static final int[] YEARS = {2025, 2024, 2023};
    static final double MIN_CLEAR = 0.6;
    static final int SHRINK = (int) Math.round(kz.kazagrosat.pipeline.series.SeriesBuilder.INTERIOR_METRES / 10);
    static final int SCALE = 2;
    static final int MAX_PHOTOS = 25;
    static final float JPEG_QUALITY = 0.82f;

    private record Box(Field field, String reading, LocalDate cutFrom, LocalDate cutTo, Map<LocalDate, Double> ndvi) {}

    private record Row(LocalDate date, double[] values) {}

    private SeriesCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: SeriesCheck <dataFolder> [<pageFolder>]");
            System.exit(2);
        }
        Path data = Path.of(args[0]);
        Path page = args.length == 2 ? Path.of(args[1]) : null;
        List<Run> runs = Run.load(Path.of("experiment", "runs.csv"));
        List<Double> allGaps = new ArrayList<>();
        for (int year : YEARS) {
            Path seriesCsv = data.resolve("series-" + year).resolve("series-" + year + ".csv");
            if (!Files.exists(seriesCsv)) {
                System.out.println(year + ": no " + seriesCsv + ", skipped");
                continue;
            }
            checkSeason(year, data, runs, page, allGaps);
        }
        allGaps.sort(null);
        if (!allGaps.isEmpty()) {
            System.out.printf(Locale.ROOT, "%nAll seasons: %d boxes, median of their median gaps %.4f, largest %.4f%n",
                    allGaps.size(), middle(allGaps), allGaps.get(allGaps.size() - 1));
        }
    }

    private static void checkSeason(int year, Path data, List<Run> runs, Path page, List<Double> allGaps)
            throws Exception {
        Path labelFile = data.resolve("fields-" + year).resolve("fields-" + year + ".tif");
        CogReader reader = CogReader.open(new FileByteSource(labelFile));
        PixelGrid grid = reader.grid();
        int width = grid.width();
        int[] labels = reader.readWindow(new PixelWindow(0, 0, width, grid.height()));
        FieldInteriors interiors = FieldInteriors.of(labels, width, grid.height(), SHRINK);
        int[] interiorLabel = new int[labels.length];
        for (int f = 1; f <= interiors.fieldCount(); f++) {
            for (int i : interiors.pixelsOf(f)) {
                interiorLabel[i] = f;
            }
        }

        List<String> header = new ArrayList<>();
        Map<String, List<Row>> series = readSeries(data.resolve("series-" + year).resolve("series-" + year + ".csv"),
                header);
        List<String[]> fieldList = readCsv(data.resolve("series-" + year).resolve("fields-" + year + ".csv"));
        int median = header.indexOf("ndvi_median");
        int line = header.indexOf("line_strength");
        List<Box> boxes = boxes(year, runs);

        System.out.println();
        System.out.println(year + ": boxes against the field that holds them");
        System.out.println("box   reading                               field       in interior  dates  median gap  largest");
        Map<String, String> fieldOfBox = new LinkedHashMap<>();
        Map<String, Double> gapOfBox = new LinkedHashMap<>();
        List<Double> gaps = new ArrayList<>();
        List<String> lineLook = new ArrayList<>();
        for (Box box : boxes) {
            Map<Integer, Integer> counts = new HashMap<>();
            int total = 0;
            for (int y = box.field().top(); y < box.field().bottom(); y++) {
                for (int x = box.field().left(); x < box.field().right(); x++) {
                    counts.merge(interiorLabel[y * width + x], 1, Integer::sum);
                    total++;
                }
            }
            Map.Entry<Integer, Integer> best = counts.entrySet().stream()
                    .filter(e -> e.getKey() != 0).max(Map.Entry.comparingByValue()).orElse(null);
            double share = best == null ? 0 : (double) best.getValue() / total;
            String id = best == null ? "-" : String.format(Locale.ROOT, "%d-F%04d", year, best.getKey());
            List<Row> rows = series.get(id);
            String gapText = "-";
            String datesText = "-";
            String largestText = "-";
            if (share == 1.0 && rows != null) {
                Map<LocalDate, Double> fieldNdvi = new TreeMap<>();
                for (Row row : rows) {
                    fieldNdvi.put(row.date(), row.values()[median]);
                }
                CurveGap.Gap gap = CurveGap.of(box.ndvi(), fieldNdvi);
                datesText = Integer.toString(gap.dates());
                if (gap.dates() > 0) {
                    gapText = String.format(Locale.ROOT, "%.4f", gap.median());
                    largestText = String.format(Locale.ROOT, "%.4f", gap.largest());
                    gaps.add(gap.median());
                    gapOfBox.put(box.field().name(), gap.median());
                }
                fieldOfBox.put(box.field().name(), id);
                if (box.reading().equals("cut seen") && box.cutFrom() != null && box.cutTo() != null) {
                    lineLook.add(lineLook(box, id, rows, line));
                }
            } else if (rows == null && best != null) {
                id = id + " (not in series)";
            }
            System.out.printf(Locale.ROOT, "%-5s %-37s %-11s %10.1f%%  %5s  %10s  %7s%n", box.field().name(),
                    box.reading(), id, share * 100, datesText, gapText, largestText);
        }
        gaps.sort(null);
        allGaps.addAll(gaps);
        if (gaps.isEmpty()) {
            System.out.println("No box lies wholly inside a field's interior.");
        } else {
            long under = gaps.stream().filter(g -> g < 0.05).count();
            System.out.printf(Locale.ROOT, "%d of %d boxes lie wholly inside one field's interior; %d of them have a "
                    + "median gap under 0.05; median of the median gaps %.4f%n", gaps.size(), boxes.size(), under,
                    middle(gaps));
        }
        if (!lineLook.isEmpty()) {
            System.out.println("First look, line strength (median before the cut window -> after it):");
            lineLook.forEach(System.out::println);
        }

        if (page != null) {
            writePage(page, year, data, grid, labels, series, header, fieldList, boxes, fieldOfBox, gapOfBox);
        }
    }

    private static String lineLook(Box box, String id, List<Row> rows, int line) {
        List<Double> before = new ArrayList<>();
        List<Double> after = new ArrayList<>();
        for (Row row : rows) {
            double v = row.values()[line];
            if (Double.isNaN(v)) {
                continue;
            }
            if (row.date().isBefore(box.cutFrom())) {
                before.add(v);
            } else if (row.date().isAfter(box.cutTo())) {
                after.add(v);
            }
        }
        return String.format(Locale.ROOT, "  %-5s %s, cut %s to %s: %s (%d dates) -> %s (%d dates)", box.field().name(),
                id, box.cutFrom(), box.cutTo(), medianText(before), before.size(), medianText(after), after.size());
    }

    /** The median of sorted values: the mean of the middle two for an even count. */
    static double middle(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static String medianText(List<Double> values) {
        if (values.isEmpty()) {
            return "-";
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return String.format(Locale.ROOT, "%.3f", middle(sorted));
    }

    /** Every box of the season, with its reading and its NDVI on dates at least 60% clear. */
    private static List<Box> boxes(int year, List<Run> runs) throws IOException {
        Map<String, Box> boxes = new LinkedHashMap<>();
        for (Run run : runs) {
            if (run.season().year() != year) {
                continue;
            }
            Map<String, String[]> readings = new HashMap<>();
            if (run.photosRead()) {
                for (String[] parts : readCsv(run.readings())) {
                    readings.put(parts[0], parts);
                }
            }
            Map<String, Map<LocalDate, Double>> ndvi = new HashMap<>();
            Path optical = run.folder(Path.of("experiment-output")).resolve("optical.csv");
            for (String[] parts : readCsv(optical)) {
                double clear = Double.parseDouble(parts[3]);
                double value = Double.parseDouble(parts[5]);
                if (clear >= MIN_CLEAR && !Double.isNaN(value)) {
                    ndvi.computeIfAbsent(parts[0], k -> new TreeMap<>()).put(LocalDate.parse(parts[1]), value);
                }
            }
            for (Field field : run.fields()) {
                if (boxes.containsKey(field.name())) {
                    continue;
                }
                String[] reading = readings.get(field.name());
                String text = reading == null ? FieldCheck.NO_READING : reading[1];
                LocalDate from = reading != null && reading.length > 2 && !reading[2].isBlank()
                        ? LocalDate.parse(reading[2]) : null;
                LocalDate to = reading != null && reading.length > 3 && !reading[3].isBlank()
                        ? LocalDate.parse(reading[3]) : null;
                boxes.put(field.name(), new Box(field, text, from, to,
                        ndvi.getOrDefault(field.name(), new TreeMap<>())));
            }
        }
        return new ArrayList<>(boxes.values());
    }

    private static Map<String, List<Row>> readSeries(Path csv, List<String> header) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        header.addAll(List.of(lines.get(0).split(",")));
        Map<String, List<Row>> series = new LinkedHashMap<>();
        for (String text : lines.subList(1, lines.size())) {
            String[] parts = text.split(",", -1);
            double[] values = new double[parts.length];
            for (int i = 2; i < parts.length; i++) {
                values[i] = parts[i].isEmpty() ? Double.NaN : Double.parseDouble(parts[i]);
            }
            series.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(new Row(LocalDate.parse(parts[1]), values));
        }
        return series;
    }

    /** The lines after the header, split on commas. */
    private static List<String[]> readCsv(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String[]> rows = new ArrayList<>();
        for (String text : lines.subList(1, lines.size())) {
            if (!text.isBlank()) {
                rows.add(text.split(",", -1));
            }
        }
        return rows;
    }

    // ---- page data -------------------------------------------------------------------------------

    private static void writePage(Path page, int year, Path data, PixelGrid grid, int[] labels,
                                  Map<String, List<Row>> series, List<String> header, List<String[]> fieldList,
                                  List<Box> boxes, Map<String, String> fieldOfBox, Map<String, Double> gapOfBox)
            throws Exception {
        Path folder = Files.createDirectories(page.resolve("step3"));
        int width = grid.width();
        int height = grid.height();
        int outWidth = (width + SCALE - 1) / SCALE;
        int outHeight = (height + SCALE - 1) / SCALE;

        BufferedImage labelImage = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int id = labels[Math.min(height - 1, y * SCALE) * width + Math.min(width - 1, x * SCALE)];
                labelImage.setRGB(x, y, (id >> 8 & 0xFF) << 16 | (id & 0xFF) << 8);
            }
        }
        ImageIO.write(labelImage, "png", folder.resolve("labels-" + year + ".png").toFile());

        int regions = fieldList.size();
        int[] x0 = new int[regions + 1];
        int[] y0 = new int[regions + 1];
        int[] x1 = new int[regions + 1];
        int[] y1 = new int[regions + 1];
        java.util.Arrays.fill(x0, Integer.MAX_VALUE);
        java.util.Arrays.fill(y0, Integer.MAX_VALUE);
        for (int i = 0; i < labels.length; i++) {
            int id = labels[i];
            if (id > 0 && id <= regions) {
                int x = i % width;
                int y = i / width;
                x0[id] = Math.min(x0[id], x);
                y0[id] = Math.min(y0[id], y);
                x1[id] = Math.max(x1[id], x);
                y1[id] = Math.max(y1[id], y);
            }
        }

        SeasonStack stack = SeasonStack.open(data.resolve("season-" + year));
        List<LocalDate> photos = stack.entries().stream()
                .filter(e -> e.status() == StackEntry.Status.STORED && e.clearShare() >= MIN_CLEAR)
                .sorted(Comparator.comparingDouble(StackEntry::clearShare).reversed())
                .limit(MAX_PHOTOS)
                .map(StackEntry::date)
                .sorted()
                .toList();
        Path photoFolder = Files.createDirectories(folder.resolve("photos-" + year));
        for (LocalDate date : photos) {
            writePhoto(stack, date, grid, outWidth, outHeight, photoFolder.resolve(date + ".jpg"));
            System.err.println("Photo " + date);
        }

        List<LocalDate> dates = series.values().stream().findFirst().map(rows -> rows.stream().map(Row::date).toList())
                .orElse(List.of());
        String[] columns = {"clear_share", "ndvi_p25", "ndvi_median", "ndvi_p75", "b11", "b12", "ndti", "blue",
                "snow_share", "roughness", "line_strength", "line_direction"};
        String[] keys = {"clear", "p25", "med", "p75", "b11", "b12", "ndti", "blue", "snow", "rough", "line", "dir"};
        try (JsonGenerator json = new JsonFactory().createGenerator(folder.resolve("season-" + year + ".json").toFile(),
                JsonEncoding.UTF8)) {
            json.writeStartObject();
            json.writeNumberField("year", year);
            json.writeNumberField("width", outWidth);
            json.writeNumberField("height", outHeight);
            json.writeNumberField("scale", SCALE);
            json.writeArrayFieldStart("dates");
            for (LocalDate date : dates) {
                json.writeString(date.toString());
            }
            json.writeEndArray();
            json.writeArrayFieldStart("photos");
            for (LocalDate date : photos) {
                json.writeString(date.toString());
            }
            json.writeEndArray();
            json.writeArrayFieldStart("fields");
            for (int r = 0; r < fieldList.size(); r++) {
                String[] f = fieldList.get(r);
                int id = r + 1;
                json.writeStartObject();
                json.writeStringField("id", f[0]);
                json.writeStringField("land", f[1]);
                json.writeNumberField("area", Double.parseDouble(f[2]));
                json.writeNumberField("interior", Double.parseDouble(f[3]));
                json.writeArrayFieldStart("bbox");
                json.writeNumber(x0[id] == Integer.MAX_VALUE ? 0 : x0[id] / SCALE);
                json.writeNumber(y0[id] == Integer.MAX_VALUE ? 0 : y0[id] / SCALE);
                json.writeNumber(x1[id] / SCALE);
                json.writeNumber(y1[id] / SCALE);
                json.writeEndArray();
                List<Row> rows = series.get(f[0]);
                if (rows != null) {
                    for (int c = 0; c < columns.length; c++) {
                        int column = header.indexOf(columns[c]);
                        json.writeArrayFieldStart(keys[c]);
                        for (Row row : rows) {
                            number(json, row.values()[column], c == columns.length - 1 ? 1 : 3);
                        }
                        json.writeEndArray();
                    }
                }
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeArrayFieldStart("boxes");
            for (Box box : boxes) {
                json.writeStartObject();
                json.writeStringField("name", box.field().name());
                json.writeStringField("reading", box.reading());
                if (box.cutFrom() != null && box.cutTo() != null) {
                    json.writeArrayFieldStart("cut");
                    json.writeString(box.cutFrom().toString());
                    json.writeString(box.cutTo().toString());
                    json.writeEndArray();
                }
                json.writeStringField("field", fieldOfBox.get(box.field().name()));
                Double gap = gapOfBox.get(box.field().name());
                if (gap == null) {
                    json.writeNullField("gap");
                } else {
                    json.writeNumberField("gap", Math.round(gap * 10000) / 10000.0);
                }
                json.writeArrayFieldStart("rect");
                json.writeNumber(box.field().left() / (double) SCALE);
                json.writeNumber(box.field().top() / (double) SCALE);
                json.writeNumber(box.field().right() / (double) SCALE);
                json.writeNumber(box.field().bottom() / (double) SCALE);
                json.writeEndArray();
                json.writeArrayFieldStart("ndvi");
                for (LocalDate date : dates) {
                    Double v = box.ndvi().get(date);
                    number(json, v == null ? Double.NaN : v, 3);
                }
                json.writeEndArray();
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
    }

    private static void number(JsonGenerator json, double value, int decimals) throws IOException {
        if (Double.isNaN(value)) {
            json.writeNull();
        } else {
            json.writeNumber(String.format(Locale.ROOT, "%." + decimals + "f", value));
        }
    }

    /** A true-colour picture of the whole area at half resolution, as the contact sheet stretches it. */
    private static void writePhoto(SeasonStack stack, LocalDate date, PixelGrid grid, int outWidth, int outHeight,
                                   Path file) throws Exception {
        float[] red = stack.reflectance(date, Layer.RED);
        float[] green = stack.reflectance(date, Layer.GREEN);
        float[] blue = stack.reflectance(date, Layer.BLUE);
        int width = grid.width();
        int height = grid.height();
        BufferedImage image = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                double r = 0;
                double g = 0;
                double b = 0;
                int n = 0;
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        int px = x * SCALE + dx;
                        int py = y * SCALE + dy;
                        if (px < width && py < height) {
                            int i = py * width + px;
                            if (!Float.isNaN(red[i]) && !Float.isNaN(green[i]) && !Float.isNaN(blue[i])) {
                                r += red[i];
                                g += green[i];
                                b += blue[i];
                                n++;
                            }
                        }
                    }
                }
                int rgb = n == 0 ? 0 : ContactSheet.stretch(r / n) << 16 | ContactSheet.stretch(g / n) << 8
                        | ContactSheet.stretch(b / n);
                image.setRGB(x, y, rgb);
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        Files.deleteIfExists(file);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
