package kz.kazagrosat.pipeline.district;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kz.kazagrosat.core.geo.DistrictPlan;
import kz.kazagrosat.core.geo.UtmPoint;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;

/**
 * Joins a season's square results into district tables:
 * <ul>
 *   <li>{@code district-harvest-<year>.csv}: every field once, from the square whose core holds its centre, with its
 *       square, centre and whether it touches its square's edge (and so may be cut off);</li>
 *   <li>{@code district-summary-<year>.txt}: totals, one row per square with its checks, and the overlap check:
 *       fields seen whole by two neighbouring squares should get the same cut window from both.</li>
 * </ul>
 */
public final class DistrictJoin {

    /** Two copies are the same field when their centres are this close and their areas this similar. */
    static final double SAME_FIELD_METRES = 150;
    static final double SAME_FIELD_AREA = 0.25;

    private DistrictJoin() {}

    public record Result(int squares, int fields, int overlapPairs, int overlapAgree) {}

    private record Row(String square, String[] harvest, double lat, double lon, double e, double n, boolean edge) {

        String status() {
            return harvest[3];
        }

        double area() {
            return Double.parseDouble(harvest[2]);
        }

        boolean hasWindow() {
            return !harvest[4].isEmpty();
        }

        LocalDate from() {
            return LocalDate.parse(harvest[4]);
        }

        LocalDate to() {
            return LocalDate.parse(harvest[5]);
        }
    }

    public static Result join(Path district, int year) throws IOException, InterruptedException {
        List<DistrictPlan.Square> squares = District.readPlan(district);
        UtmProjection utm = UtmProjection.forEpsg(District.epsg(district));
        String y = Integer.toString(year);
        List<Row> all = new ArrayList<>();
        List<String> table = new ArrayList<>();
        int joined = 0;
        List<String> missing = new ArrayList<>();
        for (DistrictPlan.Square s : squares) {
            Path sq = district.resolve(s.id());
            Path harvestCsv = sq.resolve("harvest-" + y).resolve("harvest-" + y + ".csv");
            if (!Files.isRegularFile(harvestCsv)) {
                missing.add(s.id());
                continue;
            }
            joined++;
            Map<String, double[]> centres = centres(sq.resolve("series-" + y).resolve("fields-" + y + ".csv"));
            Set<Integer> edge = edgeRegions(sq.resolve("fields-" + y).resolve("fields-" + y + ".tif"));
            List<String> lines = Files.readAllLines(harvestCsv, StandardCharsets.UTF_8);
            int[] counts = new int[5];
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split(",", -1);
                double[] centre = centres.get(c[0]);
                UtmPoint u = utm.project(centre[1], centre[0]);
                int region = Integer.parseInt(c[0].substring(c[0].indexOf("-F") + 2));
                all.add(new Row(s.id(), c, centre[0], centre[1], u.easting(), u.northing(), edge.contains(region)));
                switch (c[3]) {
                    case "dated" -> counts[0]++;
                    case "cut in a cloud gap" -> counts[1]++;
                    case "no cut found" -> counts[2]++;
                    case "no crop seen" -> counts[4]++;
                    default -> counts[3]++;
                }
            }
            Map<String, String> checks = checks(sq.resolve("harvest-" + y).resolve("summary-" + y + ".txt"));
            table.add(String.join(" | ", s.id(), Integer.toString(lines.size() - 1), Integer.toString(counts[0]),
                    Integer.toString(counts[1]), Integer.toString(counts[2]), Integer.toString(counts[3]),
                    Integer.toString(counts[4]), checks.getOrDefault("half", "-"), checks.getOrDefault("bare", "-"),
                    checks.getOrDefault("agree", "-"), checks.getOrDefault("screened", "-")));
        }
        Map<String, DistrictPlan.Square> byId = new HashMap<>();
        squares.forEach(s -> byId.put(s.id(), s));
        List<Row> kept = all.stream().filter(r -> byId.get(r.square()).inCore(r.e(), r.n())).toList();

