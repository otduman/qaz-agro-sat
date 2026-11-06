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

/** Writes the results page: the numbers as data, dropped into a fixed page that draws the charts. */
final class Report {

    private static final String MARKER = "/*DATA*/null";
    private static final LocalDate CHART_FROM = LocalDate.of(2025, 7, 1);
    private static final LocalDate CHART_TO = LocalDate.of(2025, 10, 25);

    private Report() {}

    static void write(List<Field> fields, Path output, Path readingsFile, PixelGrid areaGrid) throws IOException {
        List<Analysis.OpticalRow> optical = Analysis.loadOptical(output.resolve("optical.csv"));
        List<Analysis.RadarRow> radar = Analysis.loadRadar(output.resolve("radar.csv"));
        Map<String, Analysis.Reading> readings = Analysis.loadReadings(readingsFile);
        CoordinateTransform toLonLat = toLonLat(areaGrid.epsg());

        List<Map<String, Object>> boxes = new ArrayList<>();
        for (Field field : fields) {
            boxes.add(describe(field, readings.get(field.name()), optical, radar, areaGrid, toLonLat));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fields", boxes);
        data.put("rain", loadRain(output.resolve("rain.csv")));
        data.put("swirJump", Analysis.SWIR_JUMP);

        String page;
        try (InputStream template = Report.class.getResourceAsStream("/report.html")) {
            page = new String(template.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(output.resolve("index.html"),
                page.replace(MARKER, new ObjectMapper().writeValueAsString(data)), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> describe(
            Field field, Analysis.Reading reading, List<Analysis.OpticalRow> optical, List<Analysis.RadarRow> radar,
            PixelGrid areaGrid, CoordinateTransform toLonLat) {
        List<Analysis.OpticalRow> clear = Analysis.clearRows(optical, field.name());
        List<Analysis.RadarRow> radarRows = radar.stream().filter(row -> row.field().equals(field.name())).toList();
        Optional<Analysis.Step> jump = Analysis.swirJump(clear);
        Optional<Analysis.Step> drop = Analysis.radarDrop(radarRows);

        Map<String, Object> box = new LinkedHashMap<>();
        box.put("name", field.name());
        box.put("kind", field.kind());
        box.put("reading", reading.label());
        box.put("note", reading.note());
        box.put("from", text(reading.from()));
        box.put("to", text(reading.to()));
        box.put("swirJump", jump.map(Report::step).orElse(null));
        box.put("radarDrop", drop.map(Report::step).orElse(null));
        box.put("result", result(reading, jump));
        box.put("across", across(reading, clear, radarRows));

        ProjCoordinate centre = new ProjCoordinate();
        toLonLat.transform(new ProjCoordinate(
                areaGrid.originX() + (field.left() + field.right()) / 2.0 * areaGrid.pixelSize(),
                areaGrid.originY() - (field.top() + field.bottom()) / 2.0 * areaGrid.pixelSize()), centre);
        box.put("lat", Math.round(centre.y * 10000) / 10000.0);
        box.put("lon", Math.round(centre.x * 10000) / 10000.0);

        List<Map<String, Object>> opticalPoints = new ArrayList<>();
        for (Analysis.OpticalRow row : clear) {
            if (inChart(row.date())) {
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
            if (inChart(row.date())) {
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
        if (!reading.hasWindow() || reading.label().startsWith("cut before")) {
            return fired ? "fired" : "quiet";
        }
        if (!fired) {
            return "missed";
        }
        if (jump.get().overlaps(reading.from(), reading.to())) {
            return "inside";
        }
        return jump.get().before().equals(reading.to()) ? "one gap late" : "elsewhere";
    }

    private static Map<String, Object> across(
            Analysis.Reading reading, List<Analysis.OpticalRow> clear, List<Analysis.RadarRow> radar) {
        if (!reading.hasWindow() || reading.label().startsWith("cut before")) {
            return null;
        }
        // Optical dates from before ripening would turn the ripening itself into a "change at the cut".
        List<Analysis.OpticalRow> ripe = Analysis.ripeRows(clear);
        List<Analysis.Point> ndvi = ripe.stream().map(r -> new Analysis.Point(r.date(), r.ndvi())).toList();
        List<Analysis.Point> ndti = ripe.stream().map(r -> new Analysis.Point(r.date(), r.ndti())).toList();
        List<Analysis.Point> swir = ripe.stream().map(r -> new Analysis.Point(r.date(), r.swir16() * 100)).toList();
        List<Analysis.Point> gap = radar.stream().map(r -> new Analysis.Point(r.date(), r.gap())).toList();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("ndvi", round(Analysis.changeAcross(ndvi, reading.from(), reading.to()), 2));
        change.put("ndti", round(Analysis.changeAcross(ndti, reading.from(), reading.to()), 2));
        change.put("swir", round(Analysis.changeAcross(swir, reading.from(), reading.to()), 1));
        change.put("gap", round(Analysis.changeAcross(gap, reading.from(), reading.to()), 1));
        return change;
    }

    private static Map<String, Object> step(Analysis.Step step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("before", step.before().toString());
        map.put("after", step.after().toString());
        map.put("size", round(step.size(), 1));
        return map;
    }

    private static List<Map<String, Object>> loadRain(Path file) throws IOException {
        List<Map<String, Object>> rain = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(",");
            if (inChart(LocalDate.parse(parts[0]))) {
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("d", parts[0]);
                day.put("mm", Double.parseDouble(parts[1]));
                rain.add(day);
            }
        }
        return rain;
    }

    private static boolean inChart(LocalDate date) {
        return !date.isBefore(CHART_FROM) && !date.isAfter(CHART_TO);
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
