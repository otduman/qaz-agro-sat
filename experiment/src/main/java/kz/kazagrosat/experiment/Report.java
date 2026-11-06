package kz.kazagrosat.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kz.kazagrosat.core.raster.PixelGrid;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Writes the results page: the numbers as data, dropped into a fixed page that draws the charts, plus
 * the written findings from experiment/findings.html.
 */
final class Report {

    private static final String DATA_MARKER = "/*DATA*/null";
    private static final String FINDINGS_MARKER = "<!--FINDINGS-->";

    private Report() {}

    static void write(List<Run> runs, Path output, PixelGrid areaGrid, Path newsFile, Path findingsFile)
            throws IOException {
        CoordinateTransform toLonLat = toLonLat(areaGrid.epsg());
        List<String[]> news = Files.readAllLines(newsFile).stream().skip(1).map(line -> line.split(",")).toList();

        List<Map<String, Object>> runData = new ArrayList<>();
        for (Run run : runs) {
            Path folder = run.folder(output);
            if (!Files.exists(folder.resolve("radar.csv"))) {
                continue;
            }
            List<Analysis.OpticalRow> optical = Analysis.loadOptical(folder.resolve("optical.csv"));
            List<Analysis.RadarRow> radar = Analysis.loadRadar(folder.resolve("radar.csv"));
            Map<String, Analysis.Reading> readings =
                    run.photosRead() ? Analysis.loadReadings(run.readings()) : Map.of();
            Map<LocalDate, Double> rain = rainByDay(folder.resolve("rain.csv"));

            List<Map<String, Object>> boxes = new ArrayList<>();
            for (Field field : run.fields()) {
                boxes.add(describe(field, readings.get(field.name()), optical, radar, areaGrid, toLonLat,
                        run.season(), rain));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", run.name());
            data.put("label", run.label());
            data.put("year", run.season().year());
            data.put("photosRead", run.photosRead());
            data.put("fields", boxes);
            data.put("rain", loadRain(folder.resolve("rain.csv"), run.season()));
            List<Map<String, Object>> milestones = new ArrayList<>();
            for (String[] item : news) {
                if (Integer.parseInt(item[0]) == run.season().year()) {
                    milestones.add(Map.of("d", item[1], "share", Integer.parseInt(item[2]), "source", item[3]));
                }
            }
            data.put("news", milestones);
            if (run.photosRead()) {
                data.put("uncut", uncutBaseline(run.fields(), readings, optical, radar, rain));
            }
            runData.add(data);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("swirJump", Analysis.SWIR_JUMP);
        data.put("runs", runData);

        String page;
        try (InputStream template = Report.class.getResourceAsStream("/report.html")) {
            page = new String(template.readAllBytes(), StandardCharsets.UTF_8);
        }
        String findings = Files.exists(findingsFile) ? Files.readString(findingsFile, StandardCharsets.UTF_8) : "";
        page = page.replace(FINDINGS_MARKER, findings)
                .replace(DATA_MARKER, new ObjectMapper().writeValueAsString(data).replace("</", "<\\/"));
        Files.writeString(output.resolve("index.html"), page, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> describe(
            Field field, Analysis.Reading reading, List<Analysis.OpticalRow> optical, List<Analysis.RadarRow> radar,
            PixelGrid areaGrid, CoordinateTransform toLonLat, Study.Season season, Map<LocalDate, Double> rain) {
        List<Analysis.OpticalRow> clear = Analysis.clearRows(optical, field.name());
        List<Analysis.RadarRow> radarRows = radar.stream().filter(row -> row.field().equals(field.name())).toList();

        Map<String, Object> box = new LinkedHashMap<>();
        box.put("name", field.name());
        box.put("kind", field.kind());
        if (reading == null) {
            // Nothing that shows the rule's verdict goes on the page before the photos are read.
            box.put("reading", "photos not read yet");
            box.put("result", "unread");
        } else {
            Optional<Analysis.Step> jump = Analysis.swirJump(clear);
            box.put("reading", reading.label());
            box.put("note", reading.note());
            box.put("from", text(reading.from()));
            box.put("to", text(reading.to()));
            box.put("swirJump", jump.map(Report::step).orElse(null));
            box.put("radarDrop", Analysis.radarDrop(radarRows).map(Report::step).orElse(null));
            box.put("result", result(reading, jump));
            box.put("across", cutWindow(reading) ? across(clear, radarRows, reading.from(), reading.to(), rain) : null);
        }

        ProjCoordinate centre = new ProjCoordinate();
        toLonLat.transform(new ProjCoordinate(
                areaGrid.originX() + (field.left() + field.right()) / 2.0 * areaGrid.pixelSize(),
                areaGrid.originY() - (field.top() + field.bottom()) / 2.0 * areaGrid.pixelSize()), centre);
        box.put("lat", Math.round(centre.y * 10000) / 10000.0);
        box.put("lon", Math.round(centre.x * 10000) / 10000.0);

        List<Map<String, Object>> opticalPoints = new ArrayList<>();
        for (Analysis.OpticalRow row : clear) {
            if (inChart(row.date(), season)) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("d", row.date().toString());
                point.put("ndvi", round(row.ndvi(), 3));
                point.put("ndti", round(row.ndti(), 3));
                point.put("swir", round(row.swir16() * 100, 1));
                opticalPoints.add(point);
            }
        }
        box.put("optical", opticalPoints);

        List<Map<String, Object>> radarPoints = new ArrayList<>();
        for (Analysis.RadarRow row : radarRows) {
            if (inChart(row.date(), season)) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("d", row.date().toString());
                point.put("track", row.track());
                point.put("vv", round(row.vv(), 2));
                point.put("vh", round(row.vh(), 2));
                point.put("gap", round(row.gap(), 2));
                radarPoints.add(point);
            }
        }
        box.put("radar", radarPoints);
        return box;
    }

    /** Compares the rule's jump with what the photos show, in the words the page uses. */
    static String result(Analysis.Reading reading, Optional<Analysis.Step> jump) {
        boolean fired = jump.isPresent() && jump.get().size() >= Analysis.SWIR_JUMP;
        if (!reading.hasWindow()) {
            return fired ? "fired" : "quiet";
        }
        if (!fired) {
            return "missed";
        }
        if (jump.get().overlaps(reading.from(), reading.to())) {
            return "inside";
        }
        if (jump.get().before().equals(reading.to())) {
            return "one gap late";
        }
        return jump.get().after().equals(reading.from()) ? "one gap early" : "elsewhere";
    }

    private static boolean cutWindow(Analysis.Reading reading) {
        return reading != null && reading.hasWindow() && !reading.label().startsWith("cut before");
    }

    /** How each signal changed across one date window. */
    private static Map<String, Object> across(List<Analysis.OpticalRow> clear, List<Analysis.RadarRow> radar,
                                              LocalDate from, LocalDate to, Map<LocalDate, Double> rain) {
        // Optical dates from before ripening would turn the ripening itself into a "change at the cut".
        List<Analysis.OpticalRow> ripe = Analysis.ripeRows(clear);
        List<Analysis.Point> ndvi = ripe.stream().map(r -> new Analysis.Point(r.date(), r.ndvi())).toList();
        List<Analysis.Point> ndti = ripe.stream().map(r -> new Analysis.Point(r.date(), r.ndti())).toList();
        List<Analysis.Point> swir = ripe.stream().map(r -> new Analysis.Point(r.date(), r.swir16() * 100)).toList();
        List<Analysis.Point> gap = radar.stream().map(r -> new Analysis.Point(r.date(), r.gap())).toList();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("ndvi", round(Analysis.changeAcross(ndvi, from, to), 2));
        change.put("ndti", round(Analysis.changeAcross(ndti, from, to), 2));
        change.put("swir", round(Analysis.changeAcross(swir, from, to), 1));
        change.put("gap", round(Analysis.changeAcross(gap, from, to), 1));
        change.put("gapFair", round(Analysis.radarChangeSameOrbit(radar, from, to, rain), 1));
        return change;
    }

    /**
     * The same changes measured on the uncut boxes over every cut window of the round: how much each
     * signal moves when nobody cuts anything. Reported as the median and the largest size seen.
     */
    private static Map<String, Object> uncutBaseline(
            List<Field> fields, Map<String, Analysis.Reading> readings, List<Analysis.OpticalRow> optical,
            List<Analysis.RadarRow> radar, Map<LocalDate, Double> rain) {
        List<Analysis.Reading> windows = readings.values().stream().filter(Report::cutWindow).toList();
        Map<String, List<Double>> changes = new LinkedHashMap<>();
        for (String key : List.of("ndvi", "ndti", "swir", "gap", "gapFair")) {
            changes.put(key, new ArrayList<>());
        }
        int boxes = 0;
        for (Field field : fields) {
            Analysis.Reading reading = readings.get(field.name());
            if (reading == null || !reading.label().startsWith("no harvest")) {
                continue;
            }
            boxes++;
            List<Analysis.OpticalRow> clear = Analysis.clearRows(optical, field.name());
            List<Analysis.RadarRow> rows = radar.stream().filter(row -> row.field().equals(field.name())).toList();
            for (Analysis.Reading window : windows) {
                Map<String, Object> change = across(clear, rows, window.from(), window.to(), rain);
                change.forEach((key, value) -> {
                    if (value != null) {
                        changes.get(key).add((Double) value);
                    }
                });
            }
        }
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("boxes", boxes);
        changes.forEach((key, values) -> {
            if (values.isEmpty()) {
                return;
            }
            List<Double> sorted = values.stream().sorted().toList();
            double middle = sorted.size() % 2 == 1 ? sorted.get(sorted.size() / 2)
                    : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;
            baseline.put(key, Map.of("median", round(middle, 2), "min", round(sorted.getFirst(), 2),
                    "max", round(sorted.getLast(), 2), "count", values.size()));
        });
        return baseline;
    }

    private static Map<LocalDate, Double> rainByDay(Path file) throws IOException {
        Map<LocalDate, Double> rain = new java.util.HashMap<>();
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines.subList(1, lines.size())) {
                String[] parts = line.split(",");
                rain.put(LocalDate.parse(parts[0]), Double.parseDouble(parts[1]));
            }
        }
        return rain;
    }

