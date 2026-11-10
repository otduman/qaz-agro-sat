package kz.kazagrosat.pipeline.district;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.geo.DistrictPlan;
import kz.kazagrosat.core.geo.LonLat;

/**
 * A district split into squares ({@link DistrictPlan}), read from a boundary GeoJSON and kept as
 * {@code squares.csv} in the district's folder. Every square's work goes into a folder named after it.
 */
public final class District {

    public static final String PLAN = "squares.csv";
    static final String HEADER = "square,min_e,min_n,max_e,max_n,west,south,east,north,share_inside,epsg";

    private District() {}

    /** The outer ring of the boundary's largest polygon (by number of points), as longitude and latitude. */
    public static List<LonLat> readBoundary(Path geojson) throws IOException {
        JsonNode root = new ObjectMapper().readTree(geojson.toFile());
        JsonNode geometry = root.has("features") ? root.get("features").get(0).get("geometry")
                : root.has("geometry") ? root.get("geometry") : root;
        List<JsonNode> polygons = new ArrayList<>();
        switch (geometry.get("type").asText()) {
            case "Polygon" -> polygons.add(geometry.get("coordinates"));
            case "MultiPolygon" -> geometry.get("coordinates").forEach(polygons::add);
            default -> throw new IOException(geojson + " holds a " + geometry.get("type").asText()
                    + ", not a Polygon or MultiPolygon.");
        }
        JsonNode outer = null;
        for (JsonNode polygon : polygons) {
            if (outer == null || polygon.get(0).size() > outer.size()) {
                outer = polygon.get(0);
            }
        }
        List<LonLat> ring = new ArrayList<>();
        for (JsonNode point : outer) {
            ring.add(new LonLat(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        return List.copyOf(ring);
    }

    /** Plans the squares for the boundary and writes them into {@code folder}. */
    public static List<DistrictPlan.Square> plan(Path geojson, Path folder) throws IOException {
        List<LonLat> ring = readBoundary(geojson);
        int west = zone(ring.stream().mapToDouble(LonLat::longitude).min().orElseThrow());
        int east = zone(ring.stream().mapToDouble(LonLat::longitude).max().orElseThrow());
        if (west != east) {
            throw new IOException("The district spans UTM zones " + west + " and " + east
                    + ". Districts in more than one zone are not supported yet.");
        }
        int epsg = 32600 + west;
        List<DistrictPlan.Square> squares = DistrictPlan.plan(ring, epsg);
        Files.createDirectories(folder);
        List<String> lines = new ArrayList<>(List.of(HEADER));
        for (DistrictPlan.Square s : squares) {
            BoundingBox b = s.bbox();
            lines.add(String.join(",", s.id(), d(s.minE()), d(s.minN()), d(s.maxE()), d(s.maxN()), d(b.west()),
                    d(b.south()), d(b.east()), d(b.north()), d(s.shareInside()), Integer.toString(epsg)));
        }
        Files.write(folder.resolve(PLAN), lines, StandardCharsets.UTF_8);
        return squares;
    }

    /** The squares of a district folder, as {@link #plan} wrote them. */
    public static List<DistrictPlan.Square> readPlan(Path folder) throws IOException {
        Path file = folder.resolve(PLAN);
        if (!Files.isRegularFile(file)) {
            throw new IOException("No " + PLAN + " in " + folder + ". Run district plan first.");
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<DistrictPlan.Square> squares = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",");
            squares.add(new DistrictPlan.Square(c[0], p(c[1]), p(c[2]), p(c[3]), p(c[4]),
                    new BoundingBox(p(c[5]), p(c[6]), p(c[7]), p(c[8])), p(c[9])));
        }
        return List.copyOf(squares);
    }

    /** The EPSG code the plan's squares are laid out in. */
    public static int epsg(Path folder) throws IOException {
        List<String> lines = Files.readAllLines(folder.resolve(PLAN), StandardCharsets.UTF_8);
        return Integer.parseInt(lines.get(1).substring(lines.get(1).lastIndexOf(',') + 1));
    }

    private static int zone(double longitude) {
        return (int) Math.floor((longitude + 180) / 6) + 1;
    }

    private static String d(double value) {
        return Double.toString(value);
    }

    private static double p(String text) {
        return Double.parseDouble(text);
    }
}