        int pairs = 0;
        int agree = 0;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Row a = all.get(i);
                Row b = all.get(j);
                if (a.square().equals(b.square()) || Math.hypot(a.e() - b.e(), a.n() - b.n()) > SAME_FIELD_METRES
                        || Math.abs(a.area() - b.area()) > SAME_FIELD_AREA * Math.max(a.area(), b.area())
                        || !a.hasWindow() || !b.hasWindow()) {
                    continue;
                }
                pairs++;
                if (!a.to().isBefore(b.from()) && !b.to().isBefore(a.from())) {
                    agree++;
                }
            }
        }

        List<String> out = new ArrayList<>(List.of("square,field,land,area_ha,status,cut_from,cut_to,cut_middle,"
                + "window_days,rise,readings,centroid_lat,centroid_lon,touches_edge"));
        for (Row r : kept) {
            out.add(r.square() + "," + String.join(",", r.harvest()) + String.format(Locale.ROOT, ",%.6f,%.6f,%s",
                    r.lat(), r.lon(), r.edge() ? "yes" : "no"));
        }
        Files.write(district.resolve("district-harvest-" + y + ".csv"), out, StandardCharsets.UTF_8);

        StringBuilder text = new StringBuilder();
        line(text, "Season %d: district of %d squares", year, squares.size());
        line(text, "Squares joined: %d%s", joined, missing.isEmpty() ? "" : " (not yet run: " + missing.size() + ")");
        line(text, "Fields: %d, %.0f ha (each once, from the square whose core holds its centre; %d touch their"
                + " square's edge)", kept.size(), kept.stream().mapToDouble(Row::area).sum(),
                kept.stream().filter(Row::edge).count());
        for (String status : List.of("dated", "cut in a cloud gap", "no cut found", "unclear", "no crop seen")) {
            List<Row> these = kept.stream().filter(r -> r.status().equals(status)).toList();
            line(text, "  %s: %d fields, %.0f ha", status, these.size(), these.stream().mapToDouble(Row::area).sum());
        }
        line(text, "");
        line(text, "Checks");
        line(text, "  Fields seen by two squares with the same cut window: %d of %d", agree, pairs);
        line(text, "");
        line(text, "Square | fields | dated | cloud gap | no cut found | unclear | no crop seen | half cut by | bare fallow cut"
                + " | Sentinel-2 alone agrees | screened dates");
        table.forEach(row -> line(text, "%s", row));
        Files.writeString(district.resolve("district-summary-" + y + ".txt"), text, StandardCharsets.UTF_8);
        return new Result(joined, kept.size(), pairs, agree);
    }

    /** Field id to {latitude, longitude}, from a series field list. */
    private static Map<String, double[]> centres(Path fieldsCsv) throws IOException {
        List<String> lines = Files.readAllLines(fieldsCsv, StandardCharsets.UTF_8);
        List<String> head = List.of(lines.get(0).split(","));
        Map<String, double[]> centres = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", -1);
            centres.put(c[0], new double[] {Double.parseDouble(c[head.indexOf("centroid_lat")]),
                    Double.parseDouble(c[head.indexOf("centroid_lon")])});
        }
        return centres;
    }

    /** Regions with a pixel on the label map's outer row or column. */
    private static Set<Integer> edgeRegions(Path labels) throws IOException, InterruptedException {
        CogReader reader = CogReader.open(new FileByteSource(labels));
        PixelGrid grid = reader.grid();
        int[] values = reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
        Set<Integer> edge = new HashSet<>();
        int w = grid.width();
        int h = grid.height();
        for (int x = 0; x < w; x++) {
            edge.add(values[x]);
            edge.add(values[(h - 1) * w + x]);
        }
        for (int yy = 0; yy < h; yy++) {
            edge.add(values[yy * w]);
            edge.add(values[yy * w + w - 1]);
        }
        edge.remove(0);
        return edge;
    }

    /** The halfway date, bare-fallow check, Sentinel-2 agreement and screened count from a harvest summary. */
    private static Map<String, String> checks(Path summary) throws IOException {
        Map<String, String> checks = new HashMap<>();
        if (!Files.isRegularFile(summary)) {
            return checks;
        }
        for (String line : Files.readAllLines(summary, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.startsWith("Half of the dated area cut by: ")) {
                checks.put("half", t.substring("Half of the dated area cut by: ".length()));
            } else if (t.startsWith("Bare fallow")) {
                checks.put("bare", t.substring(t.indexOf("found: ") + "found: ".length()));
            } else if (t.startsWith("Sentinel-2 alone agrees: ")) {
                String rest = t.substring("Sentinel-2 alone agrees: ".length());
                checks.put("agree", rest.substring(0, rest.indexOf(" fields")));
            } else if (t.startsWith("Screened dates: ")) {
                checks.put("screened", t.substring("Screened dates: ".length()));
            }
        }
        return checks;
    }

    private static void line(StringBuilder s, String format, Object... values) {
        s.append(String.format(Locale.ROOT, format, values)).append(System.lineSeparator());
    }
}