    private static Map<String, Object> step(Analysis.Step step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("before", step.before().toString());
        map.put("after", step.after().toString());
        map.put("size", round(step.size(), 1));
        return map;
    }

    private static List<Map<String, Object>> loadRain(Path file, Study.Season season) throws IOException {
        List<Map<String, Object>> rain = new ArrayList<>();
        if (!Files.exists(file)) {
            return rain;
        }
        List<String> lines = Files.readAllLines(file);
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(",");
            if (inChart(LocalDate.parse(parts[0]), season)) {
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("d", parts[0]);
                day.put("mm", Double.parseDouble(parts[1]));
                rain.add(day);
            }
        }
        return rain;
    }

    private static boolean inChart(LocalDate date, Study.Season season) {
        return !date.isBefore(season.chartFrom()) && !date.isAfter(season.chartTo());
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static Double round(double value, int places) {
        if (Double.isNaN(value)) {
            return null;
        }
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    /** Northern UTM only, like the rest of the project; the EPSG lookup table is not on the classpath. */
    private static CoordinateTransform toLonLat(int epsg) {
        if (epsg < 32601 || epsg > 32660) {
            throw new IllegalArgumentException("Not a northern UTM zone: EPSG:" + epsg);
        }
        CRSFactory factory = new CRSFactory();
        return new CoordinateTransformFactory().createTransform(
                factory.createFromParameters("UTM", "+proj=utm +zone=" + (epsg - 32600) + " +datum=WGS84 +units=m +no_defs"),
                factory.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs"));
    }
}
